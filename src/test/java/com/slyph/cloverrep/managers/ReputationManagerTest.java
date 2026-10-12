package com.slyph.cloverrep.managers;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.storage.ReputationDataSource;
import com.slyph.cloverrep.storage.StorageException;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReputationManagerTest {
    @TempDir Path directory;
    private ReputationDataSource storage;
    private ReputationManager manager;
    private Player voter;
    private Player target;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        CloverRepPlugin plugin = mock(CloverRepPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        storage = mock(ReputationDataSource.class);
        when(storage.saveData()).thenReturn(true);
        manager = new ReputationManager(plugin, storage, null);
        voter = player("Voter");
        target = player("Target");
        targetId = target.getUniqueId();
    }

    @AfterEach void tearDown() { manager.shutdown(); }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    void coldPlaceholderDoesNotBlockAndCoalescesConcurrentLoads() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storage.getReputation(targetId)).thenAnswer(invocation -> {
            assertEquals("CloverRep-storage", Thread.currentThread().getName());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return 23;
        });
        try {
            assertEquals(0, manager.getReputation(targetId));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 100; index++) {
                assertEquals(0, manager.getReputation(targetId));
            }
        } finally {
            release.countDown();
        }
        manager.saveAllData().get(3, TimeUnit.SECONDS);
        assertEquals(23, manager.getReputation(targetId));
        verify(storage, times(1)).getReputation(targetId);
    }

    @Test
    void failedReadDoesNotPoisonTheCacheWithZeroOrHammerTheDatabase() throws Exception {
        when(storage.getReputation(targetId)).thenThrow(new StorageException("unavailable", null));
        assertEquals(0, manager.getReputation(targetId));
        manager.saveAllData().get(3, TimeUnit.SECONDS);
        for (int index = 0; index < 100; index++) manager.getReputation(targetId);
        assertEquals(0, manager.getReputationCacheSize());
        verify(storage, times(1)).getReputation(targetId);
    }

    @Test
    void voteResultCarriesExactCommittedScoreAndRunsOffTheCallerThread() throws Exception {
        when(storage.applyVote(voter.getUniqueId(), targetId, 1)).thenAnswer(invocation -> {
            assertEquals("CloverRep-storage", Thread.currentThread().getName());
            return 7;
        });
        var result = manager.addReputation(voter, target, 1, false).get(3, TimeUnit.SECONDS);
        assertEquals(ReputationManager.VoteChangeStatus.APPLIED, result.status());
        assertEquals(7, result.reputation());
        assertEquals(7, manager.getReputation(targetId));
        assertTrue(manager.hasPendingChanges());
    }

    @Test
    void backupIsNotReportedSuccessfulWhenFlushFails() throws Exception {
        manager.loadPlayer(target).get(3, TimeUnit.SECONDS);
        when(storage.saveData()).thenReturn(false);
        assertFalse(manager.createBackup().get(3, TimeUnit.SECONDS).success());
        assertTrue(manager.hasPendingChanges());
        verify(storage, never()).createBackup(any());
        when(storage.saveData()).thenReturn(true);
    }

    @Test
    void failedIdentityWriteIsRetried() throws Exception {
        doThrow(new StorageException("unavailable", null)).doNothing().when(storage).upsertPlayerIdentity(targetId, "Target");
        assertThrows(Exception.class, () -> manager.loadPlayer(target).get(3, TimeUnit.SECONDS));
        manager.loadPlayer(target).get(3, TimeUnit.SECONDS);
        verify(storage, times(2)).upsertPlayerIdentity(targetId, "Target");
    }
}
