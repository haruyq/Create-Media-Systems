package org.haruyq.createmediasystems.stream;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/** Keeps links to the same source when Sable moves or reloads its block entity. */
public final class SourceLinks extends SavedData {
    /** Farthest a speaker or display may be from its source. */
    public static final int MAX_LINK_DISTANCE = 128;

    private final Map<UUID, BlockPos> positions = new HashMap<>();

    /**
     * Whether {@code player} may link the speaker or display at {@code emitter} to the {@code kind} of source at
     * {@code source}; tells them if it is too far.
     */
    public static boolean linkable(ServerPlayer player, BlockPos source, BlockPos emitter, Class<?> kind) {
        if (!player.level().isLoaded(source) || !kind.isInstance(player.level().getBlockEntity(source))) {
            return false;
        }
        if (SableCompat.distance(player.level(), source, emitter) < MAX_LINK_DISTANCE) {
            return true;
        }
        player.displayClientMessage(Component.translatable("create_media_systems.speaker.too_far", MAX_LINK_DISTANCE)
                .withStyle(ChatFormatting.RED), true);
        return false;
    }

    /** A block entity's link to a source, saved with it, that follows the source wherever Sable moves it. */
    public static final class Link {
        @Nullable
        private BlockPos pos;
        @Nullable
        private UUID id;

        /**
         * Where the source is now, as {@code level} has it; on a server, this follows the source, and calls
         * {@code changed} when it moved or was replaced, so the owner saves (and resends) it.
         */
        public Optional<BlockPos> get(@Nullable Level level, Runnable changed) {
            if (level instanceof ServerLevel) {
                if (id == null) {
                    id = identify(level, pos);
                    if (id != null) {
                        changed.run();
                    }
                }
                BlockPos resolved = resolve(level, id, pos);
                if (!Objects.equals(pos, resolved)) {
                    pos = resolved;
                    changed.run();
                }
            }
            return Optional.ofNullable(pos);
        }

        public void set(@Nullable Level level, @Nullable BlockPos pos) {
            this.pos = pos;
            id = level == null ? null : identify(level, pos);
        }

        /** Saves where the source is now, as {@code level} has it (see get). */
        public void save(@Nullable Level level, CompoundTag tag) {
            get(level, () -> {
            });
            if (pos != null) {
                tag.put("Source", NbtUtils.writeBlockPos(pos));
            }
            if (id != null) {
                tag.putUUID("SourceId", id);
            }
        }

        public void load(CompoundTag tag) {
            pos = NbtUtils.readBlockPos(tag, "Source").orElse(null);
            id = tag.hasUUID("SourceId") ? tag.getUUID("SourceId") : null;
        }
    }

    private static SourceLinks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(SourceLinks::new, SourceLinks::load), "create_media_systems_sources");
    }

    @Nullable
    public static UUID identify(Level level, @Nullable BlockPos pos) {
        if (!(level instanceof ServerLevel server) || pos == null || !level.isLoaded(pos)) {
            return null;
        }
        if (!(level.getBlockEntity(pos) instanceof MediaSource media)) {
            return null;
        }
        BlockEntity source = (BlockEntity) media;
        UUID id = source.getData(CreateMediaSystems.SOURCE_ID);
        source.setChanged();
        get(server).put(id, pos);
        return id;
    }

    public static void loaded(BlockEntity source) {
        if (source.getLevel() instanceof ServerLevel level && source.hasData(CreateMediaSystems.SOURCE_ID)) {
            get(level).put(source.getData(CreateMediaSystems.SOURCE_ID), source.getBlockPos());
        }
    }

    @Nullable
    public static BlockPos resolve(Level level, @Nullable UUID id, @Nullable BlockPos fallback) {
        if (!(level instanceof ServerLevel server) || id == null) {
            return fallback;
        }
        BlockPos pos = get(server).position(id);
        if (pos == null) {
            pos = fallback;
        }
        if (pos != null && level.isLoaded(pos)) {
            BlockEntity source = level.getBlockEntity(pos);
            if (source == null || !source.hasData(CreateMediaSystems.SOURCE_ID) || !id.equals(source.getData(CreateMediaSystems.SOURCE_ID))) {
                return null;
            }
        }
        return pos;
    }

    void put(UUID id, BlockPos pos) {
        if (!pos.equals(positions.put(id, pos.immutable()))) {
            setDirty();
        }
    }

    @Nullable
    BlockPos position(UUID id) {
        return positions.get(id);
    }

    static SourceLinks load(CompoundTag tag, HolderLookup.Provider registries) {
        SourceLinks links = new SourceLinks();
        for (Tag entry : tag.getList("Sources", Tag.TAG_COMPOUND)) {
            CompoundTag source = (CompoundTag) entry;
            if (source.hasUUID("Id")) {
                NbtUtils.readBlockPos(source, "Pos").ifPresent(pos -> links.positions.put(source.getUUID("Id"), pos));
            }
        }
        return links;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag sources = new ListTag();
        positions.forEach((id, pos) -> {
            CompoundTag source = new CompoundTag();
            source.putUUID("Id", id);
            source.put("Pos", NbtUtils.writeBlockPos(pos));
            sources.add(source);
        });
        tag.put("Sources", sources);
        return tag;
    }
}
