package org.haruyq.createmediasystems.launcher;

import java.util.UUID;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.ContraptionSurface;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

/** A projected disc keeps its contact in the support's own coordinate system. */
record DiscSurfaceAnchor(BlockPos block, UUID carrier, boolean contraption, Vec3 local) {
    static DiscSurfaceAnchor onContraption(ContraptionSurface.Hit hit) {
        return new DiscSurfaceAnchor(hit.local().getBlockPos(), hit.carrier().getUUID(), true, hit.local().getLocation());
    }

    @Nullable
    static DiscSurfaceAnchor onShip(Level level, BlockPos block, Vec3 point) {
        var ship = SableCompat.containing(level, block);
        return ship == null ? null : new DiscSurfaceAnchor(block, ship.getUniqueId(), false,
                ship.logicalPose().transformPositionInverse(point));
    }

    @Nullable
    Vec3 position(ServerLevel level) {
        if (contraption) {
            if (!(level.getEntity(carrier) instanceof AbstractContraptionEntity entity) || !entity.isAlive()
                    || entity.getContraption() == null || !supported(entity.getContraption().getContraptionWorld())) return null;
            Vec3 at = entity.toGlobalVector(local, 1);
            return SableCompat.toWorld(level, entity.blockPosition(), at);
        }
        var ship = SableCompat.containing(level, block);
        return ship == null || !ship.getUniqueId().equals(carrier) || !supported(level)
                ? null : ship.logicalPose().transformPosition(local);
    }

    private boolean supported(BlockGetter world) {
        var contact = new AABB(local, local).inflate(.0625);
        return world.getBlockState(block).getCollisionShape(world, block).toAabbs().stream()
                .anyMatch(part -> part.move(block).intersects(contact));
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putLong("Block", block.asLong());
        tag.putUUID("Carrier", carrier);
        tag.putBoolean("Contraption", contraption);
        tag.putDouble("X", local.x);
        tag.putDouble("Y", local.y);
        tag.putDouble("Z", local.z);
        return tag;
    }

    @Nullable
    static DiscSurfaceAnchor read(CompoundTag tag) {
        if (!tag.hasUUID("Carrier")) return null;
        Vec3 point = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        return Double.isFinite(point.lengthSqr()) ? new DiscSurfaceAnchor(BlockPos.of(tag.getLong("Block")),
                tag.getUUID("Carrier"), tag.getBoolean("Contraption"), point) : null;
    }
}
