package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.util.stream.IntStream;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderPerformanceTest {
    @Test
    void bundledPieceKeepsEveryNoteInOrder() {
        var score = PonderPerformance.SCORE;
        long strikes = IntStream.of(score.messages())
                .filter(message -> (message & 0xF0) == Synth.NOTE_ON && (message >> 16 & 127) > 0).count();
        assertEquals(3282, strikes);
        for (int i = 1; i < score.frames().length; i++) {
            assertTrue(score.frames()[i - 1] <= score.frames()[i], "Out of order at " + i);
        }
        assertEquals(287, score.length() / Synth.RATE);
    }
}
