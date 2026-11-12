package org.haruyq.createmediasystems.broadcast;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.EmitterRange;

/** Remembers linked stations so a listener can hear them beyond simulation distance, even after a restart. */
final class BroadcastCoverage extends SavedData {
    private static final int RENEW_EVERY = 20;
    private static final int TICKET_DISTANCE = 1; // Block ticking in the station and source chunks.
    private static final TicketType<BlockPos> TICKET = TicketType.create("cms_broadcast",
            Comparator.comparingLong(BlockPos::asLong), 2 * RENEW_EVERY);
    private final Map<BlockPos, Station> stations = new HashMap<>();

    private record Station(BlockPos source, BroadcastProfile profile) {}

    private static BroadcastCoverage get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(BroadcastCoverage::new, BroadcastCoverage::load),
                CreateMediaSystems.MOD_ID + "_broadcasts");
    }

    static void watch(ServerLevel level, BlockPos tower, BlockPos source, BroadcastProfile profile) {
        var coverage = get(level);
        var station = new Station(source.immutable(), profile);
        if (!station.equals(coverage.stations.put(tower.immutable(), station))) coverage.setDirty();
    }

    static void remove(ServerLevel level, BlockPos tower) {
        var coverage = get(level);
        if (coverage.stations.remove(tower) != null) coverage.setDirty();
    }

    static void tick(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % RENEW_EVERY == 0
                && !level.players().isEmpty()) {
            get(level).renew(level);
        }
    }

    private void renew(ServerLevel level) {
        var entries = stations.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            BlockPos tower = entry.getKey();
            Station station = entry.getValue();
            if (!level.isInWorldBounds(tower) || !level.isInWorldBounds(station.source)
                    || level.isLoaded(tower) && !(level.getBlockState(tower).getBlock() instanceof BroadcastTowerBlock)) {
                entries.remove();
                setDirty();
                continue;
            }
            double reach = station.profile.reach + EmitterRange.DROP_MARGIN;
            if (level.players().stream().noneMatch(player -> player.distanceToSqr(tower.getCenter()) <= reach * reach)) continue;
            var chunks = level.getChunkSource();
            ChunkPos towerChunk = new ChunkPos(tower), sourceChunk = new ChunkPos(station.source);
            chunks.addRegionTicket(TICKET, towerChunk, TICKET_DISTANCE, tower, true);
            if (!sourceChunk.equals(towerChunk)) {
                chunks.addRegionTicket(TICKET, sourceChunk, TICKET_DISTANCE, tower, true);
            }
        }
    }

    private static BroadcastCoverage load(CompoundTag tag, HolderLookup.Provider registries) {
        var coverage = new BroadcastCoverage();
        for (Tag value : tag.getList("Stations", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            var profile = BroadcastProfile.byId(entry.getInt("Profile"));
            if (profile.outdoor() && entry.contains("Tower", Tag.TAG_LONG) && entry.contains("Source", Tag.TAG_LONG)) {
                coverage.stations.put(BlockPos.of(entry.getLong("Tower")), new Station(BlockPos.of(entry.getLong("Source")), profile));
            }
        }
        return coverage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        stations.forEach((tower, station) -> {
            var entry = new CompoundTag();
            entry.putLong("Tower", tower.asLong());
            entry.putLong("Source", station.source.asLong());
            entry.putInt("Profile", station.profile.id());
            entries.add(entry);
        });
        tag.put("Stations", entries);
        return tag;
    }
}
