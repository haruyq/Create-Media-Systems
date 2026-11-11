package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import org.haruyq.createmediasystems.SableCompat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far an emitter is heard and how loud: within {@code area} (kept relative to the emitter, so it goes wherever the
 * emitter does: onto a Sable structure, a Create contraption, a copied schematic), or {@link #DEFAULT_RANGE} blocks
 * without one, like a vanilla disc; at {@code volume} (0..1) beside it, falling off to silence at the range's edge.
 * The server and every client decide who hears an emitter by the same {@link #hears} (see Streams and ClientStreams).
 */
public record EmitterRange(Optional<BoundingBox> area, float volume) {
    public static final double DEFAULT_RANGE = 64;
    /** Listeners are dropped this far outside the range; the gap keeps the edge from flapping. */
    public static final double DROP_MARGIN = 8;
    public static final EmitterRange DEFAULT = new EmitterRange(Optional.empty(), 1);

    public static final Codec<EmitterRange> CODEC = RecordCodecBuilder.create(i -> i.group(
            BoundingBox.CODEC.optionalFieldOf("offset_area").forGetter(EmitterRange::area),
            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(EmitterRange::volume)
    ).apply(i, EmitterRange::new));
    public static final StreamCodec<ByteBuf, EmitterRange> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public EmitterRange {
        volume = Mth.clamp(volume, 0, 1);
    }

    /** The default range at {@code volume}. */
    public static EmitterRange at(float volume) {
        return new EmitterRange(Optional.empty(), volume);
    }

    /** The area around the emitter at {@code emitter}, in its block space; empty with the default range. */
    public Optional<AABB> areaAt(BlockPos emitter) {
        return area.map(box -> AABB.of(box).move(Vec3.atLowerCornerOf(emitter)));
    }

    /**
     * Whether a listener at {@code listener} (in the world) hears the emitter at {@code emitter}, sounding from
     * {@code origin} in its block space, with the range grown by {@code margin}: as Sable has the emitter now.
     */
    public boolean hears(Level level, BlockPos emitter, Vec3 origin, Vec3 listener, double margin) {
        return inRange(emitter, origin, SableCompat.toBlockSpace(level, emitter, listener), margin);
    }

    /** {@link #hears}, with {@code listener} already in the emitter's block space. */
    public boolean inRange(BlockPos emitter, Vec3 origin, Vec3 listener, double margin) {
        return areaAt(emitter).map(box -> box.inflate(margin).contains(listener))
                .orElseGet(() -> listener.distanceTo(origin) <= DEFAULT_RANGE + margin);
    }

    /** Linear falloff from {@code volume} at {@code origin} to silence at the farthest point of the range. */
    public float gain(BlockPos emitter, Vec3 origin, Vec3 listener) {
        double reach = areaAt(emitter).map(box -> farthestCorner(origin, box)).orElse(DEFAULT_RANGE);
        return volume * (float) Mth.clamp(1 - listener.distanceTo(origin) / Math.max(reach, 1), 0, 1);
    }

    private static double farthestCorner(Vec3 center, AABB box) {
        double dx = Math.max(Math.abs(box.minX - center.x), Math.abs(box.maxX - center.x));
        double dy = Math.max(Math.abs(box.minY - center.y), Math.abs(box.maxY - center.y));
        double dz = Math.max(Math.abs(box.minZ - center.z), Math.abs(box.maxZ - center.z));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
