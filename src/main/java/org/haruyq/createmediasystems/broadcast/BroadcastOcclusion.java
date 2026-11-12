package org.haruyq.createmediasystems.broadcast;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.ContraptionSurface;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class BroadcastOcclusion {
    private BroadcastOcclusion() {}

    public static boolean obstructed(Entity listener, Vec3 ear, Vec3 mouth) {
        Vec3 direction = mouth.subtract(ear);
        Vec3 end = ear.add(direction.normalize().scale(Math.clamp(direction.length() - .9, 0, 96)));
        BlockState hit = firstObstacle(listener, ear, end);
        if (hit == null) return false;
        if (!(hit.getBlock() instanceof BroadcastMastBlock || hit.getBlock() instanceof BroadcastBaseBlock)) return true;
        // A narrow pole can interrupt the centre ray while sound still reaches either side of it.
        Vec3 sideways = new Vec3(direction.z, 0, -direction.x).normalize();
        Vec3 offset = sideways.lengthSqr() == 0 ? new Vec3(.6, 0, 0) : sideways.scale(.6);
        return firstObstacle(listener, ear.add(offset), end.add(offset)) != null
                && firstObstacle(listener, ear.subtract(offset), end.subtract(offset)) != null;
    }

    @Nullable
    private static BlockState firstObstacle(Entity listener, Vec3 from, Vec3 end) {
        var level = listener.level();
        var hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, listener));
        boolean hasBlock = hit.getType() == HitResult.Type.BLOCK;
        var moving = ContraptionSurface.nearest(listener, from, hasBlock ? SableCompat.hitInWorld(level, hit) : end);
        if (moving != null) {
            return moving.carrier().getContraption().getContraptionWorld().getBlockState(moving.local().getBlockPos());
        }
        return hasBlock ? level.getBlockState(hit.getBlockPos()) : null;
    }
}
