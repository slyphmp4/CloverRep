package com.slyph.cloverrep.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoundUtilTest {
    @Test
    void convertsTheDefaultLegacyBukkitSound() {
        assertEquals("minecraft:entity.villager.no", SoundUtil.toSoundKey("ENTITY_VILLAGER_NO"));
    }

    @Test
    void preservesUnderscoresInVanillaSoundFamilies() {
        assertEquals("minecraft:block.note_block.pling", SoundUtil.toSoundKey("BLOCK_NOTE_BLOCK_PLING"));
        assertEquals("minecraft:music_disc.cat", SoundUtil.toSoundKey("MUSIC_DISC_CAT"));
    }

    @Test
    void supportsVanillaAndModdedNamespacedSoundKeys() {
        assertEquals("minecraft:entity.villager.no", SoundUtil.toSoundKey("minecraft:entity.villager.no"));
        assertEquals("some_mod:custom_sound", SoundUtil.toSoundKey("some_mod:custom_sound"));
    }

    @Test
    void rejectsMalformedKeysWithoutCrashing() {
        assertNull(SoundUtil.toSoundKey(""));
        assertNull(SoundUtil.toSoundKey("bad sound !"));
    }
}
