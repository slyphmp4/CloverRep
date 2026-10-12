package com.slyph.cloverrep.managers;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.commands.RepCommand;
import com.slyph.cloverrep.config.ConfigManager;
import com.slyph.cloverrep.storage.ReputationDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepCommandTest {
    private ReputationManager manager;
    private ReputationDataSource storage;
    private RepCommand command;
    private Player voter;
    private Player target;
    private final Queue<Runnable> mainThreadActions = new ConcurrentLinkedQueue<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        CloverRepPlugin plugin = mock(CloverRepPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getSuccessMessage()).thenReturn("score=%reputation%");
        when(config.getAlreadyVotedMessage()).thenReturn("already voted");
        when(config.getStorageErrorMessage()).thenReturn("storage unavailable");
        when(config.getSelfVoteErrorMessage()).thenReturn("self vote");
        when(config.getErrorSound()).thenReturn("");
        doAnswer(invocation -> { mainThreadActions.add(invocation.getArgument(0)); return null; })
                .when(plugin).runOnMainThread(any(Runnable.class));
        storage = mock(ReputationDataSource.class);
        when(storage.saveData()).thenReturn(true);
        manager = new ReputationManager(plugin, storage, null);
        when(plugin.getReputationManager()).thenReturn(manager);
        command = new RepCommand(plugin);
        voter = player("Voter");
        target = player("Target");
        when(voter.hasPermission("cloverrep.rep.plus")).thenReturn(true);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Target")).thenReturn(target);
        bukkit.when(() -> Bukkit.getPlayerExact("Voter")).thenReturn(voter);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        bukkit.close();
    }

    private void execute(String targetName) {
        assertTrue(command.onCommand(voter, mock(Command.class), "rep", new String[]{"+", targetName}));
    }

    private void drainAndReply() throws Exception {
        manager.saveAllData().get(3, TimeUnit.SECONDS);
        Runnable action;
        while ((action = mainThreadActions.poll()) != null) action.run();
    }

    @Test
    void successIsScheduledOnMainThreadWithTheCommittedScore() throws Exception {
        when(storage.applyVote(voter.getUniqueId(), target.getUniqueId(), 1)).thenReturn(5);
        execute("Target");
        verify(voter, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        drainAndReply();
        verify(voter).sendMessage(com.slyph.cloverrep.utils.ColorUtil.toComponent("score=5"));
    }

    @Test
    void duplicateVoteGetsAnErrorWithoutReportingSuccess() throws Exception {
        when(storage.applyVote(voter.getUniqueId(), target.getUniqueId(), 1)).thenReturn(null);
        when(storage.hasVoted(voter.getUniqueId(), target.getUniqueId())).thenReturn(true);
        execute("Target");
        drainAndReply();
        verify(voter).sendMessage(com.slyph.cloverrep.utils.ColorUtil.toComponent("already voted"));
    }

    @Test
    void selfVoteDoesNotReachStorage() {
        execute("Voter");
        verify(voter).sendMessage(com.slyph.cloverrep.utils.ColorUtil.toComponent("self vote"));
        verifyNoInteractions(storage);
    }

    @Test
    void disconnectedSenderDoesNotReceiveTheDelayedReply() throws Exception {
        when(storage.applyVote(voter.getUniqueId(), target.getUniqueId(), 1)).thenReturn(5);
        execute("Target");
        when(voter.isOnline()).thenReturn(false);
        drainAndReply();
        verify(voter, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
