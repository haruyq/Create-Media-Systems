package org.haruyq.createmediasystems.blocks.audio;

import org.haruyq.createmediasystems.ItemSlot;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock;
import org.haruyq.createmediasystems.stream.DiscDrive;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
 * Plays a custom disc silently: it keeps the track's position so linked speakers can play it in sync. Through
 * Engineer's Goggles it shows whether it plays, its disc and link, and how far into the track it is.
 */
public class DiscReaderBlock extends Block implements EntityBlock, SableCompat.SilentAssemblyRemoval {
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
        ItemSlot.onRemove(this, state, level, pos, newState);
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

    public static class Entity extends BlockEntity implements MediaSource.Captured, DiscDrive.Fed, ItemSlot.Holder,
            IHaveGoggleInformation {
        private final ItemSlot disc = new ItemSlot("Disc", stack -> MediaItem.fits(stack, DiscKind.Slot.JUKEBOX));
        private final PlaybackClock clock = new PlaybackClock();
        private boolean powered;
        /**
         * Whether its clock counts on, rather than standing while its listeners load the track (see Streams#tick):
         * clients count its position on from what the server last sent only while it does.
         */
        private boolean counting;
        /** On a client: the game time the server sent its position at. */
        private long sentAt;

        public Entity(BlockPos pos, BlockState state) {
            super(AudioFeature.DISC_READER_ENTITY.get(), pos, state);
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            String url = clock.running() ? url() : null;
            return url != null ? new Streams.Source(url, clock.position(), null) : null;
        }

        @Nullable
        private String url() {
            if (!disc.isEmpty()) {
                return MediaItem.url(disc.get());
            }
            DiscDrive drive = level == null ? null : DiscDrive.feeding(level, worldPosition, this);
            return drive == null ? null : drive.link();
        }

        @Override
        public boolean plays(DiscKind kind) {
            return !kind.hasPicture();
        }

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

        /** Speakers notice the restart on their next check. */
        @Override
        public void insert(ItemStack stack, @Nullable Player by) {
            disc.set(stack);
            if (url() != null) {
                play(by);
            } else {
                clock.stop();
            }
            powered = level != null && level.hasNeighborSignal(worldPosition);
            changed();
        }

        private void play(@Nullable Player by) {
            clock.start(0);
            if (level != null) {
                Streams.loading(this, by, clock, level.getGameTime());
            }
        }

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
            changed();
        }

        void tick() {
            if (!clock.running()) {
                return;
            }
            long now = level.getGameTime();
            long before = clock.position();
            Streams.tick(this, clock, now);
            advance(now);
            setChanged();
            boolean counts = clock.running() && clock.position() > before;
            if (counts != counting) {
                counting = counts;
                changed();
            }
        }

        @Override
        public void tickCaptured(long now) {
            clock.tick(now);
            advance(now);
        }

        private void advance(long now) {
            String url = url();
            if (url != null && clock.ended(url, now)) {
                clock.stop();
                counting = false;
                changed();
            }
        }

        /** Saved, and sent to the clients watching it, for its goggle tooltip. */
        private void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            String url = url();
            boolean playing = clock.running() && url != null;
            tooltip.add(Component.literal("     ").append(Component.translatable(url == null ? "create_media_systems.video_player.empty"
                    : playing ? "create_media_systems.video_player.playing" : "create_media_systems.video_player.stopped"))
                    .withStyle(url == null ? ChatFormatting.GRAY : playing ? ChatFormatting.GREEN : ChatFormatting.RED));
            DriveEmulatorBlock.Entity drive = disc.isEmpty() ? DriveEmulatorBlock.beside(level, worldPosition) : null;
            if (drive != null) {
                drive.describe(tooltip);
            } else if (url != null && !disc.isEmpty()) {
                tooltip.add(Component.literal("     ").append(disc.get().getHoverName()).withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     " + (url.length() > 40 ? url.substring(0, 39) + "…" : url))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            if (playing) {
                long ticks = clock.position() + (counting && level != null ? Math.max(0, level.getGameTime() - sentAt) : 0);
                tooltip.add(Component.literal("     ").append(counting
                        ? Component.translatable("create_media_systems.video_player.position",
                                Component.literal(PlaybackClock.format(ticks / (double) PlaybackClock.TICKS_PER_SECOND))
                                        .withStyle(ChatFormatting.WHITE))
                        : Component.translatable("create_media_systems.video_player.loading").withStyle(ChatFormatting.YELLOW))
                        .withStyle(ChatFormatting.GRAY));
            }
            return true;
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
            counting = tag.getBoolean("Counting");
            sentAt = tag.getLong("SentAt");
        }

        /** Its position as of now, and whether it counts on from there (see counting). */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveWithoutMetadata(registries);
            long now = level == null ? 0 : level.getGameTime();
            tag.putLong("Ticks", clock.position(now));
            tag.putBoolean("Counting", counting);
            tag.putLong("SentAt", now);
            return tag;
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}
