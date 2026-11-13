package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;

import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderScoreTest {
    private record Attack(int frame, int note, int velocity) {}

    @Test
    void bundledMidiKeepsEveryNoteInBothHands() throws Exception {
        List<Attack> expected = new ArrayList<>();
        try (var input = PonderScoreTest.class.getResourceAsStream(
                "/assets/create_media_systems/piano/pxs1100we/un_owen_sabi_piano_only.mid")) {
            var midi = MidiSystem.getSequence(new java.io.BufferedInputStream(input));
            assertEquals(480, midi.getResolution());
            assertEquals(34_000_000, midi.getMicrosecondLength());
            for (var track : midi.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    var event = track.get(i);
                    if (event.getMessage() instanceof ShortMessage message
                            && message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
                        expected.add(new Attack(Math.toIntExact(event.getTick() * 50),
                                message.getData1(), message.getData2() * 129));
                    }
                }
            }
        }
        expected.sort(Comparator.comparingInt(Attack::frame));
        var score = PonderScore.load();
        assertEquals(680, score.duration());
        assertEquals(362, expected.size());
        var actual = score.cues().stream().flatMap(cue -> cue.events().stream()
                .filter(event -> event.kind() == PianoEvent.Kind.ON)
                .map(event -> new Attack(cue.frame(), event.number(), event.value()))).toList();
        assertEquals(expected, actual);
    }

    @Test
    void keysMoveAtTheAudioFrameOfEachStrike() {
        var demo = new PxS1100Ponder.Demo();
        var model = KeyboardLayout.PX;
        var down = new boolean[128];
        for (var cue : demo.score.cues()) {
            demo.advance(cue.frame());
            for (var event : cue.events()) {
                if (event.kind() == PianoEvent.Kind.CONTROL) continue;
                int note = event.number();
                down[note] = event.kind() == PianoEvent.Kind.ON;
                assertEquals(note, model.keys.get(note - KeyboardLayout.FIRST).note());
                if (down[note]) assertTrue(demo.travel(note) > 0, "No key travel for MIDI " + note);
            }
            for (var key : model.keys) {
                int note = key.note();
                assertEquals(down[note], demo.keys.pressed(note),
                        "Wrong key at MIDI note " + note + ", frame " + cue.frame());
            }
        }
        demo.advance((demo.score.duration() + 1) * PonderScore.FRAMES_PER_TICK);
        assertFalse(demo.movingKeys());
    }

    @Test
    void theShortestNotesAreNotRoundedToGameTicks() {
        var score = PonderScore.load();
        int[] down = new int[128];
        int eighthSeconds = 0;
        for (var cue : score.cues()) {
            for (var event : cue.events()) {
                if (event.kind() == PianoEvent.Kind.ON) down[event.number()] = cue.frame();
                if (event.kind() == PianoEvent.Kind.OFF && cue.frame() - down[event.number()] == KeyboardSynth.RATE / 8) {
                    eighthSeconds++;
                }
            }
        }
        assertEquals(16, eighthSeconds);
    }
}
