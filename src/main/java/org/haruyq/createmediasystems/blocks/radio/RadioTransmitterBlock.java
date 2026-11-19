package org.haruyq.createmediasystems.blocks.radio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.blocks.broadcast.BroadcastProfile;
import org.haruyq.createmediasystems.client.TextPromptScreen;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.MixBus;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A radio station's transmitter: linked to a source like a speaker and, with a wrench, to a tower (see RadioLink). It
 * makes no sound where it stands; its source's track goes to the receivers tuned to its frequency within the tower's
 * reach (see RadioStations).
 */
public final class RadioTransmitterBlock extends SpeakerBlock {
    private static final MapCodec<RadioTransmitterBlock> CODEC = simpleCodec(RadioTransmitterBlock::new);

    public RadioTransmitterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<RadioTransmitterBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Transmitter(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != RadioFeature.TRANSMITTER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Transmitter) be).tick((ServerLevel) l);
    }

    /** An empty hand asks for its frequency. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide && level.getBlockEntity(pos) instanceof Transmitter transmitter) {
            TextPromptScreen.open("create_media_systems.screen.frequency", RadioFeature.mhz(transmitter.frequency()), 5, typed -> {
                try {
                    PacketDistributor.sendToServer(new RadioPackets.SetFrequency(pos, Math.round(Float.parseFloat(typed.trim()) * 10)));
                } catch (NumberFormatException e) {
                    player.displayClientMessage(Component.translatable("create_media_systems.radio.bad_frequency",
                            RadioFeature.mhz(RadioFeature.MIN_FREQUENCY), RadioFeature.mhz(RadioFeature.MAX_FREQUENCY))
                            .withStyle(ChatFormatting.RED), true);
                }
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static void setFrequency(ServerPlayer player, RadioPackets.SetFrequency payload) {
        Transmitter transmitter = Reach.blockEntity(player, payload.transmitter(), Reach.HAND, Transmitter.class);
        if (transmitter == null || !player.mayBuild() || !SableCompat.mayInteract(player.level(), player, payload.transmitter())) {
            return;
        }
        transmitter.frequency = RadioFeature.clampFrequency(payload.frequency());
        transmitter.changed(player.serverLevel());
        player.displayClientMessage(Component.translatable("create_media_systems.radio.frequency_set",
                RadioFeature.mhz(transmitter.frequency)), true);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server) {
            RadioStations.update(server, pos, null);
        }
        super.onRemove(state, level, pos, next, moving);
    }

    public static final class Transmitter extends SpeakerBlock.Entity implements IHaveGoggleInformation {
        private int frequency = RadioFeature.DEFAULT_FREQUENCY;
        /** A block of the tower it sends from (a mast or the antenna), as the wrench clicked it; null for none. */
        @Nullable
        private BlockPos tower;
        /** The tower's antenna and the masts under it, as last seen with its chunk loaded; null without an antenna. */
        @Nullable
        private BlockPos antenna;
        private int masts;
        /**
         * What it relays each source it plays as (a mixer feeds several). Not the block entity itself: that would count
         * as a speaker of the source, and a display's own sound gives way to speakers (see Emitter#givesWay).
         */
        private final Map<BlockPos, Object> relays = new HashMap<>();

        public Transmitter(BlockPos pos, BlockState state) {
            super(RadioFeature.TRANSMITTER_ENTITY.get(), pos, state);
        }

        public int frequency() {
            return frequency;
        }

        @Nullable
        public BlockPos antenna() {
            return antenna;
        }

        public int masts() {
            return masts;
        }

        /** Sends from the tower {@code part} is a block of from now on; whether that tower has an antenna. */
        boolean linkTower(ServerLevel level, BlockPos part) {
            tower = part.immutable();
            survey(level);
            changed(level);
            return antenna != null;
        }

        private void changed(ServerLevel level) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            relay(level);
        }

        /** Looks at its tower, if that is loaded: it may have grown, lost its antenna, or gone. */
        private void survey(ServerLevel level) {
            if (tower != null && !level.isLoaded(tower)) {
                return;
            }
            BlockPos found = tower == null ? null : RadioMastBlock.antenna(level, tower);
            int count = found == null ? 0 : RadioMastBlock.masts(level, found);
            if (!Objects.equals(found, antenna) || count != masts) {
                antenna = found;
                masts = count;
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public void relay(ServerLevel level) {
            followSource(level);
            survey(level);
            BlockPos link = source().orElse(null);
            RadioStations.update(level, worldPosition, antenna == null ? null
                    : new RadioStations.Station(frequency, antenna, RadioFeature.reach(masts), link));
            Map<ServerPlayer, Float> audience = RadioStations.audience(level, worldPosition);
            Set<BlockPos> playing = new HashSet<>();
            if (link != null && antenna != null && volume() > 0 && !audience.isEmpty()) {
                // Linked to a mixer, it sends each source the mixer feeds, at that feed's gain (see Streams#relay).
                List<MixBus.Feed> feeds = level.isLoaded(link) && level.getBlockEntity(link) instanceof MixBus bus
                        ? bus.feeds(level) : List.of(new MixBus.Feed(link, 1));
                for (MixBus.Feed feed : feeds) {
                    Streams.Source track = Streams.source(level, feed.source());
                    if (track == null || !playing.add(feed.source())) {
                        continue;
                    }
                    var target = new StreamPackets.Target.Radio(worldPosition, feed.source());
                    float seconds = track.ticks() / (float) PlaybackClock.TICKS_PER_SECOND;
                    Streams.relayMoving(level, relays.computeIfAbsent(feed.source(), source -> new Object()), target,
                            feed.source(), Streams.Channel.MIX, BroadcastProfile.STANDARD, audience.keySet(),
                            (source, load) -> new RadioPackets.Play(target, source.url(),
                                    source.ticks() / (float) PlaybackClock.TICKS_PER_SECOND, load));
                    float gain = volume() * feed.gain()
                            * (level.getBlockEntity(feed.source()) instanceof MediaSource media ? media.gain() : 1);
                    audience.keySet().forEach(listener -> PacketDistributor.sendToPlayer(listener,
                            new RadioPackets.Tune(target, seconds, gain)));
                }
            }
            relays.entrySet().removeIf(relay -> {
                if (playing.contains(relay.getKey())) {
                    return false;
                }
                Streams.stop(relay.getValue());
                return true;
            });
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            relays.values().forEach(Streams::stop);
            relays.clear();
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            goggles(tooltip, ChatFormatting.GRAY, "frequency", RadioFeature.mhz(frequency));
            if (antenna == null) {
                goggles(tooltip, ChatFormatting.RED, "no_tower");
            } else {
                goggles(tooltip, ChatFormatting.GRAY, "tower", masts);
                goggles(tooltip, ChatFormatting.GRAY, "range", RadioFeature.reach(masts));
            }
            if (source().isEmpty()) {
                goggles(tooltip, ChatFormatting.RED, "no_source");
            }
            return true;
        }

        private static void goggles(List<Component> tooltip, ChatFormatting color, String key, Object... args) {
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.radio.goggles." + key, args))
                    .withStyle(color));
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("Frequency", frequency);
            if (tower != null) {
                tag.putLong("Tower", tower.asLong());
            }
            if (antenna != null) {
                tag.putLong("Antenna", antenna.asLong());
                tag.putInt("Masts", masts);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            frequency = tag.contains("Frequency") ? RadioFeature.clampFrequency(tag.getInt("Frequency")) : RadioFeature.DEFAULT_FREQUENCY;
            tower = tag.contains("Tower") ? BlockPos.of(tag.getLong("Tower")) : null;
            antenna = tag.contains("Antenna") ? BlockPos.of(tag.getLong("Antenna")) : null;
            masts = tag.getInt("Masts");
        }
    }
}
