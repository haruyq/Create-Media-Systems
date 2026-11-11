package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedDecoderTest {
    @TempDir
    Path temp;

    @Test
    void acceptsOnlyExistingLocalInputsAndCopiesOnlyTheLocalSlave() throws Exception {
        URI video = Files.createFile(temp.resolve("video.mp4")).toUri();
        URI audio = Files.createFile(temp.resolve("audio.m4a")).toUri();
        var prepared = new NativeMediaResolver.Prepared(video, false, null, 1,
                List.of(":input-slave=" + audio, ":sout=#http", ":http-referrer=https://example.com"));
        assertEquals(List.of(":input-slave=" + audio), CachedDecoder.localOptions(prepared));
        assertThrows(IllegalArgumentException.class, () -> CachedDecoder.localOptions(
                new NativeMediaResolver.Prepared(URI.create("https://example.com/video.mp4"), false)));
        assertThrows(IllegalArgumentException.class, () -> CachedDecoder.localOptions(
                new NativeMediaResolver.Prepared(video, false, null, 1, List.of(":input-slave=https://example.com/a"))));
        assertThrows(IllegalArgumentException.class, () -> CachedDecoder.localOptions(
                new NativeMediaResolver.Prepared(temp.resolve("missing.mp4").toUri(), false)));
        assertThrows(IllegalArgumentException.class, () -> CachedDecoder.localOptions(
                new NativeMediaResolver.Prepared(video, true)));
    }

    @Test
    void decodesThroughItsSpanFromTheFirstTimestamp() {
        long maxMillis = 7_200_000;
        CachedDecoder.Span span = new CachedDecoder.Span(maxMillis);
        assertFalse(span.beyond(5_000_000));
        assertFalse(span.beyond(5_000_000 + maxMillis * 1000 - 1));
        assertTrue(span.beyond(5_000_000 + maxMillis * 1000));
    }
}
