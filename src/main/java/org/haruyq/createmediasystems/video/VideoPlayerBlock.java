package org.haruyq.createmediasystems.video;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.haruyq.createmediasystems.PercentSetting;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.stream.DiscDrive;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.Downloads;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.VideoSource;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Plays a disc's playlist for its linked displays and speakers, one video after another, and stops after the last. A
 * remote steps through it (see RemoteItem); its volume (on top) and how brightly its displays light their surroundings
 * (in front) are set like any Create value, by holding right-click on it with a wrench. Right-click with a disc to put it in, with an empty hand to take it out.
 * With no disc of its own, the DVD or Blu-ray in a disc drive emulator beside it is its disc (see DriveEmulatorBlock).
 */
public class VideoPlayerBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<VideoPlayerBlock> CODEC = simpleCodec(VideoPlayerBlock::new);

    public VideoPlayerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<VideoPlayerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!MediaItem.fits(stack, DiscKind.Slot.PICTURE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity videoPlayer && videoPlayer.cd.isEmpty()) {
            videoPlayer.insert(stack.split(1), player);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity videoPlayer && !videoPlayer.cd.isEmpty()) {
            player.getInventory().placeItemBackInInventory(videoPlayer.cd);
            videoPlayer.insert(ItemStack.EMPTY, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Sable assembling a ship removes it too: data/sable/tags/block/silent_assembly_removal.json keeps that from dropping it.
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity videoPlayer) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), videoPlayer.cd);
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
        // Both sides: Create's value box lives on the client too.
        return type != VideoFeature.VIDEO_PLAYER_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** The Blu-ray player's volume: a block entity keeps one behaviour per type, and lighting has the default one. */
    private static final BehaviourType<ScrollValueBehaviour> VOLUME = new BehaviourType<>("create_media_systems_volume");

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation, VideoSource, Remotable,
            DiscDrive.Fed {
        private ItemStack cd = ItemStack.EMPTY;
        /** The playlist entry playing (or paused, or stopped at). */
        private int index;
        /** Ticks since the entry was started or sought; Streams counts on from {@link #from} with them (see played). */
        private long ticks;
        /** Where (ticks in) it was last sought to; 0 from the top. */
        private long from;
        private boolean playing;
        private boolean paused;
        private ScrollValueBehaviour volume;
        private ScrollValueBehaviour lighting;
        /** How much of the video playing its listeners have downloaded. Not saved. */
        private final Downloads downloads = new Downloads();

        public Entity(BlockPos pos, BlockState state) {
            super(VideoFeature.VIDEO_PLAYER_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            volume = PercentSetting.create(this, "create_media_systems.video_player.volume",
                    new CenteredSideValueBoxTransform((state, side) -> side == Direction.UP), VOLUME, "Volume");
            lighting = PercentSetting.create(this, "create_media_systems.video_player.lighting",
                    new CenteredSideValueBoxTransform((state, side) -> side == state.getValue(FACING)));
            volume.withCallback(percent -> changed());
            lighting.withCallback(percent -> changed());
            behaviours.add(volume);
            behaviours.add(lighting);
        }

        /** The drive whose disc it plays: one beside it with a disc that has a picture, while it has no disc of its own. */
        @Nullable
        private DiscDrive drive() {
            return cd.isEmpty() ? DiscDrive.feeding(level, worldPosition, this) : null;
        }

        @Override
        public List<String> playlist() {
            DiscDrive drive = drive();
            return drive != null ? List.of(drive.link()) : MediaItem.linksOf(cd);
        }

        @Override
        public boolean plays(DiscKind kind) {
            return kind.fits(DiscKind.Slot.PICTURE);
        }

        /** Plays the disc of the drive beside it from the top, or stops with it, unless it has its own. */
        @Override
        public void driveChanged(@Nullable Player by) {
            if (cd.isEmpty()) {
                insert(ItemStack.EMPTY, by);
            }
        }

        @Override
        public int index() {
            return index;
        }

        /** Its remote or wrench turns all of its speakers and displays up or down. */
        @Override
        public float volume() {
            return volume == null ? 1 : volume.getValue() / 100f;
        }

        /** How brightly its displays light their surroundings, 0..1 (0: not at all); the same for everyone. */
        @Override
        public float lighting() {
            return lighting == null ? 1 : lighting.getValue() / 100f;
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            if (!playing || paused) {
                return null;
            }
            List<String> playlist = playlist();
            DiscDrive drive = drive();
            DiscKind kind = drive != null ? drive.ready() : MediaItem.kindOf(cd);
            return index < playlist.size() && kind != null
                    ? new Streams.Source(playlist.get(index), counted(), new Streams.Video(worldPosition, kind.pictureHeight,
                            index + 1 < playlist.size() ? Optional.of(playlist.get(index + 1)) : Optional.empty()))
                    : null;
        }

        /**
         * Its ticks as Streams counts on from them (see played): since it was started or sought, which its load adds
         * {@link #from} to; with no load (the server restarted since), where it is.
         */
        private long counted() {
            return Streams.loadId(this) == 0 ? from + ticks : ticks;
        }

        /** A new disc put in by {@code by} plays its first video; with none, the disc of a drive beside it does. */
        public void insert(ItemStack stack, @Nullable Player by) {
            cd = stack;
            if (playlist().isEmpty()) {
                playing = false;
                changed();
            } else {
                play(0, by);
            }
        }

        /**
         * Plays entry {@code entry} from the top, if the playlist has it, as {@code by} asked (null: the one before
         * ended); its displays and speakers start over.
         */
        private void play(int entry, @Nullable Player by) {
            if (entry < 0 || entry >= playlist().size()) {
                return;
            }
            index = entry;
            ticks = 0;
            from = 0;
            playing = true;
            paused = false;
            downloads.clear();
            Streams.loading(this, by);
            changed();
        }

        /** {@code action} from {@code by}'s remote; {@code value} is the entry (from 1) to select, or the volume in percent. */
        @Override
        public void control(Action action, int value, @Nullable Player by) {
            switch (action) {
                case PLAY_PAUSE -> {
                    if (!playing) {
                        play(index, by);
                    } else {
                        paused = !paused;
                        changed();
                    }
                }
                case STOP -> {
                    playing = false;
                    paused = false;
                    ticks = 0;
                    from = 0;
                    changed();
                }
                case PREVIOUS -> play(Math.max(index - 1, 0), by);
                case NEXT -> play(index + 1, by);
                case SELECT -> play(value - 1, by);
                case VOLUME -> volume.setValue(Mth.clamp(value, 0, 100));
                case SEEK -> seek(value * 20L, by);
                case SEEK_BY -> seek(Streams.played(this, counted()) + value * 20L, by);
            }
        }

        /**
         * The entry playing (or paused) goes on from {@code to} ticks in: every listener loads it there and they start
         * together, as with a new entry (see Streams#loading).
         */
        private void seek(long to, @Nullable Player by) {
            List<String> playlist = playlist();
            if (!playing || index >= playlist.size()) {
                return;
            }
            from = Streams.seekable(playlist.get(index), to);
            ticks = 0;
            Streams.loading(this, by, from, false);
            changed();
        }

        @Override
        public void tick() {
            super.tick();
            if (level == null || level.isClientSide || !playing || paused) {
                return;
            }
            ticks++;
            setChanged();
            Streams.Source source = Streams.playing(this);
            if (ticks % Streams.RELAY_TICKS == 0 && source != null && Streams.endedAt(source)) {
                if (index + 1 < playlist().size()) {
                    play(index + 1, null);
                } else {
                    playing = false;
                    changed();
                }
            }
        }

        @Override
        public void downloaded(ServerPlayer player, String url, float fraction) {
            List<String> playlist = playlist();
            if (playing && index < playlist.size() && playlist.get(index).equals(url) && downloads.told(player, fraction)) {
                changed();
            }
        }

        /** Saved, and sent to clients for remotes and goggles. */
        private void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                sendData();
            }
        }

        /** Empty (no disc, or none with links), stopped, paused or playing, as its remote and goggles show it. */
        @Override
        public float remoteVolume() {
            return volume();
        }

        @Override
        public Component state() {
            return Component.translatable(playlist().isEmpty() ? "create_media_systems.video_player.empty"
                    : !playing ? "create_media_systems.video_player.stopped"
                    : paused ? "create_media_systems.video_player.paused" : "create_media_systems.video_player.playing");
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            List<String> playlist = playlist();
            tooltip.add(Component.literal("     ").append(state()).withStyle(playlist.isEmpty() ? ChatFormatting.GRAY
                    : !playing ? ChatFormatting.RED : paused ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
            DriveEmulatorBlock.Entity beside = cd.isEmpty() ? DriveEmulatorBlock.beside(level, worldPosition) : null;
            if (beside != null) {
                beside.describe(tooltip);
                tooltip.add(Component.empty());
            } else if (!playlist.isEmpty()) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.track",
                        Math.min(index, playlist.size() - 1) + 1, playlist.size())).withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     " + shorten(playlist.get(Math.min(index, playlist.size() - 1))))
                        .withStyle(ChatFormatting.DARK_GRAY));
                tooltip.add(Component.empty());
            }
            if (playing && !paused && level != null && level.isClientSide) {
                NativeAudio.Progress progress = NativeAudio.progress(worldPosition);
                if (progress != null) {
                    tooltip.add(Component.literal("     ").append(progress.loading()
                            ? Component.translatable("create_media_systems.video_player.loading").withStyle(ChatFormatting.YELLOW)
                            : Component.translatable("create_media_systems.video_player.position", value(clock(progress.seconds())
                            + (progress.length() > 0 ? " / " + clock(progress.length()) : ""), ChatFormatting.WHITE)))
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            if (playing) {
                downloads.addTo(tooltip, "     ");
            }
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.volume_value",
                    value(volume.getValue() + "%", ChatFormatting.AQUA))).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.lighting_value",
                    value(lighting.getValue() + "%", ChatFormatting.AQUA))).withStyle(ChatFormatting.GRAY));
            return true;
        }

        /** A value in a goggle line, set apart from its gray label. */
        private static Component value(Object value, ChatFormatting color) {
            return Component.literal(String.valueOf(value)).withStyle(color);
        }

        /** {@code seconds} as m:ss, or h:mm:ss from an hour. */
        static String clock(double seconds) {
            long s = (long) seconds;
            return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                    : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }

        private static String shorten(String url) {
            return url.length() > 40 ? url.substring(0, 39) + "…" : url;
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            if (!cd.isEmpty()) {
                tag.put("Cd", cd.save(registries));
            }
            tag.putInt("Index", index);
            tag.putLong("Ticks", ticks);
            tag.putLong("From", from);
            tag.putBoolean("Playing", playing);
            tag.putBoolean("Paused", paused);
            if (clientPacket) {
                downloads.write(tag);
            }
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            cd = ItemStack.parseOptional(registries, tag.getCompound("Cd"));
            index = tag.getInt("Index");
            ticks = tag.getLong("Ticks");
            from = tag.getLong("From");
            playing = tag.getBoolean("Playing");
            paused = tag.getBoolean("Paused");
            if (clientPacket) {
                downloads.read(tag);
            }
        }
    }
}
