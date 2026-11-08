package org.haruyq.createmediasystems;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/** How far a player reaches the blocks they work, checked by the server and aimed at by the client alike. */
public final class Reach {
    /** What a hand works directly: an equalizer's faders, an image viewer's link, a channel. */
    public static final double HAND = 8;
    /** What a quill picks and sets up from afar. */
    public static final double QUILL = 75;

    private Reach() {
    }

    @Nullable
    public static <T> T withQuill(Player player, Item quill, BlockPos pos, Class<T> type) {
        return player.getMainHandItem().is(quill) ? blockEntity(player, pos, QUILL, type) : null;
    }

    @Nullable
    public static <T> T blockEntity(Player player, BlockPos pos, double reach, Class<T> type) {
        return player.level().isLoaded(pos) && player.position().distanceToSqr(SableCompat.toWorld(player.level(), pos.getCenter())) <= reach * reach
                && type.isInstance(player.level().getBlockEntity(pos)) ? type.cast(player.level().getBlockEntity(pos)) : null;
    }
}
