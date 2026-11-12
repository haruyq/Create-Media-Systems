package org.haruyq.createmediasystems.api.lighting.client;

import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Light traced from a fixture as CMS traces its stage lights' beams: through sub-levels where they are drawn, past
 * the fixture's own block, stopped by blocks and displays. World-space endpoints. One frame's.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface LightRays {
    /** Traces for this frame on the render thread from the fixture at {@code fixture}. */
    static LightRays rendered(Level level, BlockPos fixture) {
        return LightingRaycast.rendered(level, fixture);
    }

    /** The nearest thing hit from {@code fromWorld} to {@code toWorld}; null for nothing. */
    @Nullable
    Hit clip(Vec3 fromWorld, Vec3 toWorld);

    /** Whether any sub-level reaches into {@code bounds}. */
    boolean hasSubLevels(AABB bounds);

    @ApiStatus.NonExtendable
    interface Hit {
        BlockHitResult block();

        /** Where it was hit, in the world. */
        Vec3 point();

        /** The face's normal there, in the world. */
        Vec3 normal();
    }
}
