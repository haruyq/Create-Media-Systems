package org.haruyq.createmediasystems;

import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one item a block holds (a disc, an announcement schedule), saved under {@code key}: right-clicked with an item
 * that {@code fits}, an empty one takes it; with an empty hand, a full one gives it back; broken, it drops it. Its
 * block hands it those through {@link #useItemOn}, {@link #useWithoutItem} and {@link #onRemove}.
 */
public final class ItemSlot {
    private final String key;
    private final Predicate<ItemStack> fits;
    /**
     * Whether a right-click with anything gives back the item it holds (a train disc reader's schedule): else one
     * with an item that fits leaves it in.
     */
    private final boolean swaps;
    private ItemStack stack = ItemStack.EMPTY;

    public ItemSlot(String key, Predicate<ItemStack> fits) {
        this(key, fits, false);
    }

    public ItemSlot(String key, Predicate<ItemStack> fits, boolean swaps) {
        this.key = key;
        this.fits = fits;
        this.swaps = swaps;
    }

    /** A block entity holding one: what goes in or comes out goes through {@link #insert}. */
    public interface Holder {
        ItemSlot slot();

        /** {@code stack} (empty: nothing) is now its item, put in or taken out by {@code by}. */
        void insert(ItemStack stack, @Nullable Player by);
    }

    public ItemStack get() {
        return stack;
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public void set(ItemStack stack) {
        this.stack = stack;
    }

    public boolean fits(ItemStack stack) {
        return fits.test(stack);
    }

    /**
     * What a slot holding {@code stored} holds once {@code player} right-clicked it with {@code hand}: the item from
     * their hand, theirs again (given back), or {@code stored} as it was.
     */
    public ItemStack exchange(Player player, InteractionHand hand, ItemStack stored) {
        ItemStack held = player.getItemInHand(hand);
        if (!stored.isEmpty() && (held.isEmpty() || swaps)) {
            player.getInventory().placeItemBackInInventory(stored);
            return ItemStack.EMPTY;
        }
        return stored.isEmpty() && fits(held) ? held.split(1) : stored;
    }

    /** Into {@code tag}, under its key; with nothing in it, the key goes (a contraption keeps its block's tag). */
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            tag.remove(key);
        } else {
            tag.put(key, stack.save(registries));
        }
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        stack = ItemStack.parseOptional(registries, tag.getCompound(key));
    }

    /** A right-click with {@code held}: one that fits goes in (or swaps); anything else is left to the block. */
    public static ItemInteractionResult useItemOn(ItemStack held, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand) {
        if (!(level.getBlockEntity(pos) instanceof Holder holder) || !holder.slot().fits(held)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        exchange(holder, player, hand);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** A right-click with an item it left to the block, or none: it gives its item back. */
    public static InteractionResult useWithoutItem(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Holder holder && !holder.slot().isEmpty()) {
            player.getInventory().placeItemBackInInventory(holder.slot().get());
            holder.insert(ItemStack.EMPTY, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void exchange(Holder holder, Player player, InteractionHand hand) {
        if (player.level().isClientSide) {
            return;
        }
        ItemStack stored = holder.slot().get();
        ItemStack next = holder.slot().exchange(player, hand, stored);
        if (next != stored) {
            holder.insert(next, player);
        }
    }

    /**
     * Its block is gone (not just changed state): it drops its item. A block that does this is one that Sable must
     * remove silently, which {@code block} (the caller) declares.
     */
    public static void onRemove(SableCompat.SilentAssemblyRemoval block, BlockState state, Level level, BlockPos pos,
                                BlockState newState) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Holder holder) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), holder.slot().get());
        }
    }
}
