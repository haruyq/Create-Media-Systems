package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class StreamPackets {
    public static final StreamCodec<ByteBuf, String> URL = ByteBufCodecs.stringUtf8(UrlPolicy.MAX_LENGTH);

    /** What a client does with the server's words: ClientStreams, which sets it as the client starts. */
    private static Client client;

    private StreamPackets() {
    }

    public interface Client {
        void play(PlayAtBlock payload);

        void retune(RetuneAtBlock payload);

        void sync(SyncAtBlock payload);

        void stop(StopAtBlock payload);
    }

    public static void client(Client handler) {
        client = handler;
    }

    /**
     * The emitter a client tells the server about: a block's, by its place, or one that is no block, by the channel its
     * clients play it on (a train's disc reader's; see MediaSource.Keyed). A channel's clients play it as soon as they
     * have it and stop it themselves (getting off the train), so the server tells them neither.
     */
    public sealed interface Target {
        StreamCodec<ByteBuf, Target> CODEC = ByteBufCodecs.BOOL.dispatch(target -> target instanceof Channel,
                channel -> channel ? Channel.CODEC : Block.CODEC);

        /** What tells its listener to play what it has loaded, from {@code seconds} in; null for none. */
        @Nullable
        CustomPacketPayload sync(float seconds);

        /** What tells its listener it stopped; null for none. */
        @Nullable
        CustomPacketPayload stop();

        record Block(BlockPos pos) implements Target {
            static final StreamCodec<ByteBuf, Block> CODEC = BlockPos.STREAM_CODEC.map(Block::new, Block::pos);

            @Override
            public CustomPacketPayload sync(float seconds) {
                return new SyncAtBlock(pos, seconds);
            }

            @Override
            public CustomPacketPayload stop() {
                return new StopAtBlock(pos);
            }
        }

        record Channel(String id) implements Target {
            static final StreamCodec<ByteBuf, Channel> CODEC = ByteBufCodecs.STRING_UTF8.map(Channel::new, Channel::id);

            @Override
            @Nullable
            public CustomPacketPayload sync(float seconds) {
                return null;
            }

            @Override
            @Nullable
            public CustomPacketPayload stop() {
                return null;
            }
        }
    }

    /**
     * Server → client: stream {@code url} from the emitter at {@code pos}, sounding as {@code sound} and heard over
     * {@code range}, from {@code startSeconds} in. {@code video} is the source whose video it is (and its disc's
     * quality), which its player also plays for that source's displays to show. {@code load} says which load of its source's
     * track it is (see Streams.Load): streams of one load are one playback, however far apart they come (while it
     * loads, every one of them says 0 s, so their time can't tell); 0 if unknown.
     */
    public record PlayAtBlock(String url, BlockPos pos, float startSeconds, EmitterRange range,
                              Streams.Sound sound, Optional<Streams.Video> video, long load) implements CustomPacketPayload {
        public static final Type<PlayAtBlock> TYPE = new Type<>(CreateMediaSystems.id("play_at_block"));
        public static final StreamCodec<ByteBuf, PlayAtBlock> CODEC = NeoForgeStreamCodecs.composite(
                ByteBufCodecs.STRING_UTF8, PlayAtBlock::url,
                BlockPos.STREAM_CODEC, PlayAtBlock::pos,
                ByteBufCodecs.FLOAT, PlayAtBlock::startSeconds,
                EmitterRange.STREAM_CODEC, PlayAtBlock::range,
                Streams.Sound.CODEC, PlayAtBlock::sound,
                ByteBufCodecs.optional(Streams.Video.CODEC), PlayAtBlock::video,
                ByteBufCodecs.VAR_LONG, PlayAtBlock::load,
                PlayAtBlock::new);

        @Override
        public Type<PlayAtBlock> type() {
            return TYPE;
        }
    }

    /** Server → client: the stream from the block at {@code pos} keeps playing with new range and volume. */
    public record RetuneAtBlock(BlockPos pos, EmitterRange range) implements CustomPacketPayload {
        public static final Type<RetuneAtBlock> TYPE = new Type<>(CreateMediaSystems.id("retune_at_block"));
        public static final StreamCodec<ByteBuf, RetuneAtBlock> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RetuneAtBlock::pos,
                EmitterRange.STREAM_CODEC, RetuneAtBlock::range,
                RetuneAtBlock::new);

        @Override
        public Type<RetuneAtBlock> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: the stream of the emitter {@code target} is loaded here, its track {@code duration} seconds long
     * (0 if unknown; see Streams#started); a block's waits paused until SyncAtBlock says to play it.
     */
    public record Started(Target target, float duration) implements CustomPacketPayload {
        public static final Type<Started> TYPE = new Type<>(CreateMediaSystems.id("started_at_block"));
        public static final StreamCodec<ByteBuf, Started> CODEC = StreamCodec.composite(
                Target.CODEC, Started::target,
                ByteBufCodecs.FLOAT, Started::duration,
                Started::new);

        public Started(BlockPos pos, float duration) {
            this(new Target.Block(pos), duration);
        }

        @Override
        public Type<Started> type() {
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

    /**
     * Client → server: {@code downloaded} (0..1) of the video {@code url} the source at {@code pos} plays is kept on
     * that client (see VideoSource#downloaded).
     */
    public record DownloadedAtBlock(BlockPos pos, String url, float downloaded) implements CustomPacketPayload {
        public static final Type<DownloadedAtBlock> TYPE = new Type<>(CreateMediaSystems.id("downloaded_at_block"));
        public static final StreamCodec<ByteBuf, DownloadedAtBlock> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DownloadedAtBlock::pos,
                ByteBufCodecs.STRING_UTF8, DownloadedAtBlock::url,
                ByteBufCodecs.FLOAT, DownloadedAtBlock::downloaded,
                DownloadedAtBlock::new);

        @Override
        public Type<DownloadedAtBlock> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToClient(PlayAtBlock.TYPE, PlayAtBlock.CODEC, (payload, context) -> client.play(payload));
        registrar.playToClient(RetuneAtBlock.TYPE, RetuneAtBlock.CODEC, (payload, context) -> client.retune(payload));
        registrar.playToClient(SyncAtBlock.TYPE, SyncAtBlock.CODEC, (payload, context) -> client.sync(payload));
        registrar.playToClient(StopAtBlock.TYPE, StopAtBlock.CODEC, (payload, context) -> client.stop(payload));
        registrar.playToServer(Started.TYPE, Started.CODEC,
                (payload, context) -> Streams.started((ServerPlayer) context.player(), payload.target(), payload.duration()));
        registrar.playToServer(DownloadedAtBlock.TYPE, DownloadedAtBlock.CODEC,
                (payload, context) -> downloaded((ServerPlayer) context.player(), payload));
    }

    private static void downloaded(ServerPlayer player, DownloadedAtBlock payload) {
        ServerLevel level = player.serverLevel();
        if (level.isLoaded(payload.pos()) && level.getBlockEntity(payload.pos()) instanceof VideoSource source
                && Streams.listeners(level, payload.pos()).contains(player)) {
            source.downloaded(player, payload.url(), payload.downloaded());
        }
    }
}
