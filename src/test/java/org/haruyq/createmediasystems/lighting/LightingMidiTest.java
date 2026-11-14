package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.lighting.LightingMidi.Binding;
import org.haruyq.createmediasystems.lighting.LightingMidi.Profile;
import org.haruyq.createmediasystems.lighting.LightingPackets.Change;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.haruyq.createmediasystems.lighting.LightingPackets.PresetAction;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class LightingMidiTest {
    private static final List<Profile> KNOWN = LightingMidi.profiles(List.of());
    private final LightingSettings settings = new LightingSettings();

    private Change change(Control control, int index, int value) {
        return LightingMidi.change(BlockPos.ZERO, settings, false, new Binding(control, index), value);
    }

    @Test
    void theApcMiniWorksTheConsoleByWhatQlcPlusListsItSending() {
        // QLC+ numbers a control change by its number and a note 128 above its own, as these maps are keyed.
        Map<Integer, Binding> apc = LightingMidi.profile(KNOWN, "APC MINI|AKAI|USB MIDI|0").map();
        assertEquals(new Binding(Control.BRIGHTNESS, 0), apc.get(48));
        assertEquals(new Binding(Control.BRIGHTNESS, 5), apc.get(53));
        assertEquals(new Binding(Control.MASTER, 0), apc.get(56));
        assertEquals(new Binding(Control.COLOR, 0), apc.get(128));
        assertEquals(new Binding(Control.COLOR, 63), apc.get(191));
        assertEquals(new Binding(Control.GROUP, 0), apc.get(192));
        assertEquals(new Binding(Control.ENABLED, 0), apc.get(217));
        // The mk2's keys, on the same map.
        assertEquals(apc.get(192), LightingMidi.profile(KNOWN, "APC mini mk2").map().get(228));
        assertEquals(apc.get(217), apc.get(247));
        assertNull(apc.get(226), "Shift works nothing");
    }

    @Test
    void theNanoKontrol2sKeysAreControlChanges() {
        Map<Integer, Binding> nano = LightingMidi.profile(KNOWN, "nanoKONTROL2 1 SLIDER/KNOB").map();
        assertEquals(new Binding(Control.BRIGHTNESS, 5), nano.get(5));
        assertEquals(new Binding(Control.MASTER, 0), nano.get(7));
        assertEquals(new Binding(Control.RGB, 2), nano.get(18));
        assertEquals(new Binding(Control.AIM, 1), nano.get(23));
        assertEquals(new Binding(Control.SLOT, 7), nano.get(55));
        assertEquals(new Binding(Control.CYCLE, 0), nano.get(46));
    }

    @Test
    void theDdjFlx4sDecksSendTheSameNumbersOnTheirOwnChannelsAsMixxxListsThem() {
        Profile flx4 = LightingMidi.profile(KNOWN, "DDJ-FLX4");
        // The channel faders: 0xB0 and 0xB1, 0x13.
        assertEquals(new Binding(Control.BRIGHTNESS, LightingMidi.SELECTED), flx4.binding(1, false, 0x13));
        assertEquals(new Binding(Control.MASTER, 0), flx4.binding(2, false, 0x13));
        assertNull(flx4.binding(1, false, 0x33), "a fader's fine half works nothing");
        assertEquals(new Binding(Control.RGB, 0), flx4.binding(1, false, 0x07));
        assertEquals(new Binding(Control.RANGE, 0), flx4.binding(2, false, 0x07));
        assertEquals(new Binding(Control.AIM, 1), flx4.binding(7, false, 0x18));
        // The pads: 0x97 and 0x99, with Shift 0x98 and 0x9A.
        assertEquals(new Binding(Control.GROUP, 5), flx4.binding(8, true, 5));
        assertEquals(new Binding(Control.PRESET, 5), flx4.binding(10, true, 5));
        assertEquals(new Binding(Control.SLOT, 7), flx4.binding(10, true, 0x37));
        assertNull(flx4.binding(9, true, 0));
        assertNull(flx4.binding(8, true, 6));
        assertEquals(new Binding(Control.ENABLED, 0), flx4.binding(1, true, 0x0B));
        assertEquals(new Binding(Control.PATTERN, 0), flx4.binding(2, true, 0x58));
    }

    @Test
    void aBindingOnOneChannelComesBeforeThatOnAny() {
        Profile own = LightingMidi.parse("x: ch2.cc7=master cc7=speed ch16.note1=audio ch17.cc8=master ch0.cc9=master"
                + " ch100.cc10=master ch3.cc1-2=selected");
        assertEquals(new Binding(Control.MASTER, 0), own.binding(2, false, 7));
        assertEquals(new Binding(Control.SPEED, 0), own.binding(1, false, 7));
        assertEquals(new Binding(Control.AUDIO, 0), own.binding(16, true, 1));
        assertNull(own.binding(15, true, 1));
        assertEquals(new Binding(Control.BRIGHTNESS, LightingMidi.SELECTED), own.binding(3, false, 2));
        assertEquals(5, own.map().size(), "a channel there isn't binds nothing, on any channel either");
    }

    @Test
    void onlyAControllerWithAMapIsKnownAndAPlayersOwnComesFirst() {
        assertNull(LightingMidi.profile(KNOWN, "A-88MKII"));
        List<Profile> own = LightingMidi.profiles(List.of("apc:", "x-touch: cc1=master"));
        assertEquals(Map.of(), LightingMidi.profile(own, "APC MINI").map());
        assertEquals(Map.of(1, new Binding(Control.MASTER, 0)), LightingMidi.profile(own, "X-TOUCH MINI").map());
        assertEquals(KNOWN.getLast(), LightingMidi.profile(own, "nanoKONTROL2"));
    }

    @Test
    void anEntryBindsRangesInTurnFromTheNumberGivenAndLeavesOutWhatItCant() {
        Map<Integer, Binding> map = LightingMidi.parse("My Desk: CC1-8=brightness, cc20=brightness3 note5=preset2 cc9-11=red"
                + " cc30=master2 cc31=sources cc32=chase cc200=master note6=nothing nonsense").map();
        assertEquals(Map.ofEntries(Map.entry(1, new Binding(Control.BRIGHTNESS, 0)), Map.entry(2, new Binding(Control.BRIGHTNESS, 1)),
                Map.entry(3, new Binding(Control.BRIGHTNESS, 2)), Map.entry(4, new Binding(Control.BRIGHTNESS, 3)),
                Map.entry(5, new Binding(Control.BRIGHTNESS, 4)), Map.entry(6, new Binding(Control.BRIGHTNESS, 5)),
                Map.entry(20, new Binding(Control.BRIGHTNESS, 2)), Map.entry(133, new Binding(Control.PRESET, 1)),
                Map.entry(9, new Binding(Control.RGB, 0)), Map.entry(10, new Binding(Control.RGB, 1)),
                Map.entry(11, new Binding(Control.RGB, 2))), map);
        assertEquals("my desk", LightingMidi.parse("My Desk: cc1=master").device());
    }

    @Test
    void faderAndKnobTravelIsTheSettingsWithAimLevelAtTheMiddle() {
        settings.selectedGroup = 2;
        settings.selectedColor = 3;
        settings.selected().tilt = 20;
        assertEquals(new Change(BlockPos.ZERO, 4, Control.BRIGHTNESS, 0, 1, 0), change(Control.BRIGHTNESS, 4, 127));
        assertEquals(new Change(BlockPos.ZERO, 2, Control.BRIGHTNESS, 0, 1, 0), change(Control.BRIGHTNESS, LightingMidi.SELECTED, 127));
        assertEquals(new Change(BlockPos.ZERO, 2, Control.MASTER, 0, 0, 0), change(Control.MASTER, 0, 0));
        assertEquals(LightingSettings.RANGE.max(), change(Control.RANGE, 0, 127).value());
        assertEquals(LightingSettings.SPEED.min(), change(Control.SPEED, 0, 0).value());
        assertEquals(new Change(BlockPos.ZERO, 2, Control.RGB, 3 * 3 + 1, 255, 0), change(Control.RGB, 1, 127));
        assertEquals(new Change(BlockPos.ZERO, 2, Control.AIM, 0, 0, 20), change(Control.AIM, 0, 64));
        assertEquals(new Change(BlockPos.ZERO, 2, Control.AIM, 0, 0, -85), change(Control.AIM, 1, 0));
        assertEquals(180, change(Control.AIM, 0, 127).value());
    }

    @Test
    void aKeyDoesWhatARightClickOnItsControlDoes() {
        settings.selectedColor = 1;
        assertEquals(new Change(BlockPos.ZERO, 4, Control.GROUP, 0, 4, 0), change(Control.GROUP, 4, 127));
        assertEquals(new Change(BlockPos.ZERO, 0, Control.SLOT, 0, 6, 0), change(Control.SLOT, 6, 127));
        // The pads are numbered from the bottom left, the palette's rows from the back.
        assertEquals(new Change(BlockPos.ZERO, 0, Control.COLOR, 1, LightingSettings.palette(0, 7), 0),
                change(Control.COLOR, 0, 127));
        assertEquals(LightingSettings.palette(7, 0), change(Control.COLOR, 63, 127).value());
        assertEquals(new Change(BlockPos.ZERO, 0, Control.PRESET, 3, PresetAction.FACTORY.ordinal(), 0),
                change(Control.PRESET, 3, 127));
        assertEquals(PresetAction.LOAD.ordinal(), LightingMidi.change(BlockPos.ZERO, settings, true,
                new Binding(Control.PRESET, 3), 127).value());
        assertEquals(new Change(BlockPos.ZERO, 0, Control.ENABLED, 0, 0, 0), change(Control.ENABLED, 0, 127));
        assertEquals(new Change(BlockPos.ZERO, 0, Control.STROBE, 0, 1, 0), change(Control.STROBE, 0, 127));
    }
}
