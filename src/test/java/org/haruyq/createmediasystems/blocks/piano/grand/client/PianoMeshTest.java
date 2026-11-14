package org.haruyq.createmediasystems.blocks.piano.grand.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;
import org.haruyq.createmediasystems.client.mesh.RecordedMesh;
import org.junit.jupiter.api.Test;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.FIRST_NOTE;
import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.LAST_NOTE;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PianoMeshTest {
    @Test void playingAscendingNotesMovesKeysAndHammersFromLeftToRight() {
        double previousKey = Double.NEGATIVE_INFINITY;
        double previousHammer = Double.NEGATIVE_INFINITY;
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            var action = new PianoAction();
            var state = new PianoState();
            var press = PianoEvent.press(note, 127);
            state.apply(press);
            action.accept(press, 1_000_000_000L);
            action.advance(state, 1_016_100_000L);

            var mesh = new RecordedMesh();
            new PianoMesh(new PoseStack(), layer -> mesh, 0xF000F0).draw(action, 43, 96);

            // From the player's side (-Z), screen X increases as model X decreases.
            double key = mesh.vertices.stream()
                    .filter(v -> v[2] > .13f && v[2] < .26f && v[4] > .99f && v[5] < -.02f)
                    .mapToDouble(v -> -v[0]).average().orElseThrow();
            double hammer = mesh.vertices.stream()
                    .filter(v -> v[2] > .883f && v[2] < .893f && v[1] > .87f && v[4] > .999f)
                    .mapToDouble(v -> -v[0]).average().orElseThrow();
            assertTrue(key > previousKey, "Wrong key position for MIDI note " + note);
            assertTrue(hammer > previousHammer, "Wrong hammer position for MIDI note " + note);
            previousKey = key;
            previousHammer = hammer;
        }
    }
}
