package org.haruyq.createmediasystems.train;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.PercentSetting;

import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.Tags;

/**
 * Holds one announcement schedule; {@link TrainDiscReaderBehaviour} plays it on a train through the train's
 * {@link TrainSpeakerBlock}s. Their volume is set here, on the placed block: hold right-click on it with a wrench.
 */
public class TrainDiscReaderBlock extends Block implements IBE<TrainDiscReaderBlock.Entity> {
    static final String ITEM_TAG = "Item";

    public TrainDiscReaderBlock(Properties properties) {
        super(properties);
    }

    /** The speakers' volume (0 to 1) in this block's saved data; full for readers saved before it had one. */
    static float volume(CompoundTag data) {
        return PercentSetting.saved(data, PercentSetting.DEFAULT_TAG) / 100f;
    }

    /** Takes the held announcement schedule if empty, else hands the stored one back. Returns the new content. */
    static ItemStack swap(Player player, InteractionHand hand, ItemStack stored) {
        if (!stored.isEmpty()) {
            player.getInventory().placeItemBackInInventory(stored);
            return ItemStack.EMPTY;
        }
        ItemStack held = player.getItemInHand(hand);
        return held.is(CreateMediaSystems.ANNOUNCEMENT_SCHEDULE.get()) ? held.split(1) : ItemStack.EMPTY;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Tags.Items.TOOLS_WRENCH)) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // missed the volume box: don't eject
        }
        if (!stack.is(CreateMediaSystems.ANNOUNCEMENT_SCHEDULE.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        interact(level, pos, player, hand);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        interact(level, pos, player, InteractionHand.MAIN_HAND);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void interact(Level level, BlockPos pos, Player player, InteractionHand hand) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity be) {
            be.item = swap(player, hand, be.item);
            be.setChanged();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Create removes the block entity before assembling a train, so this only fires on real removal; Sable
        // assembling a ship is kept from dropping it by data/sable/tags/block/silent_assembly_removal.json.
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity be) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), be.item);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<Entity> getBlockEntityClass() {
        return Entity.class;
    }

    @Override
    public BlockEntityType<? extends Entity> getBlockEntityType() {
        return CreateMediaSystems.TRAIN_DISC_READER_ENTITY.get();
    }

    public static class Entity extends SmartBlockEntity {
        private ItemStack item = ItemStack.EMPTY;
        private ScrollValueBehaviour volume;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.TRAIN_DISC_READER_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            volume = PercentSetting.create(this, "create_media_systems.train_disc_reader.volume",
                    new CenteredSideValueBoxTransform());
            behaviours.add(volume);
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            if (!item.isEmpty()) {
                tag.put(ITEM_TAG, item.save(registries));
            }
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            item = ItemStack.parseOptional(registries, tag.getCompound(ITEM_TAG));
        }
    }
}
