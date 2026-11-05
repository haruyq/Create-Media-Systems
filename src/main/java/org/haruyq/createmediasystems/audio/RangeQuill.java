package org.haruyq.createmediasystems.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.SourceLinks;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server side of the range quill: a jukebox's range and volume, a speaker's volume, channel and source. */
public final class RangeQuill {
    private RangeQuill() {
    }

    /**
     * Client → server: range quill saved a jukebox's volume, and its area unless {@code keepArea}.
     * An empty {@code area} (without {@code keepArea}) resets it to the default radius. The area is in place, in the
     * jukebox's block space; it is stored relative to the jukebox.
     */
    public record SetJukeboxSettings(BlockPos jukebox, Optional<BoundingBox> area, boolean keepArea, float volume)
            implements CustomPacketPayload {
        public static final Type<SetJukeboxSettings> TYPE = new Type<>(CreateMediaSystems.id("set_jukebox_settings"));
        public static final StreamCodec<ByteBuf, SetJukeboxSettings> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetJukeboxSettings::jukebox,
                ByteBufCodecs.optional(ByteBufCodecs.fromCodec(BoundingBox.CODEC)), SetJukeboxSettings::area,
                ByteBufCodecs.BOOL, SetJukeboxSettings::keepArea,
                ByteBufCodecs.FLOAT, SetJukeboxSettings::volume,
                SetJukeboxSettings::new);

        @Override
        public Type<SetJukeboxSettings> type() {
            return TYPE;
        }
    }

    /** Client → server: range quill wants the current settings of the jukebox or speaker at {@code pos}. */
    public record QueryBlockSettings(BlockPos pos) implements CustomPacketPayload {
        public static final Type<QueryBlockSettings> TYPE = new Type<>(CreateMediaSystems.id("query_block_settings"));
        public static final StreamCodec<ByteBuf, QueryBlockSettings> CODEC =
                BlockPos.STREAM_CODEC.map(QueryBlockSettings::new, QueryBlockSettings::pos);

        @Override
        public Type<QueryBlockSettings> type() {
            return TYPE;
        }
    }

    /** Server → client: a speaker's current source and volume, in reply to a query or after saving. */
    public record SpeakerInfo(BlockPos speaker, Optional<BlockPos> source, float volume, Streams.Channel channel)
            implements CustomPacketPayload {
        public static final Type<SpeakerInfo> TYPE = new Type<>(CreateMediaSystems.id("speaker_info"));
        public static final StreamCodec<ByteBuf, SpeakerInfo> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SpeakerInfo::speaker,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), SpeakerInfo::source,
                ByteBufCodecs.FLOAT, SpeakerInfo::volume,
                Streams.Channel.CODEC, SpeakerInfo::channel,
                SpeakerInfo::new);

        @Override
        public Type<SpeakerInfo> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: range quill saved a speaker's volume, linking it to {@code connectTo} if set (else keeping
     * its source), or unlinking it when {@code disconnect}.
     */
    public record SetSpeaker(BlockPos speaker, Optional<BlockPos> connectTo, boolean disconnect, float volume,
                             Streams.Channel channel) implements CustomPacketPayload {
        public static final Type<SetSpeaker> TYPE = new Type<>(CreateMediaSystems.id("set_speaker"));
        public static final StreamCodec<ByteBuf, SetSpeaker> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetSpeaker::speaker,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), SetSpeaker::connectTo,
                ByteBufCodecs.BOOL, SetSpeaker::disconnect,
                ByteBufCodecs.FLOAT, SetSpeaker::volume,
                Streams.Channel.CODEC, SetSpeaker::channel,
                SetSpeaker::new);

        @Override
        public Type<SetSpeaker> type() {
            return TYPE;
        }
    }

    /** Server → client: a jukebox's current settings, in reply to a query or after saving. */
    public record JukeboxSettingsInfo(BlockPos jukebox, JukeboxSettings settings) implements CustomPacketPayload {
        public static final Type<JukeboxSettingsInfo> TYPE = new Type<>(CreateMediaSystems.id("jukebox_settings_info"));
        public static final StreamCodec<ByteBuf, JukeboxSettingsInfo> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, JukeboxSettingsInfo::jukebox,
                JukeboxSettings.STREAM_CODEC, JukeboxSettingsInfo::settings,
                JukeboxSettingsInfo::new);

        @Override
        public Type<JukeboxSettingsInfo> type() {
            return TYPE;
        }
    }

    /** The block entity a range quill holder may configure, or null if they can't (wrong item, too far). */
    @Nullable
    static BlockEntity quillTarget(ServerPlayer player, BlockPos pos) {
        return player.getMainHandItem().is(CreateMediaSystems.RANGE_QUILL.get())
                ? Reach.blockEntity(player, pos, Reach.QUILL, BlockEntity.class) : null;
    }

    static void sendBlockSettings(ServerPlayer player, BlockPos pos) {
        BlockEntity target = quillTarget(player, pos);
        if (target instanceof JukeboxBlockEntity jukebox) {
            PacketDistributor.sendToPlayer(player, new JukeboxSettingsInfo(pos, jukebox.getData(CreateMediaSystems.JUKEBOX_SETTINGS)));
        } else if (target instanceof SpeakerBlock.Entity speaker) {
            PacketDistributor.sendToPlayer(player,
                    new SpeakerInfo(pos, speaker.source(), speaker.volume(), speaker.channel()));
        }
    }

    static void applySpeaker(ServerPlayer player, SetSpeaker payload) {
        if (!(quillTarget(player, payload.speaker()) instanceof SpeakerBlock.Entity speaker)) {
            return;
        }
        BlockPos source = payload.disconnect() ? null : payload.connectTo().or(speaker::source).orElse(null);
        if (source != null && !source.equals(speaker.source().orElse(null))
                && !SourceLinks.linkable(player, source, payload.speaker(), MediaSource.class)) {
            return;
        }
        speaker.configure(player.serverLevel(), source, payload.volume(), payload.channel());
        sendBlockSettings(player, payload.speaker());
        player.displayClientMessage(Component.translatable("create_media_systems.speaker.saved"), true);
    }

    static void applyJukeboxSettings(ServerPlayer player, SetJukeboxSettings payload) {
        if (!(quillTarget(player, payload.jukebox()) instanceof JukeboxBlockEntity jukebox)) {
            return;
        }
        boolean tooBig = payload.area().map(box -> box.getXSpan() > JukeboxSettings.MAX_SIZE
                || box.getYSpan() > JukeboxSettings.MAX_SIZE || box.getZSpan() > JukeboxSettings.MAX_SIZE).orElse(false);
        if (tooBig) {
            player.displayClientMessage(Component.translatable("create_media_systems.range.too_big", JukeboxSettings.MAX_SIZE)
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        Optional<BoundingBox> area = payload.keepArea() ? jukebox.getData(CreateMediaSystems.JUKEBOX_SETTINGS).area()
                : payload.area().map(box -> JukeboxSettings.relative(box, payload.jukebox()));
        jukebox.setData(CreateMediaSystems.JUKEBOX_SETTINGS, new JukeboxSettings(area, payload.volume()));
        jukebox.setChanged();
        Jukeboxes.relay(player.serverLevel(), jukebox);
        sendBlockSettings(player, payload.jukebox());
        player.displayClientMessage(Component.translatable("create_media_systems.range.saved"), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetJukeboxSettings.TYPE, SetJukeboxSettings.CODEC,
                (payload, context) -> applyJukeboxSettings((ServerPlayer) context.player(), payload));
        registrar.playToServer(QueryBlockSettings.TYPE, QueryBlockSettings.CODEC,
                (payload, context) -> sendBlockSettings((ServerPlayer) context.player(), payload.pos()));
        registrar.playToServer(SetSpeaker.TYPE, SetSpeaker.CODEC,
                (payload, context) -> applySpeaker((ServerPlayer) context.player(), payload));
        registrar.playToClient(SpeakerInfo.TYPE, SpeakerInfo.CODEC, (payload, context) -> RangeQuillHandler.onSpeakerInfo(payload));
        registrar.playToClient(JukeboxSettingsInfo.TYPE, JukeboxSettingsInfo.CODEC,
                (payload, context) -> RangeQuillHandler.onSettings(payload));
    }
}
