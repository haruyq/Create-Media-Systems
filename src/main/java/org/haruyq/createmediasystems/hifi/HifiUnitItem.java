package org.haruyq.createmediasystems.hifi;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One unit of a hi-fi stack. Used on a stack it goes on top of it, in the same block, if the stack has room and none
 * of its kind yet; anywhere else it is placed as a new stack of one.
 */
public class HifiUnitItem extends BlockItem {
    public final Unit unit;

    public HifiUnitItem(Block stack, Unit unit, Properties properties) {
        super(stack, properties);
        this.unit = unit;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.getBlockEntity(context.getClickedPos()) instanceof HifiStackBlock.Entity stack && stack.canAdd(unit)) {
            if (!level.isClientSide) {
                stack.add(unit);
                level.playSound(null, context.getClickedPos(), SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.BLOCKS, 0.6f, 1.3f);
                if (context.getPlayer() == null || !context.getPlayer().hasInfiniteMaterials()) {
                    context.getItemInHand().shrink(1);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useOn(context);
    }

    /** The stack it was just placed as starts with it. */
    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player, ItemStack stack,
                                                 BlockState state) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof HifiStackBlock.Entity placed) {
            placed.add(unit);
        }
        return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }

    /** Named as the unit it is, not as the stack. */
    @Override
    public String getDescriptionId() {
        return getOrCreateDescriptionId();
    }

    /** Five items place the one stack block, so none of them is the block's own item. */
    @Override
    public void registerBlocks(Map<Block, Item> blockToItemMap, Item item) {
    }
}
