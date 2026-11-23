package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.PadMode;

import org.junit.jupiter.api.Test;

class ControlMidiTest {
    /** What a read told, as text. */
    private static final class Told implements ControlMidi.Sink {
        final List<String> all = new ArrayList<>();

        @Override
        public void set(int slot, int value) {
            all.add("set " + slot + " " + value);
        }

        @Override
        public void turn(int slot, int steps) {
            all.add("turn " + slot + " " + steps);
        }

        @Override
        public void mode(int deck, PadMode mode) {
            all.add("mode " + deck + " " + mode);
        }
    }

    @Test
    void aDeckIsToldItsTempoWhilePlayingItsMovementWhileScratchedAndNothingNewOverAJump() {
        assertEquals(0, DjState.rate(false, 1, 1, 1));
        assertEquals(0, DjState.rate(true, .01, 1, 1));
        assertEquals(1.08f, DjState.rate(true, 1.2, 1.08f, 0));
        assertEquals(-2.28f, DjState.rate(true, -2.28, 1, 1));
        assertEquals(-4, DjState.rate(true, -6, 1, 1));
        // A load, a cue or a beat jump, as read from rekordbox with a real DDJ-FLX4: -2105, -146, -20 a second.
        assertEquals(1, DjState.rate(true, -20.25, 1, 1));
        assertEquals(0, DjState.rate(true, -2105, 1, 0));
    }

    @Test
    void aMappingForALoopbackPortIsNamedForItAndSendsNothingOut() {
        String own = "@file,1,DDJ-FLX4,,,,,,,,,,,,\r\n"
                + "#name,function,type,input,deck1,deck2,deck3,deck4,output,deck1,deck2,deck3,deck4,option,comment\r\n"
                + "A,A,Button,900B,0,1,,,900B,0,1,,,Fast,press and lamp\r\n"
                + "B,B,Indicator,,,,,,9002,0,1,,,RO,lamp alone\r\n"
                + "C,C,KnobSliderHiRes,B61F,,,,,,,,,,Fast,no lamp\r\n";
        assertEquals("@file,1,loopMIDI Port,,,,,,,,,,,,\r\n"
                + "#name,function,type,input,deck1,deck2,deck3,deck4,output,deck1,deck2,deck3,deck4,option,comment\r\n"
                + "A,A,Button,900B,0,1,,,,,,,,Fast,press and lamp\r\n"
                + "C,C,KnobSliderHiRes,B61F,,,,,,,,,,Fast,no lamp\r\n", ControlMidi.mapping(own, "loopMIDI Port"));
    }

    @Test
    void theTempoFaderIsFullestAtItsNearEnd() {
        DjState state = new DjState();
        state.set(Control.TEMPO.slot(0), Control.FULL);
        state.set(Control.CHANNEL_FADER.slot(0), Control.FULL);
        assertEquals(0, state.place(Control.TEMPO, 0));
        assertEquals(1, state.place(Control.CHANNEL_FADER, 0));
    }

    private static int[] written(java.util.function.Consumer<java.util.function.IntConsumer> write) {
        List<Integer> out = new ArrayList<>();
        write.accept(out::add);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int message(int status, int first, int second) {
        return status | first << 8 | second << 16;
    }

    /** The numbers rekordbox's own DDJ-FLX4 mapping lists. */
    @Test
    void writesWhatTheControllerSends() {
        assertArrayEquals(new int[]{message(0x91, 0x0B, 0x7F)},
                written(out -> ControlMidi.press(Control.PLAY.slot(1), 1, false, PadMode.HOT_CUE, 0, out)));
        assertArrayEquals(new int[]{message(0x90, 0x0E, 0)},
                written(out -> ControlMidi.press(Control.PLAY.slot(0), 0, true, PadMode.HOT_CUE, 0, out)));
        assertArrayEquals(new int[]{message(0x96, 0x47, 0x7F)},
                written(out -> ControlMidi.press(Control.LOAD.slot(1), 1, false, PadMode.HOT_CUE, 0, out)));
        assertArrayEquals(new int[]{message(0x96, 0x7A, 0x7F)},
                written(out -> ControlMidi.press(Control.LOAD.slot(1), 1, true, PadMode.HOT_CUE, 0, out)));
        // Deck 2's third pad in beat jump, and with SHIFT.
        assertArrayEquals(new int[]{message(0x99, 0x22, 0x7F)},
                written(out -> ControlMidi.press(Control.PAD_3.slot(1), 1, false, PadMode.BEAT_JUMP, 0, out)));
        assertArrayEquals(new int[]{message(0x98, 0x02, 0x7F)},
                written(out -> ControlMidi.press(Control.PAD_3.slot(0), 1, true, PadMode.HOT_CUE, 0, out)));
        assertArrayEquals(new int[]{message(0x95, 0x47, 0x7F)},
                written(out -> ControlMidi.press(Control.FX_ON.slot(0), 1, false, PadMode.HOT_CUE, 1, out)));
        assertArrayEquals(new int[]{message(0xB6, 0x1F, 0x40), message(0xB6, 0x3F, 0x01)},
                written(out -> ControlMidi.move(Control.CROSSFADER.slot(0), 0x2001, false, 0, out)));
        assertArrayEquals(new int[]{message(0xB6, 0x18, 0x7F), message(0xB6, 0x38, 0x7F)},
                written(out -> ControlMidi.move(Control.CFX.slot(1), Control.FULL, false, 0, out)));
        assertArrayEquals(new int[]{message(0xB1, 0x22, 0x43)},
                written(out -> ControlMidi.turn(Control.JOG.slot(1), 3, false, out)));
        assertArrayEquals(new int[]{message(0xB6, 0x40, 0x7F)},
                written(out -> ControlMidi.turn(Control.BROWSE.slot(0), -1, false, out)));
    }

    /** Whatever is written for a control reads back as that control doing the same. */
    @Test
    void readsBackWhatItWrites() {
        for (Control control : Control.values()) {
            for (int deck = 0; deck < control.count(); deck++) {
                for (boolean shifted : new boolean[]{false, true}) {
                    int slot = control.slot(deck);
                    ControlMidi midi = new ControlMidi();
                    Told told = new Told();
                    String wanted;
                    int[] sent;
                    if (control.kind.continuous()) {
                        sent = written(out -> ControlMidi.move(slot, 0x1234, shifted, 1, out));
                        wanted = "set " + slot + " " + 0x1234;
                    } else if (control.kind == Control.Kind.SWITCH) {
                        sent = written(out -> ControlMidi.press(slot, 2, shifted, PadMode.SAMPLER, 1, out));
                        wanted = "set " + slot + " 2";
                    } else {
                        sent = written(out -> ControlMidi.press(slot, 1, shifted, PadMode.SAMPLER, 1, out));
                        wanted = "set " + slot + " 1";
                    }
                    if (control == Control.MIC_LEVEL) {
                        assertEquals(0, sent.length);
                        continue;
                    }
                    assertTrue(sent.length > 0, control + " writes nothing");
                    for (int message : sent) {
                        midi.read(message, told);
                    }
                    assertTrue(told.all.contains(wanted), control + (shifted ? " shifted" : "") + " read back as " + told.all);
                    if (control.kind == Control.Kind.PAD) {
                        assertTrue(told.all.contains("mode " + deck + " SAMPLER"), told.all.toString());
                    }
                }
            }
        }
        Told told = new Told();
        ControlMidi midi = new ControlMidi();
        for (int message : written(out -> ControlMidi.turn(Control.JOG.slot(1), -200, true, out))) {
            midi.read(message, told);
        }
        for (int message : written(out -> ControlMidi.turn(Control.BROWSE.slot(0), 2, false, out))) {
            midi.read(message, told);
        }
        int jog = Control.JOG.slot(1), browse = Control.BROWSE.slot(0);
        assertEquals(List.of("turn " + jog + " -63", "turn " + jog + " -63", "turn " + jog + " -63", "turn " + jog + " -11",
                "turn " + browse + " 2"), told.all);
    }

    @Test
    void stateSurvivesItsBytes() {
        DjState state = new DjState();
        state.set(Control.SHIFT.slot(1), 1);
        state.set(Control.MODE_BEAT_JUMP.slot(1), 1);
        state.set(Control.CHANNEL_CUE.slot(1), 1);
        state.set(Control.TEMPO.slot(0), 1000);
        state.track[1] = -77;
        state.position[1] = 12.5f;
        state.rate[1] = 1.06f;
        DjState read = new DjState(state.bytes());
        assertEquals(PadMode.BEAT_LOOP, read.padMode[1]);
        assertEquals(PadMode.HOT_CUE, read.padMode[0]);
        assertTrue(read.lamp(Control.CHANNEL_CUE, 1));
        assertTrue(!read.lamp(Control.CHANNEL_CUE, 0) && !read.lamp(Control.SYNC, 1));
        assertEquals(1000, read.value(Control.TEMPO, 0));
        assertEquals(-77, read.track[1]);
        assertEquals(12.5f, read.position[1]);
        assertArrayEquals(state.bytes(), read.bytes());
        assertTrue(state.bytes().length <= DjState.MAX_BYTES);
        // A state cut short keeps what it has.
        assertEquals(1000, new DjState(java.util.Arrays.copyOf(state.bytes(), 100)).value(Control.TEMPO, 0));
    }

    @Test
    void trackSurvivesItsPartsInAnyOrder() {
        short[] wave = new short[60_000];
        for (int i = 0; i < wave.length; i++) {
            wave[i] = (short) (i * 31);
        }
        DjState.Track track = new DjState.Track(5, "題名", "Artist", "Am", 12800, 172, new int[]{10, 1 << 30 | 479}, wave,
                new int[]{-1, 2000, -1, -1, -1, -1, -1, -1});
        List<DdjFlx4Block.TrackPart> parts = new ArrayList<>();
        DdjFlx4Block.TrackPart.split(net.minecraft.core.BlockPos.ZERO, 1, 5, track.bytes(), parts::add);
        assertTrue(parts.size() > 1);
        DdjFlx4Block.Arriving arriving = new DdjFlx4Block.Arriving();
        byte[] whole = null;
        for (int i = parts.size() - 1; i >= 0; i--) {
            assertNull(whole);
            assertTrue(parts.get(i).valid());
            whole = arriving.take(parts.get(i));
        }
        assertNotNull(whole);
        DjState.Track read = DjState.Track.of(whole);
        assertEquals("題名", read.title());
        assertArrayEquals(wave, read.wave());
        assertEquals(479, DjState.Track.millis(read.beats()[1]));
        assertEquals(1, DjState.Track.inBar(read.beats()[1]));
        assertEquals(2000, read.cues()[1]);
        assertNull(DjState.Track.of(java.util.Arrays.copyOf(whole, 30)));
    }

    /** Every control lies inside the controller, and is the one found where it lies. */
    @Test
    void controlsAreFoundWhereTheyLie() {
        for (Control control : Control.values()) {
            for (int deck = 0; deck < control.count(); deck++) {
                float x = control.x(deck);
                assertTrue(x - control.width / 2 >= Control.LEFT && x + control.width / 2 <= Control.RIGHT
                        && control.y - control.depth / 2 >= Control.BACK && control.y + control.depth / 2 <= Control.FRONT,
                        control + " lies off the controller");
                assertEquals(control.slot(deck), Control.at(x, control.y), control + " is under another control");
            }
        }
    }
}
