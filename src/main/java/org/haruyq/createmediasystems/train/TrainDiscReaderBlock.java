package org.haruyq.createmediasystems.train;

import java.util.List;

import org.haruyq.createmediasystems.ItemSlot;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.PercentSetting;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
public class TrainDiscReaderBlock extends Block implements IBE<TrainDiscReaderBlock.Entity>,
        SableCompat.SilentAssemblyRemoval {
    static final String ITEM_TAG = "Item";

    public TrainDiscReaderBlock(Properties properties) {
        super(properties);
    }

    /** The speakers' volume (0 to 1) in this block's saved data; full for readers saved before it had one. */
    static float volume(CompoundTag data) {
        return PercentSetting.saved(data, PercentSetting.DEFAULT_TAG) / 100f;
    }

    static ItemSlot slot() {
        return new ItemSlot(ITEM_TAG, stack -> stack.is(TrainFeature.ANNOUNCEMENT_SCHEDULE.get()), true);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Tags.Items.TOOLS_WRENCH)) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // missed the volume box: don't eject
        }
        return ItemSlot.useItemOn(stack, level, pos, player, hand);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        return ItemSlot.useWithoutItem(level, pos, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Create removes the block entity before assembling a train, so this only fires on real removal.
        ItemSlot.onRemove(this, state, level, pos, newState);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<Entity> getBlockEntityClass() {
        return Entity.class;
    }

    @Override
    public BlockEntityType<? extends Entity> getBlockEntityType() {
        return TrainFeature.TRAIN_DISC_READER_ENTITY.get();
    }

    public static class Entity extends SmartBlockEntity implements ItemSlot.Holder {
        private final ItemSlot item = slot();
        private ScrollValueBehaviour volume;

        public Entity(BlockPos pos, BlockState state) {
            super(TrainFeature.TRAIN_DISC_READER_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            volume = PercentSetting.create(this, "create_media_systems.train_disc_reader.volume",
                    new CenteredSideValueBoxTransform());
            behaviours.add(volume);
        }

        @Override
        public ItemSlot slot() {
            return item;
        }

        @Override
        public void insert(ItemStack stack, @Nullable Player by) {
            item.set(stack);
            setChanged();
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            item.save(tag, registries);
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            item.load(tag, registries);
        }
    }
}
