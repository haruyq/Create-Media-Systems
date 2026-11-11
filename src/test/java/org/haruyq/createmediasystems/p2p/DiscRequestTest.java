package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.disc.DiscSource;
import org.haruyq.createmediasystems.disc.DiscStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class DiscRequestTest {
    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    void replacesAViewersQueuedReadsWhileTheEncoderIsBusy() throws Exception {
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        UUID sourceId = UUID.randomUUID();
        CountDownLatch blocked = new CountDownLatch(1);
        DiscSource source = new DiscSource("unused", Path.of("."), false, 1, 300, null);
        try (Transport transport = new Transport(Protocol.Ticket.parse(P2pTest.ticket("host", P2pTest.HOST, "")),
                message -> {}, message -> {}, () -> {});
             DiscStream stream = new DiscStream(transport, sourceId, source, message -> {})) {
            ThreadPoolExecutor encoder = (ThreadPoolExecutor) field(stream, "encoder");
            encoder.execute(() -> {
                blocked.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException closed) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int request = 0; request < 100; request++) {
                stream.control(P2pTest.VIEWER, ByteBuffer.allocate(32).putInt(0x434d4401)
                        .putLong(sourceId.getMostSignificantBits()).putLong(sourceId.getLeastSignificantBits())
                        .putInt(1).putInt(request).putInt(request % 20 + 20).array());
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Set<?> queued = Set.of();
            do {
                synchronized (stream) {
                    queued = Set.copyOf(((Map<?, ?>) field(stream, "segments")).keySet());
                }
                if (queued.equals(Set.of(39))) break;
                Thread.sleep(10);
            } while (System.nanoTime() < until);
            assertEquals(Set.of(39), queued, "superseded requests must not seek the DVD back after the new request");
            assertEquals(1, encoder.getQueue().size());
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
