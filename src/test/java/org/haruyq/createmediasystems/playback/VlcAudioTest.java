package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.haruyq.createmediasystems.stream.Streams;

import org.junit.jupiter.api.Test;

import com.sun.jna.Memory;

class VlcAudioTest {
    private static final int RATE = 48_000;
    private static final long START = 10_000_000;
    private static final int BLOCK = 240;

    private final Random wobble = new Random(1);
    private int fed;

    @Test
    void callbacksKeepStandbyEligibleWithoutAudioTapsOrLightingDemand() {
        VlcPlayer player = new VlcPlayer(null, 0, 0);
        assertFalse(player.recentMedia(System.nanoTime()));
        try (Memory sample = new Memory(4)) {
            player.audio.play.invoke(null, sample, 1, START);
            long last = player.audio.lastSamples;
            assertTrue(player.recentMedia(last));
            assertTrue(player.recentMedia(last + 1_999_999_999L));
            assertFalse(player.recentMedia(last + 2_000_000_000L));
            player.audio.flush.invoke(null, START);
            assertFalse(player.recentMedia(last));
            player.audio.play.invoke(null, sample, 0, START);
            assertEquals(0, player.audio.lastSamples);
            player.audio.play.invoke(null, sample, 1, START);
            player.release();
            player.audio.play.invoke(null, sample, 1, START);
            assertFalse(player.recentMedia(player.audio.lastSamples));
        }
    }

    /** VLC's blocks up to {@code until} (µs); sample n's left value is n + 1, each block timed a hair off. */
    private void feedUntil(VlcAudio audio, long until) {
        while (START + (long) fed * 1_000_000 / RATE < until) {
            byte[] stereo = new byte[BLOCK * 4];
            for (int i = 0; i < BLOCK; i++) {
                short value = (short) ((fed + i) % 30_000 + 1);
                stereo[i * 4] = (byte) value;
                stereo[i * 4 + 1] = (byte) (value >> 8);
                stereo[i * 4 + 2] = (byte) -value;
                stereo[i * 4 + 3] = (byte) (-value >> 8);
            }
            long pts = START + (long) fed * 1_000_000 / RATE + wobble.nextInt(601) - 300;
            audio.offer(stereo, BLOCK, pts);
            fed += BLOCK;
        }
    }

    /** What {@code stream} plays from {@code at} on, by sample number since START (silence left out). */
    private static void read(VlcAudio.Stream stream, long at, Map<Long, Integer> heard) {
        ByteBuffer out = ByteBuffer.allocateDirect(RATE * 2 / 10);
        stream.fill(out, at, true);
        out.position(0);
        out.order(ByteOrder.LITTLE_ENDIAN);
        long first = Math.round((at - START) * (double) RATE / 1_000_000);
        for (int i = 0; i < out.capacity() / 2; i++) {
            int value = out.getShort(i * 2);
            if (value != 0) {
                heard.put(first + i, value);
            }
        }
    }

    @Test
    void streamsPlayEachSampleTogetherWhateverHappenedToThemBefore() {
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream a = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        VlcAudio.Stream b = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        Map<Long, Integer> heardA = new HashMap<>();
        Map<Long, Integer> heardB = new HashMap<>();
        for (int k = 0; k < 40; k++) {
            long atA = START + k * 100_000L;
            long atB = atA + 37_000; // the engine reads each stream at its own moment
            // Once, VLC is a moment behind: b's read runs 5 ms past what it has, and pads that with silence.
            feedUntil(audio, k == 15 ? atB + 95_000 : atA + 300_000);
            read(a, atA, heardA);
            read(b, atB, heardB);
        }
        int compared = 0;
        for (var sample : heardB.entrySet()) {
            Integer other = heardA.get(sample.getKey());
            if (other != null && sample.getKey() > 17 * RATE / 10) { // after b ran short
                assertTrue(Math.abs(other - sample.getValue()) <= 1,
                        "sample " + sample.getKey() + ": a plays " + other + ", b plays " + sample.getValue());
                compared++;
            }
        }
        assertTrue(compared > RATE, "the streams overlapped for over a second after b ran short");
    }

    @Test
    void timestampsAHairOffStillPlayAsOneRun() {
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream stream = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        Map<Long, Integer> heard = new HashMap<>();
        feedUntil(audio, START + 1_000_000);
        for (int k = 0; k < 5; k++) {
            read(stream, START + k * 100_000L, heard);
        }
        for (long n = 1; n < 5 * RATE / 10; n++) {
            assertEquals(heard.get(n - 1) + 1, heard.get(n), "no sample skipped or repeated at " + n);
        }
    }

    @Test
    void clockDriftNeverPlaysASilentSample() {
        clockDrift(100_005); // OpenAL's clock 50 ppm fast against VLC's: a sample is skipped now and then
        clockDrift(99_995); // 50 ppm slow: a sample is held now and then
    }

    private void clockDrift(long readEvery) {
        fed = 0;
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream stream = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        feedUntil(audio, START + 5_000_000);
        int previous = 0;
        for (int k = 0; k < 40; k++) {
            ByteBuffer out = ByteBuffer.allocateDirect(RATE * 2 / 10);
            stream.fill(out, START + k * readEvery, true);
            out.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < out.capacity() / 2; i++) {
                int value = out.getShort(i * 2);
                assertNotEquals(0, value, "silence at read " + k + ", sample " + i);
                if (previous != 0) {
                    int step = Math.floorMod(value - previous, 30_000); // the fed values wrap at 30 000
                    assertTrue(step <= 2, "a jump from " + previous + " to " + value);
                }
                previous = value;
            }
        }
    }

    @Test
    void eightSpeakersRebuildTheirBuffersAtTheCurrentVideoTimeAfterABriefPause() {
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        List<VlcAudio.Stream> streams = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            streams.add((VlcAudio.Stream) audio.open(i < 4 ? Streams.Channel.LEFT : Streams.Channel.RIGHT));
        }
        feedUntil(audio, START + 1_000_000);
        for (int i = 0; i < streams.size(); i++) {
            VlcAudio.Stream stream = streams.get(i);
            for (int k = 0; k < 4; k++) {
                read(stream, START + k * 100_000L + i * 3_000L, new HashMap<>());
            }
        }
        // Fresh taps give the reference audio; the existing taps have already handed these samples to OpenAL.
        fed = 0;
        wobble.setSeed(1);
        VlcAudio reference = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream left = (VlcAudio.Stream) reference.open(Streams.Channel.LEFT);
        VlcAudio.Stream right = (VlcAudio.Stream) reference.open(Streams.Channel.RIGHT);
        feedUntil(reference, START + 1_000_000);
        Map<Long, Integer> expectedLeft = new HashMap<>();
        Map<Long, Integer> expectedRight = new HashMap<>();
        read(left, START + 250_000, expectedLeft);
        read(right, START + 250_000, expectedRight);
        assertEquals(RATE / 10, expectedLeft.size());
        assertEquals(RATE / 10, expectedRight.size());
        for (int i = 0; i < streams.size(); i++) {
            VlcAudio.Stream stream = streams.get(i);
            stream.attach(i + 1, 1_000_000_000L);
            Map<Long, Integer> heard = new HashMap<>();
            read(stream, START + 250_000, heard);
            assertEquals(i < 4 ? expectedLeft : expectedRight, heard, "speaker " + i);
        }
    }

    @Test
    void flushingAlsoDiscardsAudioKeptForResuming() {
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream stream = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        feedUntil(audio, START + 500_000);
        read(stream, START, new HashMap<>());
        stream.clear();
        stream.attach(1, 1_000_000_000L);
        Map<Long, Integer> heard = new HashMap<>();
        read(stream, START, heard);
        assertTrue(heard.isEmpty());
        assertTrue(VlcAudio.drained(stream));
    }

    @Test
    void aTapIsDrainedOnlyOnceItHasNothingQueued() {
        VlcAudio audio = new VlcAudio(new MediaTaps(() -> 0));
        VlcAudio.Stream tap = (VlcAudio.Stream) audio.open(Streams.Channel.LEFT);
        assertTrue(VlcAudio.drained(tap));
        tap.offer(new byte[] {1, 0, 1, 0}, 0);
        assertFalse(VlcAudio.drained(tap));
        tap.clear();
        assertTrue(VlcAudio.drained(tap));
    }
}
