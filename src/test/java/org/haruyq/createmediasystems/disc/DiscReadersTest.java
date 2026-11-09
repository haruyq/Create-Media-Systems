package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscReadersTest {
    @Test
    void usesExtensionsBeforeInstallingDefaultTools(@TempDir Path root) throws Exception {
        AtomicInteger closed = new AtomicInteger();
        DiscSource source = new DiscSource("test", root, true, 0, 32, null, new DiscSource.Access() {
            @Override
            public void close() {
                closed.incrementAndGet();
            }
        });
        DiscReaders.register((path, title, downloading) -> path.equals(root) ? source : null);
        assertSame(source, DiscReaders.open(root, "ffmpeg", "ffprobe", root, 0, () -> fail("Default tools must not be installed")));
        source.close();
        assertEquals(1, closed.get());
    }

    @Test
    void releasesExtensionResourcesWhenOpeningFails(@TempDir Path root) {
        AtomicInteger closed = new AtomicInteger();
        assertThrows(IOException.class, () -> DiscSource.open("ffmpeg", "ffprobe", root, 0, new DiscSource.Access() {
            @Override
            public void close() {
                closed.incrementAndGet();
            }
        }));
        assertEquals(1, closed.get());
    }
}
