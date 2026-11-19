package org.haruyq.createmediasystems.blocks.radio;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A dimension's radio stations, by their transmitter, and who hears each now. Every relay tick each player carrying a
 * receiver that is on gets the station of its frequency that reaches them strongest. A station with listeners keeps
 * the chunks of its transmitter, source and tower loaded, so it plays beyond simulation distance; the stations are
 * saved, so it does after a restart too.
 */
public final class RadioStations extends SavedData {
    /** The share of a station's reach, at its edge, over which its signal fades to nothing. */
    private static final double EDGE = .15;
    private static final int TICKET_DISTANCE = 1; // Block ticking in the chunk.
    private static final TicketType<BlockPos> TICKET = TicketType.create("cms_radio",
            Comparator.comparingLong(BlockPos::asLong), 4 * Streams.RELAY_TICKS);

    final Map<BlockPos, Station> stations = new HashMap<>();
    /** Each station's listeners and how strong (0..1] they receive it, as of the last relay tick. */
    private final Map<BlockPos, Map<ServerPlayer, Float>> audiences = new HashMap<>();

    /**
     * A transmitter on air: its frequency (tenths of a MHz), its tower's antenna, how far that reaches (blocks), and
     * the source it is linked to, if any.
     */
    public record Station(int frequency, BlockPos antenna, int reach, @Nullable BlockPos source) {
        /** How strong a receiver at {@code listener} gets it: 1 near, fading over the edge of its reach, 0 beyond. */
        public float signal(Vec3 antennaAt, Vec3 listener) {
            return (float) Math.clamp((reach - antennaAt.distanceTo(listener)) / (reach * EDGE), 0, 1);
        }
    }

    static RadioStations get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(RadioStations::new, RadioStations::load),
                CreateMediaSystems.MOD_ID + "_radio");
    }

    /** The transmitter at {@code transmitter} is on air as {@code station} now; off air for null. */
    static void update(ServerLevel level, BlockPos transmitter, @Nullable Station station) {
        RadioStations radio = get(level);
        Station before = station == null ? radio.stations.remove(transmitter) : radio.stations.put(transmitter.immutable(), station);
        if (station == null) {
            radio.audiences.remove(transmitter);
        }
        if (!Objects.equals(before, station)) {
            radio.setDirty();
        }
    }

    /** Who hears the transmitter at {@code transmitter} now, and how strong. */
    static Map<ServerPlayer, Float> audience(ServerLevel level, BlockPos transmitter) {
        return get(level).audiences.getOrDefault(transmitter, Map.of());
    }

    static void tick(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && Streams.relayDue(level)) {
            get(level).assign(level);
        }
    }

    private void assign(ServerLevel level) {
        audiences.clear();
        // A transmitter Sable took away, or one removed without its onRemove, is gone once its chunk shows it.
        if (stations.keySet().removeIf(pos -> level.isLoaded(pos)
                && !(level.getBlockState(pos).getBlock() instanceof RadioTransmitterBlock))) {
            setDirty();
        }
        for (ServerPlayer player : level.players()) {
            int frequency = LoudmanItem.tuned(player);
            if (frequency == 0) {
                continue;
            }
            Vec3 ear = SableCompat.eye(player, 1);
            BlockPos best = null;
            float strongest = 0;
            // ponytail: every receiver against every station, each relay tick; index stations by frequency if a
            // server ever runs hundreds of them.
            for (var entry : stations.entrySet()) {
                Station station = entry.getValue();
                float signal = station.frequency() != frequency ? 0
                        : station.signal(SableCompat.toWorld(level, station.antenna(), station.antenna().getCenter()), ear);
                if (signal > strongest) {
                    strongest = signal;
                    best = entry.getKey();
                }
            }
            if (best != null) {
                audiences.computeIfAbsent(best, pos -> new HashMap<>()).put(player, strongest);
            }
            // Also with nothing received, or a station that plays nothing: the receiver hisses by it.
            PacketDistributor.sendToPlayer(player, new RadioPackets.Signal(strongest));
        }
        for (BlockPos transmitter : audiences.keySet()) {
            Station station = stations.get(transmitter);
            keepLoaded(level, transmitter, transmitter);
            keepLoaded(level, station.antenna(), transmitter);
            if (station.source() != null) {
                keepLoaded(level, station.source(), transmitter);
            }
        }
    }

    private static void keepLoaded(ServerLevel level, BlockPos block, BlockPos owner) {
        if (level.isInWorldBounds(block) && !SableCompat.inPlotGrid(level, block)) {
            level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(block), TICKET_DISTANCE, owner, true);
        }
    }

    static RadioStations load(CompoundTag tag, HolderLookup.Provider registries) {
        RadioStations radio = new RadioStations();
        for (Tag value : tag.getList("Stations", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            radio.stations.put(BlockPos.of(entry.getLong("Transmitter")), new Station(
                    RadioFeature.clampFrequency(entry.getInt("Frequency")), BlockPos.of(entry.getLong("Antenna")),
                    entry.getInt("Reach"), entry.contains("Source") ? BlockPos.of(entry.getLong("Source")) : null));
        }
        return radio;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        stations.forEach((transmitter, station) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Transmitter", transmitter.asLong());
            entry.putInt("Frequency", station.frequency());
            entry.putLong("Antenna", station.antenna().asLong());
            entry.putInt("Reach", station.reach());
            if (station.source() != null) {
                entry.putLong("Source", station.source().asLong());
            }
            entries.add(entry);
        });
        tag.put("Stations", entries);
        return tag;
    }
}
