package org.haruyq.createmediasystems.audio;

import org.haruyq.createmediasystems.ItemSlot;
import org.haruyq.createmediasystems.stream.DiscDrive;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

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
        return ItemSlot.useItemOn(stack, level, pos, player, hand);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        return ItemSlot.useWithoutItem(level, pos, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        ItemSlot.onRemove(state, level, pos, newState);
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
        return level.isClientSide || type != AudioFeature.DISC_READER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick();
    }

    public static class Entity extends BlockEntity implements MediaSource, DiscDrive.Fed, ItemSlot.Holder {
        private final ItemSlot disc = new ItemSlot("Disc", stack -> MediaItem.fits(stack, DiscKind.Slot.JUKEBOX));
        /** How far into its disc's track it is; it runs while it plays. */
        private final PlaybackClock clock = new PlaybackClock();
        /** Whether it had a redstone signal, so only a change starts or stops it. */
        private boolean powered;

        public Entity(BlockPos pos, BlockState state) {
            super(AudioFeature.DISC_READER_ENTITY.get(), pos, state);
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            String url = clock.running() ? url() : null;
            return url != null ? new Streams.Source(url, clock.position(), null) : null;
        }

        /** Its disc's link, or with no disc that of the audio CD in a drive beside it (see DiscDrive). */
        @Nullable
        private String url() {
            if (!disc.isEmpty()) {
                return MediaItem.url(disc.get());
            }
            DiscDrive drive = DiscDrive.feeding(level, worldPosition, this);
            return drive == null ? null : drive.link();
        }

        /** A drive's disc with sound alone. */
        @Override
        public boolean plays(DiscKind kind) {
            return !kind.hasPicture();
        }

        /** Plays the CD of the drive beside it from the beginning, or stops with it, unless it has a disc of its own. */
        @Override
        public void driveChanged(@Nullable Player by) {
            if (disc.isEmpty()) {
                insert(ItemStack.EMPTY, by);
            }
        }

        @Override
        public ItemSlot slot() {
            return disc;
        }

        /** A new disc (or none, but a drive's CD) starts from the beginning; speakers notice the restart on their next check. */
        @Override
        public void insert(ItemStack stack, @Nullable Player by) {
            disc.set(stack);
            if (url() != null) {
                play(by);
            } else {
                clock.stop();
            }
            // A signal already on doesn't count as turning on later.
            powered = level != null && level.hasNeighborSignal(worldPosition);
            setChanged();
        }

        /** Its track plays from the top, for its listeners to load (see Streams#loading). */
        private void play(@Nullable Player by) {
            clock.start(0);
            if (level != null) {
                Streams.loading(this, by, clock, level.getGameTime());
            }
        }

        /** A redstone signal turning on plays the disc from the start (unless it plays already); off stops it. */
        void power(boolean on) {
            if (on == powered) {
                return;
            }
            powered = on;
            if (on && !clock.running() && url() != null) {
                play(null);
            } else if (!on) {
                clock.stop();
            }
            setChanged();
        }

        void tick() {
            if (!clock.running()) {
                return;
            }
            long now = level.getGameTime();
            Streams.tick(this, clock, now);
            String url = url();
            if (url != null && clock.ended(url, now)) {
                clock.stop();
            }
            setChanged();
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            disc.save(tag, registries);
            tag.putLong("Ticks", clock.position());
            tag.putBoolean("Playing", clock.running());
            tag.putBoolean("Powered", powered);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            disc.load(tag, registries);
            clock.load(tag.getLong("Ticks"), tag.getBoolean("Playing"));
            powered = tag.getBoolean("Powered");
        }
    }
}
