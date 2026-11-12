package org.haruyq.createmediasystems.keyboard;

import static org.haruyq.createmediasystems.keyboard.Controller.CONTROL;
import static org.haruyq.createmediasystems.keyboard.Controller.KEY;
import static org.haruyq.createmediasystems.keyboard.Controller.NOTE_OFF;
import static org.haruyq.createmediasystems.keyboard.Controller.NOTE_ON;
import static org.haruyq.createmediasystems.keyboard.Controller.PAD;
import static org.haruyq.createmediasystems.keyboard.Controller.PROGRAM;
import static org.haruyq.createmediasystems.keyboard.Controller.message;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.haruyq.createmediasystems.keyboard.Layout.Control;

import org.junit.jupiter.api.Test;

class ControllerTest {
    /** What a controller sent, without the keys and pads themselves unless asked for. */
    private static final class Sent implements Controller.Out {
        final List<Integer> all = Collections.synchronizedList(new ArrayList<>());
        int panelChanges;

        @Override
        public void send(int message) {
            all.add(message);
        }

        @Override
        public void panelChanged() {
            panelChanges++;
        }

        /** The MIDI sent since last asked. */
        List<Integer> midi() {
            synchronized (all) {
                List<Integer> midi = all.stream().filter(message -> (message & 0x80) != 0).toList();
                all.clear();
                return midi;
            }
        }
    }

    private final Sent sent = new Sent();
    private final Panel panel = new Panel();
    private final Controller controller = new Controller(panel, sent);

    @Test
    void aKeyPlaysItsZoneAndTellsThoseWhoWatch() {
        controller.key(60, 100);
        assertEquals(List.of(message(KEY, 0, 60, 100), message(NOTE_ON, 0, 60, 100)), List.copyOf(sent.all));
        sent.all.clear();
        controller.key(60, 0);
        assertEquals(List.of(message(KEY, 0, 60, 0), message(NOTE_OFF, 0, 60, 0)), List.copyOf(sent.all));
    }

    @Test
    void zonesThatAreOnPlayLayeredAndASplitDividesThem() {
        controller.press(Control.LOWER);
        controller.key(60, 100);
        assertEquals(List.of(message(NOTE_ON, 2, 60, 100), message(NOTE_ON, 0, 60, 100)), sent.midi());
        controller.press(Control.SPLIT);
        // Changing what the keys play lets the held ones go first.
        assertEquals(List.of(message(NOTE_OFF, 2, 60, 0), message(NOTE_OFF, 0, 60, 0)), sent.midi());
        controller.key(54, 100);
        controller.key(55, 100);
        assertEquals(List.of(message(NOTE_ON, 2, 54, 100), message(NOTE_ON, 0, 55, 100)), sent.midi());
    }

    @Test
    void aZoneButtonTurnsOnThenMarksThenTurnsOff() {
        controller.press(Control.UPPER_2);
        assertEquals(0b110, panel.zones);
        assertEquals(Panel.UPPER_2, panel.target);
        controller.press(Control.UPPER_1);
        assertEquals(0b110, panel.zones);
        assertEquals(Panel.UPPER_1, panel.target);
        controller.press(Control.UPPER_1);
        assertEquals(0b100, panel.zones);
        assertEquals(Panel.UPPER_2, panel.target);
        // The last zone stays on.
        controller.press(Control.UPPER_2);
        assertEquals(0b100, panel.zones);
    }

    @Test
    void theNextKeyAfterShiftSplitIsTheSplitPoint() {
        controller.press(Control.SHIFT);
        controller.press(Control.SPLIT);
        assertTrue(controller.lit(Control.SPLIT));
        assertFalse(panel.split);
        controller.key(48, 100);
        assertTrue(panel.split);
        assertEquals(48, panel.splitPoint);
        assertEquals(List.of(), sent.midi());
    }

    @Test
    void octaveTransposeAndVelocityCurveShiftWhatAKeySends() {
        controller.press(Control.OCTAVE_UP);
        controller.press(Control.TRANSPOSE);
        controller.press(Control.OCTAVE_DOWN);
        controller.press(Control.TRANSPOSE);
        assertEquals(1, panel.octave);
        assertEquals(-1, panel.transpose);
        controller.key(60, 64);
        assertEquals(List.of(message(NOTE_ON, 0, 71, 64)), sent.midi());
        controller.key(60, 0);
        sent.midi();
        controller.press(Control.SHIFT);
        controller.press(Control.OCTAVE_UP);
        assertEquals(3, panel.curve);
        controller.key(60, 64);
        int velocity = sent.midi().get(0) >> 16 & 127;
        assertTrue(velocity < 64 && velocity > 30, "a heavier touch: " + velocity);
        for (int i = 0; i < 20; i++) {
            controller.press(Control.OCTAVE_UP);
        }
        assertEquals(Panel.CURVES - 1, panel.curve);
    }

    @Test
    void padsPickProgramsSwitchControllersAndPlayDrums() {
        controller.pad(3, 110);
        controller.pad(3, 0);
        assertEquals(List.of(message(PROGRAM, 0, 3, 0)), sent.midi());
        assertEquals(3, panel.program[Panel.UPPER_1]);
        controller.press(Control.BANK_UP);
        controller.pad(0, 110);
        assertEquals(List.of(message(PROGRAM, 0, 8, 0)), sent.midi());
        controller.press(Control.BANK_DOWN);
        controller.press(Control.BANK_DOWN);
        assertEquals(15, panel.bank());

        controller.press(Control.CC);
        controller.pad(0, 110);
        controller.pad(0, 0);
        controller.pad(0, 110);
        assertEquals(List.of(message(CONTROL, 0, 64, 127), message(CONTROL, 0, 64, 0)), sent.midi());

        controller.press(Control.NOTE);
        sent.all.clear();
        controller.pad(1, 90);
        controller.pad(1, 0);
        assertEquals(List.of(message(PAD, 0, 1, 90), message(NOTE_ON, 9, 38, 90), message(PAD, 0, 1, 0),
                message(NOTE_OFF, 9, 38, 0)), List.copyOf(sent.all));
    }

    @Test
    void aKnobSendsItsControllerToTheMarkedZone() {
        controller.press(Control.LOWER);
        sent.midi();
        controller.knob(4, 90);
        controller.knob(4, 90);
        assertEquals(List.of(message(CONTROL, 2, 91, 90)), sent.midi());
        assertEquals(90, panel.knob[4]);
    }

    @Test
    void theLeverAndPedalsGoToEveryZoneThatSoundsOncePerChannel() {
        controller.press(Control.UPPER_2);
        panel.channel[Panel.UPPER_2] = 0;
        controller.bend(16383);
        controller.control(64, 127);
        assertEquals(List.of(message(Controller.BEND, 0, 127, 127), message(CONTROL, 0, 64, 127)), sent.midi());
    }

    @Test
    void funcAndAKeyWorkTheFunctionPrintedAboveIt() {
        controller.key(60, 100);
        sent.midi();
        controller.press(Control.FUNC);
        controller.key(Layout.LOWEST + Controller.PANIC_KEY, 100);
        List<Integer> panic = sent.midi();
        assertEquals(message(NOTE_OFF, 0, 60, 0), panic.get(0));
        assertEquals(1 + 16 * 3, panic.size());
        assertFalse(controller.lit(Control.FUNC));

        controller.press(Control.FUNC);
        controller.key(Layout.LOWEST + Controller.FIRST_CHANNEL_KEY + 9, 100);
        assertEquals(9, panel.channel[Panel.UPPER_1]);
        controller.press(Control.FUNC);
        controller.key(Layout.LOWEST + Controller.FIRST_STEP_KEY + 2, 100);
        controller.press(Control.FUNC);
        controller.key(Layout.LOWEST + Controller.FIRST_GATE_KEY, 100);
        assertEquals(2, panel.step);
        assertEquals(0, panel.gate);
    }

    @Test
    void aSnapshotIsStoredWithShiftAndRecalledWithout() {
        controller.pad(5, 110);
        controller.knob(7, 30);
        controller.press(Control.SHIFT);
        controller.press(Control.SNAPSHOT);
        assertEquals(Controller.WRITE, controller.snapshotMode());
        controller.pad(2, 110);
        controller.press(Control.NEXT);
        assertEquals(0, controller.snapshotMode());

        controller.pad(0, 110);
        controller.knob(7, 127);
        controller.press(Control.OCTAVE_UP);
        sent.midi();
        controller.press(Control.SNAPSHOT);
        controller.pad(2, 110);
        controller.press(Control.NEXT);
        assertEquals(5, panel.program[Panel.UPPER_1]);
        assertEquals(30, panel.knob[7]);
        assertEquals(0, panel.octave);
        List<Integer> restored = sent.midi();
        assertTrue(restored.contains(message(PROGRAM, 0, 5, 0)));
        assertTrue(restored.contains(message(CONTROL, 0, 7, 30)));

        // PREV leaves without recalling; an empty pad recalls nothing.
        controller.press(Control.SNAPSHOT);
        controller.pad(6, 110);
        controller.press(Control.NEXT);
        assertEquals(5, panel.program[Panel.UPPER_1]);
    }

    @Test
    void theArpeggiatorRunsOverTheKeysHeldInItsOrder() throws InterruptedException {
        panel.tempo = Panel.MAX_TEMPO;
        panel.step = 5;
        controller.press(Control.SHIFT);
        controller.press(Control.UPPER_1);
        assertTrue(panel.arpeggio);
        controller.key(64, 100);
        controller.key(60, 100);
        controller.key(67, 100);
        Thread.sleep(400);
        controller.key(64, 0);
        controller.key(60, 0);
        controller.key(67, 0);
        Thread.sleep(100);
        List<Integer> played = sent.midi();
        List<Integer> pitches = played.stream().filter(message -> (message & 0xF0) == NOTE_ON).map(message -> message >> 8 & 127).toList();
        assertTrue(pitches.size() >= 6, "steps: " + pitches);
        for (int i = 2; i < pitches.size(); i++) {
            assertEquals(new int[]{60, 64, 67}[(i - 2 + List.of(60, 64, 67).indexOf(pitches.get(2))) % 3], pitches.get(i), "at " + i + " of " + pitches);
        }
        // Every note it started it ended, and it stopped once the keys were let go.
        assertEquals(pitches.size(), played.stream().filter(message -> (message & 0xF0) == NOTE_OFF).count());
        Thread.sleep(150);
        assertEquals(List.of(), sent.midi());
    }

    @Test
    void aPanelSurvivesBeingSavedAndWhatIsNoPanelGivesANewOne() {
        controller.press(Control.LOWER);
        controller.press(Control.SPLIT);
        controller.press(Control.CC);
        controller.pad(4, 100);
        controller.knob(2, 99);
        controller.press(Control.SHIFT);
        controller.press(Control.SNAPSHOT);
        controller.pad(7, 100);
        controller.press(Control.NEXT);
        panel.tempo = 200;
        Panel read = new Panel(panel.bytes());
        assertArrayEquals(panel.bytes(), read.bytes());
        assertEquals(0b011, read.zones);
        assertTrue(read.split);
        assertEquals(1 << 4, read.latched[0]);
        assertEquals(99, read.knob[2]);
        assertEquals(200, read.tempo);
        assertArrayEquals(panel.snapshot(), read.snapshots[7]);

        assertArrayEquals(new Panel().bytes(), new Panel(new byte[3]).bytes());
        byte[] junk = new byte[400];
        java.util.Arrays.fill(junk, (byte) 0x7F);
        Panel clamped = new Panel(junk);
        assertEquals(Panel.MAX_OCTAVE, clamped.octave);
        assertEquals(4, clamped.range);
        assertArrayEquals(clamped.bytes(), new Panel(clamped.bytes()).bytes());
    }

    @Test
    void theKeysAreAPianosAndEveryControlHasItsOwnPlace() {
        int black = 0;
        for (int note = Layout.LOWEST; note <= Layout.HIGHEST; note++) {
            black += Layout.black(note) ? 1 : 0;
            float middle = Layout.left(note) + Layout.width(note) / 2;
            assertEquals(note, Layout.keyAt(middle, 0.3f), "key " + note);
            if (!Layout.black(note)) {
                assertEquals(note, Layout.keyAt(middle, 0.9f), "front of key " + note);
            }
        }
        assertEquals(Layout.KEYS - Layout.WHITE_KEYS, black);
        assertEquals(Layout.WHITE_KEYS - 1, Layout.across(Layout.HIGHEST));
        assertEquals(-1, Layout.keyAt(Layout.WHITE_KEYS, 0.5f));
        for (Control control : Control.values()) {
            assertTrue(control.left >= 0 && control.right <= 1 && control.top >= 0 && control.bottom <= 1, control.name());
            assertEquals(control, Control.at(control.centerX(), control.centerY()));
            for (Control other : Control.values()) {
                assertTrue(control == other || control.right < other.left || other.right < control.left
                        || control.bottom < other.top || other.bottom < control.top, control + " over " + other);
            }
        }
    }
}
