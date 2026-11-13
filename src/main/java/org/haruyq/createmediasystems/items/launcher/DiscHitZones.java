package org.haruyq.createmediasystems.items.launcher;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import org.haruyq.createmediasystems.CreateMediaSystems;

/** Eye-height based head volume follows standing, crouching and baby dimensions. */
public final class DiscHitZones {
    public static final float HEAD_MULTIPLIER=2;
    public static final ResourceKey<DamageType> DAMAGE=ResourceKey.create(Registries.DAMAGE_TYPE,CreateMediaSystems.id("disc_projection"));
    public static final TagKey<EntityType<?>> HEADS=TagKey.create(Registries.ENTITY_TYPE,CreateMediaSystems.id("disc_headshot_targets"));
    private DiscHitZones() {}
    public static Optional<AABB> headBox(LivingEntity target) {
        if(!target.getType().is(HEADS))return Optional.empty();
        Pose pose=target.getPose();
        if(pose==Pose.SWIMMING || pose==Pose.FALL_FLYING || pose==Pose.SPIN_ATTACK || pose==Pose.SLEEPING)return Optional.empty();
        var body=target.getBoundingBox();
        double half=Math.min(.38,target.getBbWidth()*.48),height=Math.clamp(target.getBbHeight()*.15,.12,.34);
        return Optional.of(new AABB(target.getX()-half,target.getEyeY()-height,target.getZ()-half,
                target.getX()+half,Math.min(body.maxY,target.getEyeY()+height),target.getZ()+half));
    }
    public static boolean headshot(LivingEntity target,Vec3 impact) {
        return headBox(target).map(head->head.inflate(.065).contains(impact)).orElse(false);
    }
    public static DamageSource source(DiscProjectile disc) {
        return new DamageSource(disc.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE),disc,disc.getOwner());
    }
}
