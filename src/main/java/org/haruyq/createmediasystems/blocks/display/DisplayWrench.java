package org.haruyq.createmediasystems.blocks.display;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.lighting.LightingLinkItem;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.VideoSource;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * What the Tuning Wrench does for screens. A right-click on a Blu-ray player or hi-fi stack picks it, while no lighting
 * console or transmitter is picked (a click on the player is theirs then). A right-click on a display links it to the
 * picked player, Shift+right-click unlinks it, and one on an image viewer takes its frame away or puts it back.
 * Decided in one place, from the wrench and the block clicked, so the client says it before the click (hint) and the
 * server does it (perform). Sizes and lighting come in packets of their own (see DisplayWrenchHandler).
 */
public final class DisplayWrench {
    private enum Kind {
        /** Picks the player clicked. */
        PICK,
        /** Links the display to the picked player. */
        LINK,
        /** The display shows the picked player already. */
        LINKED,
        /** Unlinks the display (Shift). */
        UNLINK,
        /** Takes the image viewer's frame away, or puts it back. */
        FRAME,
        /** A display, with no player picked. */
        PICK_FIRST,
        /** A display, with a console or transmitter picked: puts that down, for a player to be picked. */
        PUT_DOWN
    }

    private record Action(Kind kind, BlockPos at, @Nullable BlockPos picked) {
    }

    private DisplayWrench() {
    }

    /** Client → server: the wrench resized a screen (sixteenths of a block) and set its {@link DisplayBlock.Ratio}. */
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

    /** Client → server: the wrench's lighting panel set a display's cinematic and ambient lighting, in percent. */
    public record LightDisplay(BlockPos display, int cinematic, int ambient) implements CustomPacketPayload {
        public static final Type<LightDisplay> TYPE = new Type<>(CreateMediaSystems.id("light_display"));
        public static final StreamCodec<ByteBuf, LightDisplay> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LightDisplay::display,
                ByteBufCodecs.VAR_INT, LightDisplay::cinematic,
                ByteBufCodecs.VAR_INT, LightDisplay::ambient,
                LightDisplay::new);

        @Override
        public Type<LightDisplay> type() {
            return TYPE;
        }
    }

    /** The player {@code wrench} has picked for displays in {@code level}; null for none. */
    @Nullable
    public static BlockPos picked(ItemStack wrench, Level level) {
        GlobalPos picked = wrench.get(DisplayFeature.DISPLAY_SOURCE.get());
        return picked != null && picked.dimension().equals(level.dimension()) ? picked.pos() : null;
    }

    @Nullable
    private static Action resolve(Player player, BlockPos pos) {
        Level level = player.level();
        ItemStack wrench = player.getMainHandItem();
        if (!wrench.is(LightingFeature.LIGHTING_LINK.get()) || !player.mayBuild() || !level.isLoaded(pos)
                || !SableCompat.mayInteract(level, player, pos)) {
            return null;
        }
        BlockPos anchor = DisplayBlock.anchor(level, pos);
        ScreenEntity screen = ScreenEntity.near(player, anchor, Reach.QUILL, ScreenEntity.class);
        if (screen instanceof ImageViewerBlock.Entity) {
            return new Action(Kind.FRAME, anchor, null);
        }
        BlockPos picked = picked(wrench, level);
        if (screen instanceof DisplayBlock.Entity display) {
            if (player.isShiftKeyDown()) {
                return display.source().isPresent() ? new Action(Kind.UNLINK, anchor, null) : null;
            }
            if (picked != null) {
                return new Action(display.source().filter(picked::equals).isPresent() ? Kind.LINKED : Kind.LINK, anchor, picked);
            }
            boolean others = wrench.has(LightingFeature.LIGHTING_TARGET.get()) || wrench.has(RadioFeature.RADIO_TARGET.get());
            return new Action(others ? Kind.PUT_DOWN : Kind.PICK_FIRST, anchor, null);
        }
        boolean near = player.distanceToSqr(SableCompat.toWorld(level, pos.getCenter())) <= Reach.QUILL * Reach.QUILL;
        return near && level.getBlockEntity(pos) instanceof VideoSource && LightingLinkItem.selected(player) == null
                ? new Action(Kind.PICK, pos, picked) : null;
    }

    /** What {@code player}'s right-click on {@code pos} with the wrench would do for a screen; null if nothing. */
    @Nullable
    public static Component hint(Player player, BlockPos pos) {
        Action action = resolve(player, pos);
        if (action == null) {
            return null;
        }
        return switch (action.kind) {
            case PICK -> pos.equals(action.picked) ? message("picked", xyz(pos)) : message("hint_pick_player");
            case LINK -> message("hint_link");
            case LINKED -> player.level().getBlockEntity(action.at) instanceof ScreenEntity screen
                    ? DisplayBlock.sizeText(screen.widthUnits(), screen.heightUnits(), screen.ratio()) : null;
            case UNLINK -> message("hint_unlink");
            case FRAME -> message("hint_frame");
            case PICK_FIRST -> message("hint_pick");
            case PUT_DOWN -> message("hint_put_down");
        };
    }

    /** Does it, on the server, and tells {@code player}; false if the click is none of a screen's. */
    public static boolean perform(Player player, BlockPos pos) {
        Action action = resolve(player, pos);
        if (action == null || !(player instanceof ServerPlayer server)) {
            return false;
        }
        Level level = player.level();
        ItemStack wrench = player.getMainHandItem();
        switch (action.kind) {
            case PICK -> {
                wrench.set(DisplayFeature.DISPLAY_SOURCE.get(), GlobalPos.of(level.dimension(), pos.immutable()));
                // One device picked at a time, as a console or transmitter picked drops this one.
                wrench.remove(LightingFeature.LIGHTING_TARGET.get());
                wrench.remove(LightingFeature.LIGHTING_SOURCE.get());
                wrench.remove(RadioFeature.RADIO_TARGET.get());
                say(player, message("picked", xyz(pos)));
            }
            case LINK -> {
                if (Link.linkable(server, action.picked, action.at, VideoSource.class)) {
                    ((DisplayBlock.Entity) level.getBlockEntity(action.at)).link(action.picked);
                    say(player, linked(action.picked));
                } else if (!level.isLoaded(action.picked) || !(level.getBlockEntity(action.picked) instanceof VideoSource)) {
                    wrench.remove(DisplayFeature.DISPLAY_SOURCE.get());
                    say(player, message("pick_first"));
                }
            }
            case LINKED -> say(player, linked(action.picked));
            case UNLINK -> {
                ((DisplayBlock.Entity) level.getBlockEntity(action.at)).link(null);
                say(player, Component.translatable("create_media_systems.display.unlinked"));
            }
            case FRAME -> {
                BlockState state = level.getBlockState(action.at).cycle(ImageViewerBlock.FRAMED);
                level.setBlockAndUpdate(action.at, state);
                say(player, Component.translatable(state.getValue(ImageViewerBlock.FRAMED)
                        ? "create_media_systems.image_viewer.framed" : "create_media_systems.image_viewer.frameless"));
            }
            case PICK_FIRST -> say(player, message("pick_first"));
            case PUT_DOWN -> {
                wrench.remove(LightingFeature.LIGHTING_TARGET.get());
                wrench.remove(LightingFeature.LIGHTING_SOURCE.get());
                wrench.remove(RadioFeature.RADIO_TARGET.get());
                say(player, message("put_down"));
            }
        }
        return true;
    }

    /** The screen of {@code kind} at {@code pos}, if {@code player} holds the wrench, may change it and is near it. */
    @Nullable
    static <T extends ScreenEntity> T withWrench(ServerPlayer player, BlockPos pos, Class<T> kind) {
        return player.getMainHandItem().is(LightingFeature.LIGHTING_LINK.get()) && player.mayBuild()
                && player.level().isLoaded(pos) && SableCompat.mayInteract(player.level(), player, pos)
                ? ScreenEntity.near(player, pos, Reach.QUILL, kind) : null;
    }

    static void resizeDisplay(ServerPlayer player, ResizeDisplay payload) {
        ScreenEntity screen = withWrench(player, payload.display(), ScreenEntity.class);
        if (screen != null) {
            screen.resize(payload.width(), payload.height(), DisplayBlock.Ratio.byId(payload.ratio()));
        }
    }

    static void lightDisplay(ServerPlayer player, LightDisplay payload) {
        DisplayBlock.Entity display = withWrench(player, payload.display(), DisplayBlock.Entity.class);
        if (display != null) {
            display.setLighting(payload.cinematic(), payload.ambient());
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(ResizeDisplay.TYPE, ResizeDisplay.CODEC,
                (payload, context) -> resizeDisplay((ServerPlayer) context.player(), payload));
        registrar.playToServer(LightDisplay.TYPE, LightDisplay.CODEC,
                (payload, context) -> lightDisplay((ServerPlayer) context.player(), payload));
    }

    private static Component linked(BlockPos source) {
        return Component.translatable("create_media_systems.display.linked", source.getX(), source.getY(), source.getZ());
    }

    private static Component message(String key, Object... args) {
        return Component.translatable("create_media_systems.display_wrench." + key, args);
    }

    private static Object[] xyz(BlockPos pos) {
        return new Object[] {pos.getX(), pos.getY(), pos.getZ()};
    }

    private static void say(Player player, Component message) {
        player.displayClientMessage(message, true);
    }
}
