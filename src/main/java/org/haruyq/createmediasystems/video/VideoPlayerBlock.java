package org.haruyq.createmediasystems.video;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.PercentSetting;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.StreamPackets;
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
import net.minecraft.server.level.ServerLevel;
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
        if (!(stack.getItem() instanceof VideoDiscItem)) {
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
        return type != CreateMediaSystems.VIDEO_PLAYER_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** The Blu-ray player's volume: a block entity keeps one behaviour per type, and lighting has the default one. */
    private static final BehaviourType<ScrollValueBehaviour> VOLUME = new BehaviourType<>("create_media_systems_volume");


    /** What a remote asks of a Blu-ray player (see Entity#control). */
    public enum Action {
        PLAY_PAUSE, STOP, PREVIOUS, NEXT, SELECT, VOLUME;

        public static Action byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation, MediaSource {
        private ItemStack cd = ItemStack.EMPTY;
        /** The playlist entry playing (or paused, or stopped at). */
        private int index;
        private long ticks;
        private boolean playing;
        private boolean paused;
        private ScrollValueBehaviour volume;
        private ScrollValueBehaviour lighting;
        /** How much of the video playing each listener has kept, 0..1, as they told (see ClientStreams#reportDownloads). */
        private final Map<ServerPlayer, Float> downloads = new HashMap<>();
        /** Their average, in percent, as goggles show it; -1 while none has told. Not saved. */
        private int downloaded = -1;
        /** How many it is the average of, and how many of them have all of it. */
        private int downloaders;
        private int finished;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.VIDEO_PLAYER_ENTITY.get(), pos, state);
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

        public List<String> playlist() {
            return cd.getOrDefault(CreateMediaSystems.PLAYLIST.get(), List.of());
        }

        public int index() {
            return index;
        }

        /** Its remote or wrench turns all of its speakers and displays up or down. */
        @Override
        public float volume() {
            return volume == null ? 1 : volume.getValue() / 100f;
        }

        /** How brightly its displays light their surroundings, 0..1 (0: not at all); the same for everyone. */
        public float lighting() {
            return lighting == null ? 1 : lighting.getValue() / 100f;
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            List<String> playlist = playlist();
            return playing && !paused && index < playlist.size()
                    ? new Streams.Source(playlist.get(index), ticks, new Streams.Video(
                            worldPosition, ((VideoDiscItem) cd.getItem()).maxHeight,
                            index + 1 < playlist.size() ? Optional.of(playlist.get(index + 1)) : Optional.empty()))
                    : null;
        }

        /** A new disc (or none) put in by {@code by} plays its first video. */
        public void insert(ItemStack stack, @Nullable Player by) {
            cd = stack;
            if (stack.isEmpty()) {
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
            playing = true;
            paused = false;
            downloads.clear();
            downloaded = -1;
            downloaders = 0;
            finished = 0;
            Streams.loading(this, by);
            changed();
        }

        /** {@code action} from {@code by}'s remote; {@code value} is the entry (from 1) to select, or the volume in percent. */
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
                    changed();
                }
                case PREVIOUS -> play(Math.max(index - 1, 0), by);
                case NEXT -> play(index + 1, by);
                case SELECT -> play(value - 1, by);
                case VOLUME -> volume.setValue(Mth.clamp(value, 0, 100));
            }
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

        /**
         * {@code player} has {@code payload.downloaded()} of the video the Blu-ray player at {@code payload.pos()}
         * plays kept: counted while they listen to it and it still plays that video.
         */
        public static void downloaded(ServerPlayer player, StreamPackets.DownloadedAtBlock payload) {
            ServerLevel level = player.serverLevel();
            if (level.isLoaded(payload.pos()) && level.getBlockEntity(payload.pos()) instanceof Entity videoPlayer
                    && Streams.listeners(level, payload.pos()).contains(player)) {
                videoPlayer.downloaded(player, payload.url(), payload.downloaded());
            }
        }

        void downloaded(ServerPlayer player, String url, float fraction) {
            List<String> playlist = playlist();
            if (!playing || index >= playlist.size() || !playlist.get(index).equals(url)) {
                return;
            }
            downloads.put(player, Mth.clamp(fraction, 0, 1));
            downloads.keySet().removeIf(ServerPlayer::isRemoved);
            int average = (int) (downloads.values().stream().mapToDouble(Float::doubleValue).average().orElse(0) * 100);
            int done = (int) downloads.values().stream().filter(each -> each >= 1).count();
            if (average != downloaded || downloads.size() != downloaders || done != finished) {
                downloaded = average;
                downloaders = downloads.size();
                finished = done;
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
        public Component state() {
            return Component.translatable(playlist().isEmpty() ? "create_media_systems.video_player.empty"
                    : !playing ? "create_media_systems.video_player.stopped"
                    : paused ? "create_media_systems.video_player.paused" : "create_media_systems.video_player.playing");
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            List<String> playlist = playlist();
            tooltip.add(Component.literal("     ").append(state()).withStyle(ChatFormatting.GRAY));
            if (!playlist.isEmpty()) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.track",
                        Math.min(index, playlist.size() - 1) + 1, playlist.size())).withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     " + shorten(playlist.get(Math.min(index, playlist.size() - 1))))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            if (playing && !paused && level != null && level.isClientSide) {
                NativeAudio.Progress progress = NativeAudio.progress(worldPosition);
                if (progress != null) {
                    tooltip.add(Component.literal("     ").append(progress.loading()
                            ? Component.translatable("create_media_systems.video_player.loading")
                            : Component.translatable("create_media_systems.video_player.position", clock(progress.seconds())
                            + (progress.length() > 0 ? " / " + clock(progress.length()) : ""))).withStyle(ChatFormatting.GRAY));
                }
            }
            if (playing && downloaded >= 0) {
                // Several listeners: their average, then how many of them have all of it.
                tooltip.add(Component.literal("     ")
                        .append(Component.translatable("create_media_systems.video_player.downloaded", downloaded))
                        .append(downloaders > 1 ? "  " + finished + "/" + downloaders : "")
                        .withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.volume_value",
                    volume.getValue())).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.lighting_value",
                    lighting.getValue())).withStyle(ChatFormatting.GRAY));
            return true;
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
            tag.putBoolean("Playing", playing);
            tag.putBoolean("Paused", paused);
            if (clientPacket) {
                tag.putInt("Downloaded", downloaded);
                tag.putInt("Downloaders", downloaders);
                tag.putInt("Finished", finished);
            }
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            cd = ItemStack.parseOptional(registries, tag.getCompound("Cd"));
            index = tag.getInt("Index");
            ticks = tag.getLong("Ticks");
            playing = tag.getBoolean("Playing");
            paused = tag.getBoolean("Paused");
            if (clientPacket) {
                downloaded = tag.getInt("Downloaded");
                downloaders = tag.getInt("Downloaders");
                finished = tag.getInt("Finished");
            }
        }
    }
}
