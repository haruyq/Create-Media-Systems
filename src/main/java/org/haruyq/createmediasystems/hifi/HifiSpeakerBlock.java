package org.haruyq.createmediasystems.hifi;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A hi-fi stack's own speaker: a slim cabinet on a foot, as tall as the stack. It plays the left or the right of what
 * its amplifier plays, at the amplifier's volume, once a speaker cable runs to it: touch one of the amplifier's
 * speaker terminals with an audio cable, then the speaker. Sneak and right-click it with an empty hand to pull the
 * cable out.
 */
public class HifiSpeakerBlock extends SpeakerBlock {
    private static final MapCodec<HifiSpeakerBlock> CODEC = simpleCodec(HifiSpeakerBlock::new);
    private static final VoxelShape ALONG_Z = Shapes.or(Block.box(2.5, 0, 3, 13.5, 1, 13), Block.box(3.5, 1, 4, 12.5, 16, 12));
    private static final VoxelShape ALONG_X = Shapes.or(Block.box(3, 0, 2.5, 13, 1, 13.5), Block.box(4, 1, 3.5, 12, 16, 12.5));

    public HifiSpeakerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends SpeakerBlock> codec() {
        return CODEC;
    }

    /** Always upright, its front to whoever places it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** A wrench turns it about where it stands. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide) {
            level.setBlock(context.getClickedPos(), state.setValue(FACING, state.getValue(FACING).getClockWise()), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? ALONG_X : ALONG_Z;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(HifiFeature.HIFI_CABLE.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity speaker) {
            speaker.connect((ServerPlayer) player, stack);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !(level.getBlockEntity(pos) instanceof Entity speaker) || speaker.source().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel server) {
            player.getInventory().placeItemBackInInventory(speaker.unplug(server));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Sable assembling a ship removes it too: data/sable/tags/block/silent_assembly_removal.json keeps that from dropping it.
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity speaker && speaker.cabled) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(HifiFeature.HIFI_CABLE.get()));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != HifiFeature.HIFI_SPEAKER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l, s);
    }

    public static class Entity extends SpeakerBlock.Entity {
        /** Whether a cable was used up to link it, which pulling it out gives back (a range quill links it without one). */
        private boolean cabled;

        public Entity(BlockPos pos, BlockState state) {
            super(HifiFeature.HIFI_SPEAKER_ENTITY.get(), pos, state);
        }

        /**
         * Runs the cable {@code player} holds from the amplifier terminal they last touched with it to this speaker,
         * if it reaches; a speaker with a cable already has that one moved.
         */
        void connect(ServerPlayer player, ItemStack cable) {
            HifiCableItem.From from = HifiCableItem.picked(player);
            ServerLevel level = player.serverLevel();
            if (from == null || from.stack().dimension() != level.dimension() || !level.isLoaded(from.stack().pos())
                    || !(level.getBlockEntity(from.stack().pos()) instanceof HifiStackBlock.Entity stack)
                    || !stack.has(Unit.AMPLIFIER)) {
                player.displayClientMessage(Component.translatable("create_media_systems.hifi.pick_terminal")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            if (SableCompat.distance(level, from.stack().pos(), worldPosition) > HifiCableItem.MAX_LENGTH) {
                player.displayClientMessage(Component.translatable("create_media_systems.hifi.cable_too_short",
                        HifiCableItem.MAX_LENGTH).withStyle(ChatFormatting.RED), true);
                return;
            }
            if (!cabled) {
                cable.consume(1, player);
                cabled = true;
            }
            configure(level, from.stack().pos(), 1, from.channel());
            HifiCableItem.done(player);
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.4f, 1.3f);
            player.displayClientMessage(Component.translatable("create_media_systems.hifi.speaker_connected",
                    from.channel().label()), true);
        }

        /** Unlinks it; the cable that linked it comes back, if one did. */
        ItemStack unplug(ServerLevel level) {
            ItemStack cable = cabled ? new ItemStack(HifiFeature.HIFI_CABLE.get()) : ItemStack.EMPTY;
            cabled = false;
            configure(level, null, 1, Streams.Channel.MIX);
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.4f, 0.8f);
            return cable;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putBoolean("Cabled", cabled);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            cabled = tag.getBoolean("Cabled");
        }
    }
}
