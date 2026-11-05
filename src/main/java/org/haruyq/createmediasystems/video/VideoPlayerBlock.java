package org.haruyq.createmediasystems.video;

import java.util.List;
import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.PercentSetting;
import org.haruyq.createmediasystems.stream.MediaSource;
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
            videoPlayer.insert(stack.split(1));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity videoPlayer && !videoPlayer.cd.isEmpty()) {
            player.getInventory().placeItemBackInInventory(videoPlayer.cd);
            videoPlayer.insert(ItemStack.EMPTY);
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
        /** A Blu-ray player's displays stay quiet this long after a linked speaker last played it. */
        private static final long HELD_TICKS = 4 * Streams.RELAY_TICKS;

        private ItemStack cd = ItemStack.EMPTY;
        /** The playlist entry playing (or paused, or stopped at). */
        private int index;
        private long ticks;
        private boolean playing;
        private boolean paused;
        /** When a linked speaker last played it (game time); not saved. */
        private long heldAt = Long.MIN_VALUE / 2;
        private ScrollValueBehaviour volume;
        private ScrollValueBehaviour lighting;

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

        /**
         * A linked speaker plays it now, so its displays keep quiet (see HELD_TICKS); one that can't play it (host not
         * allowed) leaves them their sound.
         */
        @Override
        public void playedBySpeaker(long gameTime) {
            heldAt = gameTime;
        }

        /** Whether its displays keep quiet, speakers playing it. */
        public boolean silenced(long gameTime) {
            return gameTime - heldAt < HELD_TICKS;
        }

        /** A new disc (or none) plays its first video. */
        public void insert(ItemStack stack) {
            cd = stack;
            if (stack.isEmpty()) {
                playing = false;
                changed();
            } else {
                play(0);
            }
        }

        /** Plays entry {@code entry} from the top, if the playlist has it; its displays and speakers start over. */
        private void play(int entry) {
            if (entry < 0 || entry >= playlist().size()) {
                return;
            }
            index = entry;
            ticks = 0;
            playing = true;
            paused = false;
            Streams.loading(this);
            changed();
        }

        /** {@code action} from a remote; {@code value} is the entry (from 1) to select, or the volume in percent. */
        public void control(Action action, int value) {
            switch (action) {
                case PLAY_PAUSE -> {
                    if (!playing) {
                        play(index);
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
                case PREVIOUS -> play(Math.max(index - 1, 0));
                case NEXT -> play(index + 1);
                case SELECT -> play(value - 1);
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
                    play(index + 1);
                } else {
                    playing = false;
                    changed();
                }
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
            tooltip.add(Component.literal("    ").append(Component.translatable("block.create_media_systems.blu_ray_player")));
            List<String> playlist = playlist();
            tooltip.add(Component.literal("     ").append(state()).withStyle(ChatFormatting.GRAY));
            if (!playlist.isEmpty()) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.track",
                        Math.min(index, playlist.size() - 1) + 1, playlist.size())).withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     " + shorten(playlist.get(Math.min(index, playlist.size() - 1))))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.volume_value",
                    volume.getValue())).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.video_player.lighting_value",
                    lighting.getValue())).withStyle(ChatFormatting.GRAY));
            return true;
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
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            cd = ItemStack.parseOptional(registries, tag.getCompound("Cd"));
            index = tag.getInt("Index");
            ticks = tag.getLong("Ticks");
            playing = tag.getBoolean("Playing");
            paused = tag.getBoolean("Paused");
        }
    }
}
