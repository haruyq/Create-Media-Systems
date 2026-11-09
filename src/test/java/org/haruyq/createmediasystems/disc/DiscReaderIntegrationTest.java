package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class DiscReaderIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "CMS_DISC_SHORT_FIXTURE", matches = ".+")
    void followsTrimmedClipsAcrossAPlaylistBoundary(@TempDir Path folder) throws Exception {
        Path original = Path.of(System.getenv("CMS_DISC_SHORT_FIXTURE"));
        byte[] playlist = Files.readAllBytes(original.resolve("BDMV/PLAYLIST/00000.mpls"));
        ByteBuffer data = ByteBuffer.wrap(playlist);
        int start = data.getInt(8), size = Short.toUnsignedInt(data.getShort(start + 10));
        int length = 6 + 2 * (2 + size);
        ByteBuffer changed = ByteBuffer.allocate(44 + length);
        changed.put(playlist, 0, 40).putInt(8, 40);
        changed.position(40).putInt(length).putShort((short) 0).putShort((short) 2).putShort((short) 0);
        long in = Integer.toUnsignedLong(data.getInt(start + 24));
        for (double[] clip : new double[][]{{1, 5.2}, {10.4, 16.8}}) {
            int at = changed.position();
            changed.put(playlist, start + 10, size + 2);
            changed.putInt(at + 14, (int) (in + Math.round(clip[0] * 45000)));
            changed.putInt(at + 18, (int) (in + Math.round(clip[1] * 45000)));
        }
        Files.createDirectories(folder.resolve("BDMV/PLAYLIST"));
        Files.createDirectories(folder.resolve("BDMV/STREAM"));
        Files.write(folder.resolve("BDMV/index.bdmv"), new byte[0]);
        Files.write(folder.resolve("BDMV/PLAYLIST/00000.mpls"), changed.array());
        Files.copy(original.resolve("BDMV/STREAM/00000.m2ts"), folder.resolve("BDMV/STREAM/00000.m2ts"));
        DiscSource source = DiscSource.open("ffmpeg", "ffprobe", folder, -1);
        assertEquals(10.6, source.duration(), 0.0001);
        byte[] segment = source.encode(1, new DiscSource.Quality(480, 30, 1100), source.encoder());
        byte[] actual = DiscSource.run(List.of("ffmpeg", "-v", "error", "-f", "mpegts", "-i", "pipe:0", "-ss", "0.5",
                "-frames:v", "1", "-vf", "scale=160:90,format=gray", "-f", "rawvideo", "pipe:1"), segment, 160 * 90, 10);
        Files.write(Path.of("build", "disc-boundary.gray"), actual);
        Files.write(Path.of("build", "disc-boundary.ts"), segment);
        double expected = difference(actual, frame(original, 10.7)), wrong = difference(actual, frame(original, 5.5));
        assertTrue(expected < wrong / 3, "After the clip boundary, playback uses the next clip's in-point: " + expected + "/" + wrong);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CMS_DISC_SHORT_FIXTURE", matches = ".+")
    void stopsAnEncoderThatDoesNotFinish() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(6), () ->
                assertThrows(IOException.class, () -> DiscSource.run(List.of("ffmpeg", "-v", "error", "-nostdin",
                        "-re", "-f", "lavfi", "-i", "color=s=32x32", "-f", "null", "-"), 1024, 1)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CMS_DISC_SHORT_FIXTURE", matches = ".+")
    void readsABlurayShorterThanThreeMinutes() throws Exception {
        DiscSource source = DiscSource.open("ffmpeg", "ffprobe", Path.of(System.getenv("CMS_DISC_SHORT_FIXTURE")), -1);
        assertTrue(source.bluray());
        assertTrue(source.duration() > 30 && source.duration() < 33);
        byte[] segment = source.encode(3, new DiscSource.Quality(480, 30, 1100), source.encoder());
        assertTrue(segment.length > 10000);
        assertEquals(0x47, segment[0] & 255);
        byte[] actual = DiscSource.run(List.of("ffmpeg", "-v", "error", "-f", "mpegts", "-i", "pipe:0", "-frames:v", "1",
                "-vf", "scale=160:90,format=gray", "-f", "rawvideo", "pipe:1"), segment, 160 * 90, 10);
        byte[] expected = frame(source.root(), 12);
        byte[] beginning = frame(source.root(), 0);
        assertTrue(difference(actual, expected) < difference(actual, beginning) / 3,
                "The encoded segment starts at the requested disc position");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CMS_DISC_DVD_FIXTURE", matches = ".+")
    void readsDvdTitles() throws Exception {
        DiscSource source = DiscSource.open("ffmpeg", "ffprobe", Path.of(System.getenv("CMS_DISC_DVD_FIXTURE")), 1);
        assertFalse(source.bluray());
        assertTrue(source.duration() > 8);
        byte[] segment = source.encode(1, new DiscSource.Quality(1080, 60, 8000), source.encoder());
        assertTrue(segment.length > 10000);
        assertEquals(0x47, segment[0] & 255);
    }

    private static byte[] frame(Path root, double seconds) throws Exception {
        return DiscSource.run(List.of("ffmpeg", "-v", "error", "-i", root.resolve("BDMV/STREAM/00000.m2ts").toString(),
                "-ss", Double.toString(seconds), "-frames:v", "1",
                "-vf", "scale=160:90,format=gray", "-f", "rawvideo", "pipe:1"), 160 * 90, 10);
    }

    private static double difference(byte[] a, byte[] b) {
        assertEquals(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.pow((a[i] & 255) - (b[i] & 255), 2);
        return sum / a.length;
    }
}
