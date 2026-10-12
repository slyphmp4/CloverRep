package com.slyph.cloverrep.utils;

import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilTest {
    @Test
    void bothLegacyHexFormsBecomeExactAdventureRgbColors() {
        assertEquals(TextColor.color(0x21FF5D), ColorUtil.toComponent("&21FF5DText").color());
        assertEquals(TextColor.color(0x21FF5D), ColorUtil.toComponent("&#21FF5DText").color());
    }

    @Test
    void existingNicknameColorFormatsStayCompatible() {
        assertEquals("\u00a78", ColorUtil.resolveColorCode("DARK_GRAY", "fallback"));
        assertEquals("\u00a78", ColorUtil.resolveColorCode("&8", "fallback"));
        assertEquals("\u00a78", ColorUtil.resolveColorCode("\u00a78", "fallback"));
        assertEquals("fallback", ColorUtil.resolveColorCode("unknown", "fallback"));
        assertEquals("", ColorUtil.applyColor(null));
    }
}
