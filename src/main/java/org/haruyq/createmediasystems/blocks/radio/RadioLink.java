package org.haruyq.createmediasystems.blocks.radio;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.audio.LineMixerBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.LiveSource;
import org.haruyq.createmediasystems.stream.MediaSource;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the Tuning Wrench does for a radio station: a right-click on a transmitter picks it, then one on a tower (any
 * mast of it, or its antenna) or on a media source links that to the picked transmitter. Decided in one place, from
 * the wrench and the block clicked alone, so the client says it before the click (hint) and the server does it
 * (perform); anything else is the lighting link's (see lighting.LinkAction).
 */
public final class RadioLink {
    private enum Kind { PICK, TOWER, SOURCE, TOO_FAR }

    /** What a click on {@code at} does. */
    private record Action(Kind kind, BlockPos at, @Nullable BlockPos picked) {
    }

    private RadioLink() {
    }

    /** The transmitter {@code wrench} has picked in {@code level}; null for none. */
    @Nullable
    public static BlockPos picked(ItemStack wrench, Level level) {
        GlobalPos picked = wrench.get(RadioFeature.RADIO_TARGET.get());
        return picked != null && picked.dimension().equals(level.dimension()) ? picked.pos() : null;
    }

    @Nullable
    private static Action resolve(Player player, BlockPos pos) {
        Level level = player.level();
        ItemStack wrench = player.getMainHandItem();
        if (!wrench.is(LightingFeature.LIGHTING_LINK.get()) || !player.mayBuild() || !level.isLoaded(pos)
                || !SableCompat.mayInteract(level, player, pos)
                || player.distanceToSqr(SableCompat.toWorld(level, pos.getCenter())) > Reach.QUILL * Reach.QUILL) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(RadioFeature.TRANSMITTER.get())) {
            return new Action(Kind.PICK, pos, null);
        }
        BlockPos picked = picked(wrench, level);
        if (picked == null) {
            return null;
        }
        BlockPos source = state.getBlock() instanceof LineMixerBlock ? LineMixerBlock.root(pos, state) : pos;
        boolean plays = level.getBlockEntity(source) instanceof MediaSource media && !(media instanceof LiveSource);
        if (!(state.getBlock() instanceof RadioMastBlock) && !plays) {
            return null;
        }
        return !Link.inReach(level, picked, pos) ? new Action(Kind.TOO_FAR, pos, picked)
                : plays ? new Action(Kind.SOURCE, source, picked) : new Action(Kind.TOWER, pos, picked);
    }

    /** What {@code player}'s right-click on {@code pos} with the wrench would do for a radio station; null if nothing. */
    @Nullable
    public static Component hint(Player player, BlockPos pos) {
        Action action = resolve(player, pos);
        return action == null ? null : switch (action.kind) {
            case PICK -> message("hint_pick");
            case TOWER -> message("hint_tower");
            case SOURCE -> message("hint_source");
            case TOO_FAR -> tooFar();
        };
    }

    /** Does it, on the server, and tells {@code player}; false if the click is none of a radio station's. */
    public static boolean perform(Player player, BlockPos pos) {
        Action action = resolve(player, pos);
        if (action == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        ItemStack wrench = player.getMainHandItem();
        if (action.kind == Kind.PICK) {
            wrench.set(RadioFeature.RADIO_TARGET.get(), GlobalPos.of(level.dimension(), pos.immutable()));
            // One device picked at a time: a click on a media source means this transmitter's now.
            wrench.remove(LightingFeature.LIGHTING_TARGET.get());
            wrench.remove(LightingFeature.LIGHTING_SOURCE.get());
            wrench.remove(DisplayFeature.DISPLAY_SOURCE.get());
            say(player, message("picked"));
            return true;
        }
        if (action.kind == Kind.TOO_FAR) {
            say(player, tooFar());
            return true;
        }
        if (!(level.getBlockEntity(action.picked) instanceof RadioTransmitterBlock.Transmitter transmitter)
                || !SableCompat.mayInteract(level, player, action.picked)) {
            wrench.remove(RadioFeature.RADIO_TARGET.get());
            say(player, message("gone").withStyle(ChatFormatting.RED));
            return true;
        }
        // ponytail: a source is replaced by linking another, never unlinked; add a toggle if stations need to go quiet.
        if (action.kind == Kind.SOURCE) {
            transmitter.configure(level, action.at, transmitter.volume(), transmitter.channel());
            say(player, message("linked").withStyle(ChatFormatting.GREEN));
        } else if (transmitter.linkTower(level, action.at)) {
            say(player, message("linked").withStyle(ChatFormatting.GREEN));
        } else {
            say(player, message("no_antenna").withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    private static MutableComponent message(String key) {
        return Component.translatable("create_media_systems.radio." + key);
    }

    private static Component tooFar() {
        return Component.translatable("create_media_systems.radio.too_far", Link.MAX_DISTANCE).withStyle(ChatFormatting.RED);
    }

    private static void say(Player player, Component message) {
        player.displayClientMessage(message, true);
    }
}
