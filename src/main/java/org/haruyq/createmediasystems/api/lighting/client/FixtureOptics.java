package org.haruyq.createmediasystems.api.lighting.client;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * How a family of fixtures' light meets the world on a client, where it differs from CMS's stage lights: each
 * method left as it is keeps CMS's own. Called from the render thread and from background scans, so it must not
 * change the level. {@code fixture} is the fixture's block, the light's source.
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
public interface FixtureOptics {
    /** Adds the optics of {@code family}'s fixtures (StageLightBlock.Entity#family), from client setup. */
    static void register(String family, FixtureOptics optics) {
        LightingRaycast.addOptics(family, optics);
    }

    /**
     * The first block a ray from {@code from} to {@code to} hits, in block space (the level's, or a sub-level's
     * plot), among those {@code allowed}; {@link BlockHitResult#miss} for none. Null lets CMS trace it.
     */
    @Nullable
    default BlockHitResult trace(Level level, BlockPos fixture, Vec3 from, Vec3 to, Predicate<BlockPos> allowed) {
        return null;
    }

    /**
     * Gives {@code quads} the faces of the block at {@code pos} that the light is laid on, and returns true; false
     * lets CMS give its model's faces.
     */
    default boolean forEachQuad(Level level, BlockPos fixture, BlockPos pos, BlockState state, Consumer<BakedQuad> quads) {
        return false;
    }
}
