package com.slyph.cloverrep.placeholders;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.config.ConfigManager;
import com.slyph.cloverrep.managers.ReputationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReputationPlaceholderTest {
    @Test
    void uuidNicknameUsesThatUuidsScoreWithoutResolvingTheNameAgain() {
        CloverRepPlugin plugin = mock(CloverRepPlugin.class);
        ReputationManager manager = mock(ReputationManager.class);
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.getReputationManager()).thenReturn(manager);
        when(plugin.getConfigManager()).thenReturn(config);
        UUID id = UUID.randomUUID();
        when(manager.findNameByUuid(id)).thenReturn("RenamedPlayer");
        when(manager.getReputation(id)).thenReturn(-30);
        when(config.getDarkNickThreshold()).thenReturn(-20);
        when(config.getDarkNickColor()).thenReturn("DARK_GRAY");
        ReputationPlaceholder placeholder = new ReputationPlaceholder(plugin);
        assertEquals("\u00a78RenamedPlayer", placeholder.onRequest(null, "nick_uuid_" + id));
        verify(manager, never()).findUuidByName(anyString());
        verify(manager, never()).registerPlayerIdentity(any());
    }
}
