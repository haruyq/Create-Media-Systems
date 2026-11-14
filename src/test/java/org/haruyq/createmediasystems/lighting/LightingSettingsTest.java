package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.haruyq.createmediasystems.lighting.LightingSettings.ColorMode;
import org.haruyq.createmediasystems.lighting.LightingSettings.Mapping;
import org.haruyq.createmediasystems.lighting.LightingSettings.Motion;
import org.haruyq.createmediasystems.lighting.LightingSettings.Pattern;
import org.haruyq.createmediasystems.lighting.LightingSettings.Preset;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
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
        group.preset(Preset.SHEET, player);
        assertEquals(Pattern.LASER_SHEET, group.pattern);
        assertTrue(group.audio);
        assertTrue(group.colorCycle);
        group.colorMode = ColorMode.VIDEO;
        group.preset(Preset.TRACK, player);
        assertEquals(Motion.TRACK, group.motion);
        assertEquals(Pattern.SPOT, group.pattern);
        assertEquals(30, group.spread);
        assertEquals(player, group.target);
        assertFalse(group.audio);
        assertFalse(group.colorCycle);
        assertEquals(ColorMode.OWN, group.colorMode);
        assertArrayEquals(new int[]{0x123456, 0xABCDEF}, java.util.Arrays.copyOf(group.colors, 2));
        assertEquals(48, group.range);
        assertEquals(0.65f, group.brightness);
        assertEquals(25, group.pan);
        assertEquals(-30, group.tilt);
        group.preset(Preset.FIXED, player);
        assertEquals(Motion.FIXED, group.motion);
        assertEquals(Pattern.SPOT, group.pattern);
    }

    @Test
    void savedPresetSurvivesApplyingAndEditingBasicEffects() {
        LightingSettings.Group source = colors(0x123456, 0xABCDEF);
        source.motion = Motion.CIRCLE;
        source.speed = 2.5f;
        source.colorMode = ColorMode.VIDEO;
        CompoundTag saved = source.save();
        source.preset(Preset.SWEEP, UUID.randomUUID());
        source.colors[0] = 0;
        LightingSettings.Group target = new LightingSettings.Group();
        target.load(saved);
        assertEquals(0x123456, target.colors[0]);
        assertEquals(Motion.CIRCLE, target.motion);
        assertEquals(2.5f, target.speed);
        assertEquals(ColorMode.VIDEO, target.colorMode);
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
        group.mapping = Mapping.SPLIT;
        assertArrayEquals(new int[]{0x2040FF, 0x2040FF, 0x2040FF, 0xA040FF, 0xA040FF, 0xFF3080, 0xFF3080}, row(group, 7));
        group.colorCount = 1;
        for (int color : row(group, 7)) {
            assertEquals(0x2040FF, color);
        }
    }

    @Test
    void gradientReachesBothEndsAndInterpolatesAcrossEveryRegisteredColor() {
        LightingSettings.Group group = colors(0xFF0000, 0x0000FF);
        group.mapping = Mapping.GRADIENT;
        assertArrayEquals(new int[]{0xFF0000, 0x800080, 0x0000FF}, row(group, 3));
        assertArrayEquals(new int[]{0xFF0000}, row(group, 1));
        group = colors(0xFF0000, 0x00FF00, 0x0000FF);
        group.mapping = Mapping.GRADIENT;
        assertArrayEquals(new int[]{0xFF0000, 0x808000, 0x00FF00, 0x008080, 0x0000FF}, row(group, 5));
    }

    @Test
    void everySupportedColorCountCanMapSparseAndFullFixtureGroups() {
        for (int colors = 1; colors <= LightingSettings.COLORS; colors++) {
            LightingSettings.Group group = new LightingSettings.Group();
            group.colorCount = colors;
            for (Mapping mapping : new Mapping[]{Mapping.SEQUENCE, Mapping.SPLIT, Mapping.GRADIENT}) {
                group.mapping = mapping;
                for (int fixtures : new int[]{1, 2, 7, 64}) {
                    for (int color : row(group, fixtures)) {
                        assertEquals(0, color & 0xFF000000);
                    }
                    if (mapping == Mapping.GRADIENT && fixtures > 1) {
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
        group.colorMode = ColorMode.VIDEO;
        assertEquals(0xFF0000, LightingSettings.color(group, 0, 4, 0, 0xFF0000, 0x0000FF, true));
        assertEquals(0x0000FF, LightingSettings.color(group, 1, 4, 0, 0xFF0000, 0x0000FF, true));
        assertEquals(0x789ABC, LightingSettings.color(group, 1, 4, 0, 0xFF0000, 0x0000FF, false));
        group.mapping = Mapping.GRADIENT;
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
        float[] floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1000, 1000};
        int[] ints = {Integer.MIN_VALUE, -10, 100, Integer.MAX_VALUE, -1};
        for (int round = 0; round < floats.length; round++) {
            LightingSettings settings = new LightingSettings();
            settings.load(corrupt(new LightingSettings().save(), floats[round], ints[round]));
            assertWithin(LightingSettings.MASTER, settings.master);
            assertTrue(settings.selectedGroup >= 0 && settings.selectedGroup < LightingSettings.GROUPS);
            assertTrue(settings.selectedColor >= 0 && settings.selectedColor < LightingSettings.COLORS);
            for (LightingSettings.Group group : settings.groups) {
                assertTrue(group.colorCount >= 1 && group.colorCount <= LightingSettings.COLORS, "colour count " + group.colorCount);
                for (int color : group.colors) {
                    assertEquals(0, color & 0xFF000000, "no alpha in a colour");
                }
                assertNotNull(group.mapping);
                assertNotNull(group.colorMode);
                assertNotNull(group.motion);
                assertNotNull(group.pattern);
                assertNotNull(group.audioBand);
                assertWithin(LightingSettings.BRIGHTNESS, group.brightness);
                assertWithin(LightingSettings.RANGE, group.range);
                assertWithin(LightingSettings.SPREAD, group.spread);
                assertWithin(LightingSettings.SPEED, group.speed);
                assertWithin(LightingSettings.PAN, group.pan);
                assertWithin(LightingSettings.TILT, group.tilt);
            }
        }
    }

    private static void assertWithin(LightingSettings.Setting setting, float value) {
        assertTrue(value >= setting.min() && value <= setting.max(), setting.key() + " " + value);
    }

    /** {@code tag} with every number in it {@code bad} ({@code badInt} if a whole number), and its colours too. */
    private static CompoundTag corrupt(CompoundTag tag, float bad, int badInt) {
        for (String key : tag.getAllKeys()) {
            switch (tag.get(key)) {
                case FloatTag ignored -> tag.putFloat(key, bad);
                case IntTag ignored -> tag.putInt(key, badInt);
                case IntArrayTag ignored -> tag.putIntArray(key, new int[] {badInt, -1, 0xFF123456});
                case CompoundTag compound -> corrupt(compound, bad, badInt);
                case ListTag list -> list.forEach(each -> {
                    if (each instanceof CompoundTag compound) {
                        corrupt(compound, bad, badInt);
                    }
                });
                default -> {
                }
            }
        }
        return tag;
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
    void randomLightsAreSavedBoundedAndTurnedOffByABuiltInPreset() {
        LightingSettings.Group group = new LightingSettings.Group();
        group.chase = 3;
        LightingSettings.Group restored = new LightingSettings.Group();
        restored.load(group.save());
        assertEquals(3, restored.chase);
        group.chase = 99;
        group.normalize();
        assertEquals(16, group.chase);
        group.preset(LightingSettings.Preset.SWEEP, null);
        assertEquals(0, group.chase);
    }

    @Test
    void aGroupAimedAtRandomLightsARunOfNeighboursAndPointsSomewhereNewOnEachBeat() {
        java.util.Set<Integer> firsts = new java.util.HashSet<>();
        java.util.Set<String> aims = new java.util.HashSet<>();
        for (long beat = 0; beat < 60; beat++) {
            // Three of eight, side by side, somewhere along the row.
            int first = -1;
            int lit = 0;
            for (int order = 0; order < 8; order++) {
                if (LightingSettings.chaseLit(3, order, 8, beat, true)) {
                    first = first < 0 ? order : first;
                    lit++;
                    assertTrue(order - first < 3, "beat " + beat + " lights " + order + " apart from " + first);
                }
            }
            assertEquals(3, lit, "beat " + beat);
            firsts.add(first);
            // Aimed within a sweep's reach across and a circle's up and down, the same for the whole beat.
            float[] aim = LightingSettings.randomAim(beat);
            assertTrue(Math.abs(aim[0]) <= 55 && Math.abs(aim[1]) <= 22, aim[0] + " / " + aim[1]);
            assertArrayEquals(aim, LightingSettings.randomAim(beat));
            aims.add(Math.round(aim[0] / 10) + "/" + Math.round(aim[1] / 10));
        }
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 5), firsts, "the run starts anywhere it fits");
        assertTrue(aims.size() > 15, "and points many ways: " + aims);
        // As many as there are, or none asked for: all lit, as ever.
        assertTrue(LightingSettings.chaseLit(8, 7, 8, 1, true) && LightingSettings.chaseLit(0, 7, 8, 1, true));
        // The motion is kept with the group, and a mod reads it by its name.
        LightingSettings.Group group = new LightingSettings.Group();
        group.motion = Motion.RANDOM;
        LightingSettings.Group restored = new LightingSettings.Group();
        restored.load(group.save());
        assertEquals(Motion.RANDOM, restored.motion);
        assertEquals(org.haruyq.createmediasystems.api.lighting.GroupView.Motion.RANDOM, restored.motion());
    }

    @Test
    void aBeatLightsAsManyAtRandomAndAnotherBeatOthers() {
        java.util.Set<String> picks = new java.util.HashSet<>();
        for (long beat = 0; beat < 50; beat++) {
            for (int chase : new int[]{1, 3, 5}) {
                StringBuilder lit = new StringBuilder();
                for (int order = 0; order < 8; order++) {
                    if (LightingSettings.chaseLit(chase, order, 8, beat, false)) {
                        lit.append(order);
                    }
                }
                assertEquals(chase, lit.length(), "beat " + beat);
                picks.add(lit.toString());
            }
        }
        assertTrue(picks.size() > 20, picks.size() + " different picks");
        // As many or fewer lights than it lights: all of them; and none chosen: all of them.
        assertTrue(LightingSettings.chaseLit(3, 1, 2, 7, false));
        assertTrue(LightingSettings.chaseLit(0, 5, 8, 7, false));
    }

    @Test
    void aStrobeIsSavedBoundedAndTurnedOffByABuiltInPreset() {
        LightingSettings.Group group = new LightingSettings.Group();
        group.strobe = 6;
        LightingSettings.Group restored = new LightingSettings.Group();
        restored.load(group.save());
        assertEquals(6, restored.strobe);
        // A group saved before strobes had one: a steady light.
        CompoundTag old = group.save();
        old.remove("Strobe");
        restored.load(old);
        assertEquals(0, restored.strobe);
        group.strobe = 99;
        group.normalize();
        assertEquals(LightingSettings.STROBE.max(), group.strobe);
        group.preset(Preset.SWEEP, null);
        assertEquals(0, group.strobe);
    }

    @Test
    void aStrobingLightIsLitForTheStartOfEachFlash() {
        assertTrue(LightingSettings.strobeLit(123.7, 0));
        // Five flashes a second: lit for the first 0.08 s of each 0.2 s.
        assertTrue(LightingSettings.strobeLit(2.0, 5));
        assertTrue(LightingSettings.strobeLit(2.05, 5));
        assertFalse(LightingSettings.strobeLit(2.1, 5));
        assertFalse(LightingSettings.strobeLit(2.19, 5));
        assertTrue(LightingSettings.strobeLit(2.21, 5));
    }

    @Test
    void fixturesDrawThePatternsTheyCanAndTheirOwnForTheRest() {
        // Saved and sent as ordinals: the wash comes after the lasers.
        assertEquals(5, Pattern.WASH.ordinal());
        assertEquals(Pattern.WASH, StageLightBlock.Spec.LIGHT.pattern(Pattern.WASH));
        assertEquals(Pattern.SPOT, StageLightBlock.Spec.LIGHT.pattern(Pattern.SPOT));
        // As before the wash: a light among lasers draws a beam, a laser among lights a ray.
        assertEquals(Pattern.BEAM, StageLightBlock.Spec.LIGHT.pattern(Pattern.LASER_SHEET));
        assertEquals(Pattern.LASER_RAY, StageLightBlock.Spec.LASER.pattern(Pattern.SPOT));
        assertEquals(Pattern.LASER_RAY, StageLightBlock.Spec.LASER.pattern(Pattern.WASH));
        for (Pattern pattern : Pattern.values()) {
            assertEquals(Pattern.WASH, StageLightBlock.Spec.STRIP.pattern(pattern));
            // Every pattern has its name in the API, as GroupView.pattern gives it.
            LightingSettings.Group group = new LightingSettings.Group();
            group.pattern = pattern;
            assertEquals(pattern.name(), group.pattern().name());
        }
        assertEquals(40, Pattern.WASH.coneSpread(12));
        assertEquals(100, Pattern.WASH.coneSpread(70));
        assertEquals(2, Pattern.BEAM.coneSpread(12));
        assertEquals(12, Pattern.SPOT.coneSpread(12));
    }

    @Test
    void savedPresetIsIndependentOfLaterEditsAndOfItsLoadedCopies() {
        LightingSettings.Group original = colors(0x123456, 0xABCDEF);
        original.motion = Motion.TRACK;
        original.audio = true;
        original.target = UUID.randomUUID();
        CompoundTag preset = original.save();
        original.colors[0] = 0xFF0000;
        original.motion = Motion.FIXED;
        original.audio = false;
        LightingSettings.Group recalled = new LightingSettings.Group();
        recalled.load(preset);
        assertEquals(0x123456, recalled.colors[0]);
        assertEquals(Motion.TRACK, recalled.motion);
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
