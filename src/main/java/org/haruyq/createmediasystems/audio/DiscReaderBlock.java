package org.haruyq.createmediasystems.audio;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Plays a custom disc silently: it keeps the track's position so linked speakers can play it in sync. A disc plays
 * once inserted; a redstone signal turning on plays it from the start, and turning off stops it.
 */
public class DiscReaderBlock extends Block implements EntityBlock {
    public DiscReaderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(CreateMediaSystems.CUSTOM_DISC.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity reader && reader.disc.isEmpty()) {
            reader.insert(stack.split(1), player);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity reader && !reader.disc.isEmpty()) {
            player.getInventory().placeItemBackInInventory(reader.disc);
            reader.insert(ItemStack.EMPTY, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Sable assembling a ship removes it too: data/sable/tags/block/silent_assembly_removal.json keeps that from dropping it.
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity reader) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), reader.disc);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos,
                                   boolean movedByPiston) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity reader) {
            reader.power(level.hasNeighborSignal(pos));
        }
        super.neighborChanged(state, level, pos, neighbor, neighborPos, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateMediaSystems.DISC_READER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick();
    }

    public static class Entity extends BlockEntity implements MediaSource, DriveEmulatorBlock.Fed {
        private ItemStack disc = ItemStack.EMPTY;
        private long ticks;
        private boolean playing;
        /** Whether it had a redstone signal, so only a change starts or stops it. */
        private boolean powered;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.DISC_READER_ENTITY.get(), pos, state);
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            String url = playing ? url() : null;
            return url != null ? new Streams.Source(url, ticks, null) : null;
        }

        /** Its disc's link, or with no disc that of the audio CD in a disc drive emulator beside it (see DriveEmulatorBlock). */
        @Nullable
        private String url() {
            if (!disc.isEmpty()) {
                return disc.get(CreateMediaSystems.URL.get());
            }
            DriveEmulatorBlock.Entity drive = DriveEmulatorBlock.feeding(level, worldPosition, true);
            return drive == null ? null : drive.link();
        }

        /** Plays the CD of the drive beside it from the beginning, or stops with it, unless it has a disc of its own. */
        @Override
        public void driveChanged(@Nullable Player by) {
            if (disc.isEmpty()) {
                insert(ItemStack.EMPTY, by);
            }
        }

        /** A new disc (or none, but a drive's CD) starts from the beginning; speakers notice the restart on their next check. */
        public void insert(ItemStack stack, @Nullable Player by) {
            disc = stack;
            ticks = 0;
            playing = url() != null;
            Streams.loading(this, by);
            // A signal already on doesn't count as turning on later.
            powered = level != null && level.hasNeighborSignal(worldPosition);
            setChanged();
        }

        /** A redstone signal turning on plays the disc from the start (unless it plays already); off stops it. */
        void power(boolean on) {
            if (on == powered) {
                return;
            }
            powered = on;
            if (on && !playing && url() != null) {
                ticks = 0;
                playing = true;
                Streams.loading(this, null);
            } else if (!on) {
                playing = false;
            }
            setChanged();
        }

        void tick() {
            if (!playing) {
                return;
            }
            ticks++;
            Streams.Source source = Streams.playing(this);
            if (ticks % Streams.RELAY_TICKS == 0 && source != null && Streams.endedAt(source)) {
                playing = false;
            }
            setChanged();
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (!disc.isEmpty()) {
                tag.put("Disc", disc.save(registries));
            }
            tag.putLong("Ticks", ticks);
            tag.putBoolean("Playing", playing);
            tag.putBoolean("Powered", powered);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            disc = ItemStack.parseOptional(registries, tag.getCompound("Disc"));
            ticks = tag.getLong("Ticks");
            playing = tag.getBoolean("Playing");
            powered = tag.getBoolean("Powered");
        }
    }
}
