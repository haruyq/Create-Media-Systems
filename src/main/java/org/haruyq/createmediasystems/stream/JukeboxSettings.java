package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Per-jukebox playback settings, set with the range quill and stored on the vanilla jukebox as an attachment.
 * Without an area the jukebox is heard within {@link #DEFAULT_RANGE} blocks, like a vanilla disc. The area is
 * kept relative to the jukebox, so it goes wherever the jukebox does: onto a Sable structure (whose blocks live in
 * a far-off plot), a Create contraption, a copied schematic.
 */
public record JukeboxSettings(Optional<BoundingBox> area, float volume) {
    public static final double DEFAULT_RANGE = 64;
    /** Listeners are dropped this far outside the range; the gap keeps the edge from flapping. */
    public static final double DROP_MARGIN = 8;
    /** Largest area side, so one jukebox can't make a whole region stream it. */
    public static final int MAX_SIZE = 256;
    public static final JukeboxSettings DEFAULT = new JukeboxSettings(Optional.empty(), 1);

    public static final Codec<JukeboxSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            BoundingBox.CODEC.optionalFieldOf("offset_area").forGetter(JukeboxSettings::area),
            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(JukeboxSettings::volume)
    ).apply(i, JukeboxSettings::new));
    public static final StreamCodec<ByteBuf, JukeboxSettings> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    /** The jukebox attachment; it also reads saves from before the area was relative, which had it in place. */
    public static final IAttachmentSerializer<Tag, JukeboxSettings> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public JukeboxSettings read(IAttachmentHolder holder, Tag tag, HolderLookup.Provider provider) {
            JukeboxSettings settings = CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(DEFAULT);
            if (settings.area.isEmpty() && tag instanceof CompoundTag saved && saved.contains("area")
                    && holder instanceof BlockEntity jukebox) {
                Optional<BoundingBox> placed = BoundingBox.CODEC.parse(NbtOps.INSTANCE, saved.get("area")).result();
                return new JukeboxSettings(placed.map(box -> relative(box, jukebox.getBlockPos())), settings.volume);
            }
            return settings;
        }

        @Override
        public Tag write(JukeboxSettings settings, HolderLookup.Provider provider) {
            return CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
        }
    };

    public JukeboxSettings {
        volume = Mth.clamp(volume, 0, 1);
    }

    /** {@code box}, given in place, as an area of the jukebox at {@code jukebox}. */
    public static BoundingBox relative(BoundingBox box, BlockPos jukebox) {
        return box.moved(-jukebox.getX(), -jukebox.getY(), -jukebox.getZ());
    }

    /** The area around the jukebox at {@code jukebox}, in its block space; empty with the default range. */
    public Optional<AABB> areaAt(BlockPos jukebox) {
        return area.map(box -> AABB.of(box).move(Vec3.atLowerCornerOf(jukebox)));
    }

    /**
     * Whether {@code listener} hears the jukebox at {@code jukebox} (sounding from {@code center}), with the range
     * grown by {@code margin}. Positions are in the jukebox's block space (see SableCompat).
     */
    public boolean inRange(BlockPos jukebox, Vec3 center, Vec3 listener, double margin) {
        return areaAt(jukebox).map(box -> box.inflate(margin).contains(listener))
                .orElseGet(() -> listener.distanceTo(center) <= DEFAULT_RANGE + margin);
    }

    /** Linear falloff from full volume at the jukebox to silence at the farthest point of the range. */
    public float gain(BlockPos jukebox, Vec3 center, Vec3 listener) {
        double reach = areaAt(jukebox).map(box -> farthestCorner(center, box)).orElse(DEFAULT_RANGE);
        return volume * (float) Mth.clamp(1 - listener.distanceTo(center) / Math.max(reach, 1), 0, 1);
    }

    private static double farthestCorner(Vec3 center, AABB box) {
        double dx = Math.max(Math.abs(box.minX - center.x), Math.abs(box.maxX - center.x));
        double dy = Math.max(Math.abs(box.minY - center.y), Math.abs(box.maxY - center.y));
        double dz = Math.max(Math.abs(box.minZ - center.z), Math.abs(box.maxZ - center.z));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
