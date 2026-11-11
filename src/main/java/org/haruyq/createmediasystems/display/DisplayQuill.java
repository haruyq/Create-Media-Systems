package org.haruyq.createmediasystems.display;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.VideoSource;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server side of the display quill: linking displays to a Blu-ray player, their size, an image viewer's frame. */
public final class DisplayQuill {
    private DisplayQuill() {
    }

    /** Client → server: display quill links the display at {@code display} to a Blu-ray player, or unlinks it. */
    public record LinkDisplay(BlockPos display, Optional<BlockPos> videoPlayer) implements CustomPacketPayload {
        public static final Type<LinkDisplay> TYPE = new Type<>(CreateMediaSystems.id("link_display"));
        public static final StreamCodec<ByteBuf, LinkDisplay> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LinkDisplay::display,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), LinkDisplay::videoPlayer,
                LinkDisplay::new);

        @Override
        public Type<LinkDisplay> type() {
            return TYPE;
        }
    }

    /** Client → server: display quill resized a display (sixteenths of a block) and set its {@link DisplayBlock.Ratio}. */
    public record ResizeDisplay(BlockPos display, int width, int height, int ratio) implements CustomPacketPayload {
        public static final Type<ResizeDisplay> TYPE = new Type<>(CreateMediaSystems.id("resize_display"));
        public static final StreamCodec<ByteBuf, ResizeDisplay> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ResizeDisplay::display,
                ByteBufCodecs.VAR_INT, ResizeDisplay::width,
                ByteBufCodecs.VAR_INT, ResizeDisplay::height,
                ByteBufCodecs.VAR_INT, ResizeDisplay::ratio,
                ResizeDisplay::new);

        @Override
        public Type<ResizeDisplay> type() {
            return TYPE;
        }
    }

    /** Client → server: display quill takes the frame of the image viewer at {@code viewer} away, or puts it back. */
    public record ToggleFrame(BlockPos viewer) implements CustomPacketPayload {
        public static final Type<ToggleFrame> TYPE = new Type<>(CreateMediaSystems.id("toggle_frame"));
        public static final StreamCodec<ByteBuf, ToggleFrame> CODEC =
                BlockPos.STREAM_CODEC.map(ToggleFrame::new, ToggleFrame::viewer);

        @Override
        public Type<ToggleFrame> type() {
            return TYPE;
        }
    }

    @Nullable
    static DisplayBlock.Entity quillDisplay(ServerPlayer player, BlockPos pos) {
        return Reach.withQuill(player, DisplayFeature.DISPLAY_QUILL.get(), pos, DisplayBlock.Entity.class);
    }

    static void linkDisplay(ServerPlayer player, LinkDisplay payload) {
        DisplayBlock.Entity display = quillDisplay(player, payload.display());
        if (display == null) {
            return;
        }
        BlockPos source = payload.videoPlayer().orElse(null);
        if (source != null && !Link.linkable(player, source, payload.display(), VideoSource.class)) {
            return;
        }
        display.link(source);
        player.displayClientMessage(source == null ? Component.translatable("create_media_systems.display.unlinked")
                : Component.translatable("create_media_systems.display.linked", source.getX(), source.getY(), source.getZ()),
                true);
    }

    static void resizeDisplay(ServerPlayer player, ResizeDisplay payload) {
        DisplayBlock.Entity display = quillDisplay(player, payload.display());
        if (display != null) {
            display.resize(payload.width(), payload.height(), DisplayBlock.Ratio.byId(payload.ratio()));
        }
    }

    static void toggleFrame(ServerPlayer player, BlockPos pos) {
        DisplayBlock.Entity viewer = quillDisplay(player, pos);
        if (viewer == null || !viewer.isImageViewer()) {
            return;
        }
        BlockState state = viewer.getBlockState().cycle(ImageViewerBlock.FRAMED);
        player.level().setBlockAndUpdate(pos, state);
        player.displayClientMessage(Component.translatable(state.getValue(ImageViewerBlock.FRAMED)
                ? "create_media_systems.image_viewer.framed" : "create_media_systems.image_viewer.frameless"), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(LinkDisplay.TYPE, LinkDisplay.CODEC,
                (payload, context) -> linkDisplay((ServerPlayer) context.player(), payload));
        registrar.playToServer(ResizeDisplay.TYPE, ResizeDisplay.CODEC,
                (payload, context) -> resizeDisplay((ServerPlayer) context.player(), payload));
        registrar.playToServer(ToggleFrame.TYPE, ToggleFrame.CODEC,
                (payload, context) -> toggleFrame((ServerPlayer) context.player(), payload.viewer()));
    }
}
