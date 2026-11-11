package org.haruyq.createmediasystems.train;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.client.TextPromptScreen;

import com.simibubi.create.Create;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Channels of train detectors, and which are on right now. A detector is a Create track observer, and Create keeps
 * each train's occupied observers up to date in its rail-network simulation even where chunks are unloaded, so
 * a channel is on while any train is over any of its detectors, whatever the distance. The channel names live
 * in world data (not in the detector blocks), so this keeps working while a detector's chunk is unloaded;
 * receivers read it when they tick, so an unloaded receiver catches up as soon as it loads.
 */
public class TrainSignals extends SavedData {
    public static final int MAX_CHANNEL_LENGTH = 32;
    private static final Factory<TrainSignals> FACTORY = new Factory<>(TrainSignals::new, TrainSignals::load);

    private final Map<UUID, String> channels = new HashMap<>();
    /** Recomputed every server tick; not saved. */
    private Set<String> active = Set.of();

    public static TrainSignals get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, CreateMediaSystems.MOD_ID + "_train_detectors");
    }

    public static void promptChannel(BlockPos pos, String channel) {
        TextPromptScreen.open("create_media_systems.screen.channel", channel, MAX_CHANNEL_LENGTH,
                value -> PacketDistributor.sendToServer(new SetChannel(pos, value)));
    }

    public static String normalize(String channel) {
        String trimmed = channel.strip();
        return trimmed.length() <= MAX_CHANNEL_LENGTH && trimmed.codePoints().noneMatch(Character::isISOControl)
                ? trimmed : "";
    }

    public boolean isActive(String channel) {
        return !channel.isEmpty() && active.contains(channel);
    }

    void setChannel(UUID observer, String channel) {
        if (!channel.equals(channels.put(observer, channel))) {
            setDirty();
        }
    }

    void remove(UUID observer) {
        if (channels.remove(observer) != null) {
            setDirty();
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        TrainSignals signals = get(event.getServer());
        signals.active = activeChannels(signals.channels,
                Create.RAILWAYS.trains.values().stream().map(train -> train.occupiedObservers).toList());
    }

    static Set<String> activeChannels(Map<UUID, String> channels, Iterable<? extends Set<UUID>> occupiedByTrain) {
        Set<String> on = new HashSet<>();
        for (Set<UUID> occupied : occupiedByTrain) {
            for (UUID observer : occupied) {
                String channel = channels.get(observer);
                if (channel != null && !channel.isEmpty()) {
                    on.add(channel);
                }
            }
        }
        return on;
    }

    private static TrainSignals load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainSignals signals = new TrainSignals();
        for (Tag entry : tag.getList("Detectors", Tag.TAG_COMPOUND)) {
            CompoundTag detector = (CompoundTag) entry;
            if (detector.hasUUID("Observer")) {
                signals.channels.put(detector.getUUID("Observer"), normalize(detector.getString("Channel")));
            }
        }
        return signals;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag detectors = new ListTag();
        channels.forEach((observer, channel) -> {
            CompoundTag detector = new CompoundTag();
            detector.putUUID("Observer", observer);
            detector.putString("Channel", channel);
            detectors.add(detector);
        });
        tag.put("Detectors", detectors);
        return tag;
    }

    /** Client → server: set the channel of the train detector or receiver at {@code pos}. */
    public record SetChannel(BlockPos pos, String channel) implements CustomPacketPayload {
        public static final Type<SetChannel> TYPE = new Type<>(CreateMediaSystems.id("set_channel"));
        public static final StreamCodec<ByteBuf, SetChannel> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetChannel::pos,
                ByteBufCodecs.stringUtf8(TrainSignals.MAX_CHANNEL_LENGTH * 4), SetChannel::channel,
                SetChannel::new);

        @Override
        public Type<SetChannel> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetChannel.TYPE, SetChannel.CODEC, (payload, context) -> {
            ChannelHolder holder = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND, ChannelHolder.class);
            if (holder != null) {
                holder.setChannel(payload.channel());
            }
        });
    }
}
