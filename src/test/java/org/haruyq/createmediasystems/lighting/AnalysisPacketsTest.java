package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import org.haruyq.createmediasystems.stream.StreamPackets;

class AnalysisPacketsTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void allAnalysisPayloadsRoundTrip() {
        AnalysisFrame frame = new AnalysisFrame(100, .2f, .3f, .4f, .5f, .6f, 120, 0x102030, 0x405060, true, true);
        AnalysisPackets.State state = new AnalysisPackets.State(SOURCE, SESSION, 3, SOURCE, new BlockPos(1, 2, 3),
                "track", "next", 720, 100, true, new AnalysisDelay(200, 800, 1_000));
        assertEquals(state, roundTrip(AnalysisPackets.State.CODEC, state));
        AnalysisPackets.Status status = new AnalysisPackets.Status(SOURCE, SESSION, true, 100, 600, -4_000_000_000L, 300);
        assertEquals(status, roundTrip(AnalysisPackets.Status.CODEC, status));
        AnalysisPackets.Clock clock = new AnalysisPackets.Clock(SOURCE, SESSION, status.clientSentNanos(), 400);
        assertEquals(clock, roundTrip(AnalysisPackets.Clock.CODEC, clock));
        AnalysisPackets.Upload upload = new AnalysisPackets.Upload(SOURCE, SESSION, 3, 4, "track", true, List.of(frame));
        assertEquals(upload, roundTrip(AnalysisPackets.Upload.CODEC, upload));
        AnalysisPackets.Batch batch = new AnalysisPackets.Batch(SOURCE, SESSION, "track", false, List.of(frame));
        assertEquals(batch, roundTrip(AnalysisPackets.Batch.CODEC, batch));
        AnalysisPackets.Accepted accepted = new AnalysisPackets.Accepted(SOURCE, SESSION, 3, 4);
        assertEquals(accepted, roundTrip(AnalysisPackets.Accepted.CODEC, accepted));
        AnalysisPackets.Received received = new AnalysisPackets.Received(SOURCE, SESSION, "track", 100);
        assertEquals(received, roundTrip(AnalysisPackets.Received.CODEC, received));
    }

    @Test
    void decoderRejectsMoreThanBatchSize() {
        ByteBuf buf = Unpooled.buffer();
        writeUuid(buf, SOURCE);
        writeUuid(buf, SESSION);
        StreamPackets.URL.encode(buf, "track");
        buf.writeBoolean(false);
        buf.writeByte(AnalysisPackets.BATCH_SIZE + 1);
        assertThrows(DecoderException.class, () -> AnalysisPackets.Batch.CODEC.decode(buf));
    }

    private static <T> T roundTrip(StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, value);
        return codec.decode(buf);
    }

    private static void writeUuid(ByteBuf buf, UUID value) {
        buf.writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());
    }
}
