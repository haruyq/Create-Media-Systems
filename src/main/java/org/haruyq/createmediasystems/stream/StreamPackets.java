package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.playback.ClientStreams;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** What a block's stream says between server and client (see Streams and ClientStreams). */
public final class StreamPackets {
    /** A disc's, image viewer's or announcement's link as sent: at most {@link UrlPolicy#MAX_LENGTH} characters. */
    public static final StreamCodec<ByteBuf, String> URL = ByteBufCodecs.stringUtf8(UrlPolicy.MAX_LENGTH);

    private StreamPackets() {
    }

    /**
     * Server → client: stream {@code url} from the emitter at {@code pos}, sounding as {@code sound}, from
     * {@code startSeconds} in. {@code video} is the Blu-ray player whose video it is (and its disc's quality), which
     * its player also plays for that Blu-ray player's displays to show.
     */
    public record PlayAtBlock(String url, BlockPos pos, float startSeconds, JukeboxSettings settings,
                              Streams.Sound sound, Optional<Streams.Video> video) implements CustomPacketPayload {
        public static final Type<PlayAtBlock> TYPE = new Type<>(CreateMediaSystems.id("play_at_block"));
        public static final StreamCodec<ByteBuf, PlayAtBlock> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlayAtBlock::url,
                BlockPos.STREAM_CODEC, PlayAtBlock::pos,
                ByteBufCodecs.FLOAT, PlayAtBlock::startSeconds,
                JukeboxSettings.STREAM_CODEC, PlayAtBlock::settings,
                Streams.Sound.CODEC, PlayAtBlock::sound,
                ByteBufCodecs.optional(Streams.Video.CODEC), PlayAtBlock::video,
                PlayAtBlock::new);

        @Override
        public Type<PlayAtBlock> type() {
            return TYPE;
        }
    }

    /** Server → client: the stream from the block at {@code pos} keeps playing with new range and volume. */
    public record RetuneAtBlock(BlockPos pos, JukeboxSettings settings) implements CustomPacketPayload {
        public static final Type<RetuneAtBlock> TYPE = new Type<>(CreateMediaSystems.id("retune_at_block"));
        public static final StreamCodec<ByteBuf, RetuneAtBlock> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RetuneAtBlock::pos,
                JukeboxSettings.STREAM_CODEC, RetuneAtBlock::settings,
                RetuneAtBlock::new);

        @Override
        public Type<RetuneAtBlock> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: the stream of the block at {@code pos} is loaded here, paused until SyncAtBlock says to play
     * it, its track {@code duration} seconds long (0 if unknown; see Streams#started).
     */
    public record StartedAtBlock(BlockPos pos, float duration) implements CustomPacketPayload {
        public static final Type<StartedAtBlock> TYPE = new Type<>(CreateMediaSystems.id("started_at_block"));
        public static final StreamCodec<ByteBuf, StartedAtBlock> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StartedAtBlock::pos,
                ByteBufCodecs.FLOAT, StartedAtBlock::duration,
                StartedAtBlock::new);

        @Override
        public Type<StartedAtBlock> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: play the stream of the block at {@code pos} that StartedAtBlock said was loaded, from
     * {@code seconds} in: the top, once every listener of a fresh track has it, or where it is, for one that loaded it
     * later or joined it playing (see Streams#started).
     */
    public record SyncAtBlock(BlockPos pos, float seconds) implements CustomPacketPayload {
        public static final Type<SyncAtBlock> TYPE = new Type<>(CreateMediaSystems.id("sync_at_block"));
        public static final StreamCodec<ByteBuf, SyncAtBlock> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SyncAtBlock::pos,
                ByteBufCodecs.FLOAT, SyncAtBlock::seconds,
                SyncAtBlock::new);

        @Override
        public Type<SyncAtBlock> type() {
            return TYPE;
        }
    }

    /** Server → client: the block at {@code pos} stopped streaming. */
    public record StopAtBlock(BlockPos pos) implements CustomPacketPayload {
        public static final Type<StopAtBlock> TYPE = new Type<>(CreateMediaSystems.id("stop_at_block"));
        public static final StreamCodec<ByteBuf, StopAtBlock> CODEC =
                BlockPos.STREAM_CODEC.map(StopAtBlock::new, StopAtBlock::pos);

        @Override
        public Type<StopAtBlock> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToClient(PlayAtBlock.TYPE, PlayAtBlock.CODEC, (payload, context) -> ClientStreams.play(payload));
        registrar.playToClient(RetuneAtBlock.TYPE, RetuneAtBlock.CODEC, (payload, context) -> ClientStreams.retune(payload));
        registrar.playToClient(SyncAtBlock.TYPE, SyncAtBlock.CODEC, (payload, context) -> ClientStreams.sync(payload));
        registrar.playToClient(StopAtBlock.TYPE, StopAtBlock.CODEC, (payload, context) -> ClientStreams.stop(payload));
        registrar.playToServer(StartedAtBlock.TYPE, StartedAtBlock.CODEC,
                (payload, context) -> Streams.started((ServerPlayer) context.player(), payload.pos(), payload.duration()));
    }
}
