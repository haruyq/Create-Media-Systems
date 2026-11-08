package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.haruyq.createmediasystems.stream.Streams;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class LightingMediaTest {
    private static final long START = 10_000_000;

    private static byte[] tone(double hz, double amplitude, double seconds, boolean opposite) {
        int count = (int) (Pcm.RATE * seconds);
        byte[] stereo = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            short value = (short) Math.round(32767 * amplitude * Math.sin(2 * Math.PI * hz * i / Pcm.RATE));
            short right = opposite ? (short) -value : value;
            stereo[i * 4] = (byte) value;
            stereo[i * 4 + 1] = (byte) (value >> 8);
            stereo[i * 4 + 2] = (byte) right;
            stereo[i * 4 + 3] = (byte) (right >> 8);
        }
        return stereo;
    }

    private static LightingMedia.AudioFrame analyze(double hz) {
        LightingMedia.Audio audio = new LightingMedia.Audio();
        byte[] stereo = tone(hz, 0.3, 1, false);
        byte[] original = stereo.clone();
        audio.offer(stereo, stereo.length / 4, START);
        assertArrayEquals(original, stereo);
        return audio.sample(START + 1_000_000);
    }

    @Test
    void separatesLowMidAndHighWithoutChangingTheAudio() {
        LightingMedia.AudioFrame low = analyze(80);
        assertTrue(low.bass() > low.mid() * 2);
        assertTrue(low.bass() > low.high() * 2);
        LightingMedia.AudioFrame mid = analyze(1000);
        assertTrue(mid.mid() > mid.bass() * 2);
        assertTrue(mid.mid() > mid.high() * 2);
        LightingMedia.AudioFrame high = analyze(10_000);
        assertTrue(high.high() > high.bass() * 2);
        assertTrue(high.high() > high.mid() * 2);
    }

    @Test
    void oppositeStereoPhasesStillHaveEnergy() {
        LightingMedia.Audio audio = new LightingMedia.Audio();
        byte[] stereo = tone(80, 0.3, 1, true);
        audio.offer(stereo, stereo.length / 4, START);
        assertEquals(analyze(80).bass(), audio.sample(START + 1_000_000).bass(), 0.001);
    }

    @Test
    void monoRelayAnalysisMatchesStereoWithoutChangingSamples() {
        byte[] stereo = tone(80, 0.3, 1, false);
        byte[] mono = new byte[stereo.length / 2];
        for (int i = 0; i < mono.length / 2; i++) {
            mono[i * 2] = stereo[i * 4];
            mono[i * 2 + 1] = stereo[i * 4 + 1];
        }
        byte[] original = mono.clone();
        LightingMedia.Audio audio = new LightingMedia.Audio();
        audio.offerMono(mono, START);
        assertArrayEquals(original, mono);
        assertEquals(analyze(80).bass(), audio.sample(START + 1_000_000).bass(), 0.001);
    }

    @Test
    void relaySamplesOnlyAudiblePcmWithoutConsumingTheSharedFeed() {
        byte[] stereo = tone(80, 0.3, 1, false);
        byte[] mono = new byte[stereo.length / 2];
        for (int i = 0; i < mono.length / 2; i++) {
            mono[i * 2] = stereo[i * 4];
            mono[i * 2 + 1] = stereo[i * 4 + 1];
        }
        Feed.Reader feed = Feed.read(UUID.randomUUID().toString(), 0,
                offset -> new ByteArrayInputStream(mono), reason -> fail(reason));
        StreamSound.PcmStream stream = new StreamSound.PcmStream(feed);
        try {
            feed.prebuffer(mono.length, System.currentTimeMillis() + 5000);
            long at = START * 1000;
            stream.queued(at, 0, mono.length);
            assertNull(stream.lighting(at - 1));
            assertTrue(stream.lighting(at + 500_000_000).bass() > 0.4);
            assertEquals(0, feed.position());
            assertArrayEquals(mono, feed.peek(0, mono.length));
            assertArrayEquals(mono, feed.read(mono.length));
            stream.queued(at + 1_000_000_000, -1, Pcm.BYTES_PER_SECOND / 4);
            assertEquals(0, stream.lighting(at + 1_100_000_000).level());
            assertNull(stream.lighting(at + 1_500_000_000));
        } finally {
            stream.close();
        }
    }

    @Test
    void relaySourceBecomesUnavailableWhenItsLastEmitterCloses() {
        byte[] mono = new byte[Pcm.BYTES_PER_SECOND];
        Feed.Reader feed = Feed.read(UUID.randomUUID().toString(), 0,
                offset -> new ByteArrayInputStream(mono), reason -> fail(reason));
        BlockPos source = new BlockPos(12, 34, 56);
        StreamSound.PcmStream stream = new StreamSound.PcmStream(feed, source, Streams.Channel.MIX);
        try {
            feed.prebuffer(mono.length, System.currentTimeMillis() + 5000);
            stream.queued(System.nanoTime() - 500_000_000L, 0, mono.length);
            assertNotNull(LightingMedia.relay(source, null));
        } finally {
            stream.close();
        }
        assertNull(LightingMedia.relay(source, null));
    }

    @Test
    void waitsForPresentationTimeAndExpiresOrClearsOldFeatures() {
        LightingMedia.Audio audio = new LightingMedia.Audio();
        byte[] stereo = tone(80, 0.4, 0.1, false);
        audio.offer(stereo, stereo.length / 4, START);
        assertNull(audio.sample(START - 1));
        assertNull(audio.sample(START + 19_999));
        assertTrue(audio.sample(START + 20_000).pulse() > 0.9);
        assertNull(audio.sample(START + 400_000));
        audio.clear();
        assertNull(audio.sample(START + 20_000));
        audio.offer(stereo, stereo.length / 4, START + 5_000_000);
        assertNull(audio.sample(START + 200_000));
        assertNotNull(audio.sample(START + 5_020_000));
    }

    @Test
    void followsRepeatedBassHitsAndLetsTheirPulseDecay() {
        LightingMedia.Audio audio = new LightingMedia.Audio();
        byte[] hit = tone(80, 0.5, 0.08, false);
        byte[] silence = new byte[(int) (Pcm.RATE * 0.42) * 4];
        for (int i = 0; i < 8; i++) {
            long at = START + i * 500_000L;
            audio.offer(hit, hit.length / 4, at);
            audio.offer(silence, silence.length / 4, at + 80_000);
            LightingMedia.AudioFrame onset = audio.sample(at + 20_000);
            LightingMedia.AudioFrame rest = audio.sample(at + 480_000);
            assertTrue(onset.pulse() > 0.8);
            assertTrue(rest.pulse() < 0.2);
            assertEquals(120, rest.bpm(), 3);
        }
    }

    @Test
    void takesIndependentSideColorsWithoutBlackBarsOrMinorWhiteDetails() {
        int width = 96;
        int height = 64;
        int[] colors = LightingMedia.colors(width, height, index -> {
            int x = index % width;
            int y = index / width;
            if (y < 16 || y >= 48) {
                return 0;
            }
            if (y < 20) {
                return 0xffffff;
            }
            return x < width / 2 ? 0xf02010 : 0x1030f0;
        });
        assertArrayEquals(new int[]{0xf02010, 0x1030f0}, colors);
        assertArrayEquals(new int[]{0, 0}, LightingMedia.colors(width, height, index -> 0));
        assertEquals(0x800080, LightingMedia.blend(0xff0000, 0x0000ff, 0.5f));
    }
}
