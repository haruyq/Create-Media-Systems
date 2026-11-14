package org.haruyq.createmediasystems.blocks.piano.client;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteTrailsTest {
    @Test void manyPlayersKeepTheirColorsAndLanesWhenTheyShareAKey() {
        var trails = new NoteTrails(now -> now);
        var colors = new HashSet<Integer>();
        long start = 10_000_000_000L;
        for (int player = 1; player <= 64; player++) trails.press(player, player, 60, 100, start);
        var visible = trails.visible(start + 150_000_000);
        assertEquals(64, visible.size());
        for (int i = 0; i < visible.size(); i++) {
            var trail = visible.get(i);
            colors.add(trail.color());
            assertEquals(i, trail.lane());
            assertEquals(64, trail.lanes());
            assertTrue(trail.head() > 0 && trail.tail() == 0);
        }
        assertEquals(64, colors.size());
        trails.release(3, start + 200_000_000);
        assertEquals(63, trails.visible(start + 250_000_000).stream().filter(trail -> trail.tail() == 0).count());
    }

    @Test void shortNotesRemainVisibleThenLeaveFromTheTop() {
        var trails = new NoteTrails(now -> now);
        long start = 3_000_000_000L;
        trails.press(1, 1, 60, 22, start);
        trails.release(1, 60, start + 10_000_000);
        var shortNote = trails.visible(start + 100_000_000).getFirst();
        assertTrue(shortNote.velocity() < .3f && shortNote.head() > shortNote.tail() && shortNote.tail() > 0);
        assertTrue(trails.visible(start + NoteTrails.TRAVEL + 100_000_000).isEmpty());
    }

    @Test void changingSpeedKeepsCurrentNotesInPlaceAndChangesHowFastTheyMove() {
        var clock = new NoteTrails.Clock(0);
        int[] speed = {100};
        var trails = new NoteTrails(now -> clock.at(now, speed[0]));
        trails.press(1, 1, 60, 100, 0);
        trails.release(1, 60, 100_000_000);
        long change = 250_000_000;
        var before = trails.visible(change).getFirst();
        speed[0] = 200;
        assertEquals(before, trails.visible(change).getFirst());
        var faster = trails.visible(change + 100_000_000).getFirst();
        assertEquals(200_000_000f / NoteTrails.TRAVEL, faster.head() - before.head(), 1e-6f);
        assertEquals(200_000_000f / NoteTrails.TRAVEL, faster.tail() - before.tail(), 1e-6f);
        assertTrue(trails.visible(change + NoteTrails.TRAVEL / 2).isEmpty());
    }
}
