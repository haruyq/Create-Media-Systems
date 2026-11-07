package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class LightingSettingsTest {
    @Test
    void builtInPresetsKeepThePaletteAndPlacementWhileReplacingTheEffect() {
        LightingSettings.Group group = colors(0x123456, 0xABCDEF);
        group.range = 48;
        group.brightness = 0.65f;
        group.pan = 25;
        group.tilt = -30;
        UUID player = UUID.randomUUID();
        group.preset(4, player);
        assertEquals(4, group.pattern);
        assertTrue(group.audio);
        assertTrue(group.colorCycle);
        group.colorMode = 1;
        group.preset(5, player);
        assertEquals(3, group.motion);
        assertEquals(0, group.pattern);
        assertEquals(30, group.spread);
        assertEquals(player, group.target);
        assertFalse(group.audio);
        assertFalse(group.colorCycle);
        assertEquals(0, group.colorMode);
        assertArrayEquals(new int[]{0x123456, 0xABCDEF}, java.util.Arrays.copyOf(group.colors, 2));
        assertEquals(48, group.range);
        assertEquals(0.65f, group.brightness);
        assertEquals(25, group.pan);
        assertEquals(-30, group.tilt);
        group.preset(0, player);
        assertEquals(0, group.motion);
        assertEquals(0, group.pattern);
    }

    @Test
    void savedPresetSurvivesApplyingAndEditingBasicEffects() {
        LightingSettings.Group source = colors(0x123456, 0xABCDEF);
        source.motion = 2;
        source.speed = 2.5f;
        source.colorMode = 1;
        CompoundTag saved = source.save();
        source.preset(1, UUID.randomUUID());
        source.colors[0] = 0;
        LightingSettings.Group target = new LightingSettings.Group();
        target.load(saved);
        assertEquals(0x123456, target.colors[0]);
        assertEquals(2, target.motion);
        assertEquals(2.5f, target.speed);
        assertEquals(1, target.colorMode);
    }

    private static LightingSettings.Group colors(int... colors) {
        LightingSettings.Group group = new LightingSettings.Group();
        group.colorCount = colors.length;
        System.arraycopy(colors, 0, group.colors, 0, colors.length);
        return group;
    }

    private static int[] row(LightingSettings.Group group, int fixtures) {
        int[] result = new int[fixtures];
        for (int i = 0; i < fixtures; i++) {
            result[i] = LightingSettings.color(group, i, fixtures, 0, 0, 0, false);
        }
        return result;
    }

    @Test
    void usesMultipleColorsSimultaneouslyInAlternatingAndContiguousGroups() {
        LightingSettings.Group group = colors(0x2040FF, 0xA040FF, 0xFF3080);
        assertArrayEquals(new int[]{0x2040FF, 0xA040FF, 0xFF3080, 0x2040FF, 0xA040FF, 0xFF3080, 0x2040FF}, row(group, 7));
        group.mapping = 1;
        assertArrayEquals(new int[]{0x2040FF, 0x2040FF, 0x2040FF, 0xA040FF, 0xA040FF, 0xFF3080, 0xFF3080}, row(group, 7));
        group.colorCount = 1;
        for (int color : row(group, 7)) {
            assertEquals(0x2040FF, color);
        }
    }

    @Test
    void gradientReachesBothEndsAndInterpolatesAcrossEveryRegisteredColor() {
        LightingSettings.Group group = colors(0xFF0000, 0x0000FF);
        group.mapping = 2;
        assertArrayEquals(new int[]{0xFF0000, 0x800080, 0x0000FF}, row(group, 3));
        assertArrayEquals(new int[]{0xFF0000}, row(group, 1));
        group = colors(0xFF0000, 0x00FF00, 0x0000FF);
        group.mapping = 2;
        assertArrayEquals(new int[]{0xFF0000, 0x808000, 0x00FF00, 0x008080, 0x0000FF}, row(group, 5));
    }

    @Test
    void everySupportedColorCountCanMapSparseAndFullFixtureGroups() {
        for (int colors = 1; colors <= LightingSettings.COLORS; colors++) {
            LightingSettings.Group group = new LightingSettings.Group();
            group.colorCount = colors;
            for (int mapping = 0; mapping < 3; mapping++) {
                group.mapping = mapping;
                for (int fixtures : new int[]{1, 2, 7, 64}) {
                    for (int color : row(group, fixtures)) {
                        assertEquals(0, color & 0xFF000000);
                    }
                    if (mapping == 2 && fixtures > 1) {
                        assertEquals(group.colors[0], row(group, fixtures)[0]);
                        assertEquals(group.colors[colors - 1], row(group, fixtures)[fixtures - 1]);
                    }
                }
            }
        }
    }

    @Test
    void colorCycleIsContinuousAtColorAndLoopBoundaries() {
        LightingSettings.Group group = colors(0xFF0000, 0x00FF00, 0x0000FF);
        group.colorCycle = true;
        group.speed = 1;
        for (double boundary : new double[]{0, 2, 4, 6, 6000}) {
            int before = LightingSettings.color(group, 0, 1, boundary - 0.00001, 0, 0, false);
            int after = LightingSettings.color(group, 0, 1, boundary + 0.00001, 0, 0, false);
            for (int shift : new int[]{0, 8, 16}) {
                assertEquals(before >> shift & 255, after >> shift & 255, 1);
            }
        }
        assertEquals(0xFF0000, LightingSettings.color(group, 0, 1, 6, 0, 0, false));
        group.speed = 4;
        assertEquals(0xFF0000, LightingSettings.color(group, 0, 1, 1.5, 0, 0, false));
    }

    @Test
    void videoUsesIndependentLeftAndRightColorsAndFallsBackToTheSavedPalette() {
        LightingSettings.Group group = colors(0x123456, 0x789ABC, 0xFEDCBA);
        group.colorMode = 1;
        assertEquals(0xFF0000, LightingSettings.color(group, 0, 4, 0, 0xFF0000, 0x0000FF, true));
        assertEquals(0x0000FF, LightingSettings.color(group, 1, 4, 0, 0xFF0000, 0x0000FF, true));
        assertEquals(0x789ABC, LightingSettings.color(group, 1, 4, 0, 0xFF0000, 0x0000FF, false));
        group.mapping = 2;
        assertEquals(0x800080, LightingSettings.color(group, 1, 3, 0, 0xFF0000, 0x0000FF, true));
    }

    @Test
    void rgbPadHasDistinctBoundedColorsAndTheRequestedCornerHues() {
        Set<Integer> colors = new HashSet<>();
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                int color = LightingSettings.palette(column, row);
                assertEquals(0, color & 0xFF000000);
                colors.add(color);
            }
        }
        assertEquals(64, colors.size());
        assertEquals(0x00FF00, LightingSettings.palette(0, 0));
        assertEquals(0x0000FF, LightingSettings.palette(7, 0));
        assertEquals(0xFF8C00, LightingSettings.palette(0, 7));
        assertEquals(0xFF00FF, LightingSettings.palette(7, 7));
        assertEquals(LightingSettings.palette(0, 7), LightingSettings.palette(-10, 20));
    }

    @Test
    void malformedSavedValuesCannotEscapeOperatingBoundsOrIntroduceNaN() {
        CompoundTag group = new CompoundTag();
        group.putIntArray("Colors", new int[]{0xFF123456, -1});
        group.putInt("ColorCount", -10);
        group.putInt("Mapping", 100);
        group.putInt("ColorMode", -20);
        group.putInt("Motion", 99);
        group.putInt("Pattern", -1);
        group.putInt("AudioBand", 99);
        group.putFloat("Brightness", Float.NaN);
        group.putFloat("Range", Float.POSITIVE_INFINITY);
        group.putFloat("Spread", Float.NEGATIVE_INFINITY);
        group.putFloat("Speed", -3);
        group.putFloat("Pan", 1000);
        group.putFloat("Tilt", -1000);
        CompoundTag saved = new CompoundTag();
        saved.putFloat("Master", Float.NaN);
        saved.putInt("SelectedGroup", 100);
        saved.putInt("SelectedColor", -10);
        ListTag groups = new ListTag();
        groups.add(group);
        saved.put("Groups", groups);
        LightingSettings settings = new LightingSettings();
        settings.load(saved);
        LightingSettings.Group restored = settings.groups[0];
        assertEquals(0.8f, settings.master);
        assertEquals(5, settings.selectedGroup);
        assertEquals(0, settings.selectedColor);
        assertEquals(1, restored.colorCount);
        assertEquals(3, restored.mapping);
        assertEquals(0, restored.colorMode);
        assertEquals(3, restored.motion);
        assertEquals(0, restored.pattern);
        assertEquals(3, restored.audioBand);
        assertEquals(0x123456, restored.colors[0]);
        assertEquals(0xFFFFFF, restored.colors[1]);
        assertEquals(1, restored.brightness);
        assertEquals(32, restored.range);
        assertEquals(12, restored.spread);
        assertEquals(0.05f, restored.speed);
        assertEquals(180, restored.pan);
        assertEquals(-85, restored.tilt);
    }

    @Test
    void shiftingAllRangesPreservesTheirOffsetsAndOtherSettings() {
        LightingSettings settings = new LightingSettings();
        settings.selectedGroup = 3;
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            settings.groups[i].range = 32 + i * 10;
            settings.groups[i].brightness = 0.5f;
        }
        settings.shiftRange(10);
        settings.shiftRange(1);
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            assertEquals(43 + i * 10, settings.groups[i].range);
            assertEquals(0.5f, settings.groups[i].brightness);
        }
        settings.shiftRange(-10);
        settings.shiftRange(-1);
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            assertEquals(32 + i * 10, settings.groups[i].range);
        }
        assertEquals(3, settings.selectedGroup);
    }

    @Test
    void shiftingAllRangesClampsEachGroupAtBothEnds() {
        LightingSettings settings = new LightingSettings();
        settings.groups[0].range = 295;
        settings.groups[1].range = 300;
        settings.shiftRange(10);
        assertEquals(300, settings.groups[0].range);
        assertEquals(300, settings.groups[1].range);
        assertEquals(42, settings.groups[2].range);
        settings.groups[0].range = 2;
        settings.groups[1].range = 5;
        settings.shiftRange(-10);
        assertEquals(2, settings.groups[0].range);
        assertEquals(2, settings.groups[1].range);
        assertEquals(32, settings.groups[2].range);
    }

    @Test
    void extendedRangeSurvivesSavingAndPresetRecallWithoutEscapingTheLimit() {
        LightingSettings.Group group = new LightingSettings.Group();
        LightingSettings.Group restored = new LightingSettings.Group();
        for (int range : new int[]{64, 150, 300}) {
            group.range = range;
            restored.load(group.save());
            assertEquals(range, restored.range);
        }
        group.range = 301;
        restored.load(group.save());
        assertEquals(300, restored.range);
        group.range = -1;
        restored.load(group.save());
        assertEquals(2, restored.range);
    }

    @Test
    void savedPresetIsIndependentOfLaterEditsAndOfItsLoadedCopies() {
        LightingSettings.Group original = colors(0x123456, 0xABCDEF);
        original.motion = 3;
        original.audio = true;
        original.target = UUID.randomUUID();
        CompoundTag preset = original.save();
        original.colors[0] = 0xFF0000;
        original.motion = 0;
        original.audio = false;
        LightingSettings.Group recalled = new LightingSettings.Group();
        recalled.load(preset);
        assertEquals(0x123456, recalled.colors[0]);
        assertEquals(3, recalled.motion);
        assertTrue(recalled.audio);
        assertEquals(original.target, recalled.target);
        recalled.colors[1] = 0;
        recalled.range = 64;
        LightingSettings.Group secondRecall = new LightingSettings.Group();
        secondRecall.load(preset);
        assertEquals(0xABCDEF, secondRecall.colors[1]);
        assertEquals(32, secondRecall.range);
        assertEquals(0xABCDEF, original.colors[1]);
    }
}
