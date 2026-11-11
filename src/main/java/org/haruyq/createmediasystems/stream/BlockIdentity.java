package org.haruyq.createmediasystems.stream;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Block entities known by an id they keep when Sable moves them or their chunk reloads, and where each is now: the
 * registry behind {@link Link}.
 */
public final class BlockIdentity extends SavedData {
    private final Map<UUID, BlockPos> positions = new HashMap<>();

    private static BlockIdentity get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(BlockIdentity::new, BlockIdentity::load),
                CreateMediaSystems.MOD_ID + "_sources");
    }

    /** The id of the block entity at {@code pos}; null if there is none, or it was never given one. Changes nothing. */
    @Nullable
    public static UUID of(Level level, @Nullable BlockPos pos) {
        BlockEntity block = pos == null || !level.isLoaded(pos) ? null : level.getBlockEntity(pos);
        return block != null && block.hasData(StreamFeature.SOURCE_ID) ? block.getData(StreamFeature.SOURCE_ID) : null;
    }

    /**
     * The id of the block entity at {@code pos}, given one (saved with it) if it has none yet; null if there is none,
     * or off a server.
     */
    @Nullable
    static UUID claim(Level level, @Nullable BlockPos pos) {
        if (!(level instanceof ServerLevel server) || pos == null || !level.isLoaded(pos)) {
            return null;
        }
        BlockEntity block = level.getBlockEntity(pos);
        if (block == null) {
            return null;
        }
        boolean given = !block.hasData(StreamFeature.SOURCE_ID);
        UUID id = block.getData(StreamFeature.SOURCE_ID);
        if (given) {
            block.setChanged();
        }
        get(server).put(id, pos);
        return id;
    }

    /** A block entity with an id joined a server level, loaded or moved there by Sable. */
    public static void loaded(BlockEntity block) {
        if (block.getLevel() instanceof ServerLevel level && block.hasData(StreamFeature.SOURCE_ID)) {
            get(level).put(block.getData(StreamFeature.SOURCE_ID), block.getBlockPos());
        }
    }

    /**
     * Where the block entity {@code id} is now ({@code fallback} if that is not known, or off a server); null if what
     * is there now is another block.
     */
    @Nullable
    public static BlockPos find(Level level, @Nullable UUID id, @Nullable BlockPos fallback) {
        if (!(level instanceof ServerLevel server) || id == null) {
            return fallback;
        }
        BlockPos pos = get(server).position(id);
        if (pos == null) {
            pos = fallback;
        }
        if (pos != null && level.isLoaded(pos) && !id.equals(of(level, pos))) {
            return null;
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

    static BlockIdentity load(CompoundTag tag, HolderLookup.Provider registries) {
        BlockIdentity identities = new BlockIdentity();
        for (Tag entry : tag.getList("Sources", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) entry;
            if (block.hasUUID("Id")) {
                NbtUtils.readBlockPos(block, "Pos").ifPresent(pos -> identities.positions.put(block.getUUID("Id"), pos));
            }
        }
        return identities;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag blocks = new ListTag();
        positions.forEach((id, pos) -> {
            CompoundTag block = new CompoundTag();
            block.putUUID("Id", id);
            block.put("Pos", NbtUtils.writeBlockPos(pos));
            blocks.add(block);
        });
        tag.put("Sources", blocks);
        return tag;
    }
}
