package org.haruyq.createmediasystems.blocks.soundproof;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * How much of a sound gets from where it is made to a listener past what soundproofs: the blocks of the tag
 * {@link #BLOCKS}, where the straight line between the two meets their shape (so not an open door, nor a board on the
 * wall the listener leans on), and full blocks with an insulation sheet in them (see {@link #sheeted}).
 */
public final class Soundproofing {
    public static final TagKey<Block> BLOCKS = TagKey.create(Registries.BLOCK, CreateMediaSystems.id("soundproof"));
    /**
     * What one layer lets through (about -12 dB); each further layer on the line does again.
     * ponytail: one factor for every layer, a door as a board. A factor per block (a data map) if they should differ.
     */
    public static final double LAYER = 0.25;

    static final Codec<Set<BlockPos>> SHEETS_CODEC = BlockPos.CODEC.listOf().xmap(Set::copyOf, List::copyOf);
    static final StreamCodec<ByteBuf, Set<BlockPos>> SHEETS_STREAM_CODEC =
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.<ByteBuf, BlockPos, Set<BlockPos>>collection(HashSet::new));

    private Soundproofing() {}

    /**
     * The part (0..1) of a sound made at {@code from} that is heard at {@code to}. A sheet in what makes the sound
     * does not count, so that a speaker set into a sheeted wall is heard: that is the first block on the line, which
     * starts a hair behind {@code from}, as a speaker's sound starts on its face.
     */
    public static double transmission(BlockGetter level, Predicate<BlockPos> sheeted, Vec3 from, Vec3 to) {
        int[] layers = {0};
        boolean[] first = {true};
        BlockGetter.traverseBlocks(from, to, null, (context, pos) -> {
            boolean makesIt = first[0];
            first[0] = false;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return null;
            if (state.is(BLOCKS) ? state.getShape(level, pos).clip(from, to, pos) != null
                    // A sheet whose block went without the server hearing of it (see SoundproofFeature) is in nothing.
                    : !makesIt && state.isCollisionShapeFullBlock(level, pos) && sheeted.test(pos)) {
                layers[0]++;
            }
            return null;
        }, context -> null);
        return Math.pow(LAYER, layers[0]);
    }

    /** Whether the block at {@code pos} has an insulation sheet in it. On a client, as its chunk was last sent. */
    public static boolean sheeted(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && sheets(chunk).contains(pos);
    }

    static Set<BlockPos> sheets(LevelChunk chunk) {
        return chunk.getExistingData(SoundproofFeature.SHEETS).orElse(Set.of());
    }

    static void set(Level level, BlockPos pos, boolean sheeted) {
        LevelChunk chunk = level.getChunkAt(pos);
        Set<BlockPos> sheets = new HashSet<>(sheets(chunk));
        if (sheeted ? !sheets.add(pos.immutable()) : !sheets.remove(pos)) return;
        if (sheets.isEmpty()) chunk.removeData(SoundproofFeature.SHEETS);
        else chunk.setData(SoundproofFeature.SHEETS, Set.copyOf(sheets));
    }
}
