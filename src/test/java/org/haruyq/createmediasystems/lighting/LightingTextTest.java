package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;

class LightingTextTest {
    @Test
    void darkPaletteColorsRemainReadableWithoutLosingTheirHue() {
        for (int color : new int[]{0, 0x080808, 0x000020, 0x200000, 0x202000}) {
            int readable = LightingText.readable(color);
            double brightness = ((readable >> 16 & 255) * 54 + (readable >> 8 & 255) * 183 + (readable & 255) * 19) / 256.0;
            assertTrue(brightness >= 149);
        }
        int blue = LightingText.readable(0x000020);
        assertTrue((blue & 255) > (blue >> 16 & 255));
        assertEquals(blue >> 16 & 255, blue >> 8 & 255);
        assertEquals(0x20C0FF, LightingText.readable(0x20C0FF));
        assertEquals(0x00FF00, LightingText.readable(0x00FF00));
    }

    @Test
    void groupAndNestedValueStylesSurviveWithoutMutatingTheirInputs() {
        LightingSettings settings = new LightingSettings();
        settings.groups[2].colors[0] = 0x302060;
        int color = LightingText.readable(0x302060);
        assertEquals(color, LightingText.group(settings, 2).getStyle().getColor().getValue());
        settings.groups[2].colors[1] = 0xFF0000;
        assertEquals(color, LightingText.groupColor(settings, 2));
        assertEquals(LightingText.TEXT, LightingText.group(null, 2).getStyle().getColor().getValue());
        var source = Component.literal("source").withColor(LightingText.OFF).append(LightingText.on(true));
        var copy = LightingText.text(source, LightingText.NUMBER);
        assertNotSame(source, copy);
        assertEquals(LightingText.OFF, source.getStyle().getColor().getValue());
        assertEquals(LightingText.NUMBER, copy.getStyle().getColor().getValue());
        assertEquals(LightingText.ON, copy.getSiblings().getFirst().getStyle().getColor().getValue());
        var label = LightingText.label("enabled", LightingText.on(false));
        assertEquals(LightingText.OFF, label.getSiblings().getLast().getStyle().getColor().getValue());
    }
}
