package org.haruyq.createmediasystems.launcher.client;

import java.util.UUID;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.playback.DopplerMath;
import org.haruyq.createmediasystems.playback.MusicEmitter;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A launched disc's music, heard from the disc itself (no proxy speaker), before and after it hits: only what is on
 * the disc (see MediaItem), never a weapon sound. Each listener hears its own Doppler pitch of it.
 */
record DiscEmitter(DiscProjectile disc) implements MusicEmitter {
    @Override
    public int cap() {
        return ClientConfig.MAX_AUDIBLE_DISC_PROJECTIONS.get();
    }

    static final double RANGE = 72;

    static Stream<DiscEmitter> near(Entity ear) {
        return ear.level().getEntitiesOfClass(DiscProjectile.class, ear.getBoundingBox().inflate(RANGE)).stream()
                .map(DiscEmitter::new);
    }

    @Override
    public UUID id() {
        return disc.getUUID();
    }

    @Override
    public int revision() {
        return disc.revision();
    }

    @Override
    public ItemStack media() {
        return disc.getItem();
    }

    /** Stuck in a target that isn't tracked here, it is silent (its clock runs on). */
    @Override
    public boolean present() {
        return !disc.isRemoved() && WorldEvents.isClientWorld(disc.level()) && disc.soundEnabled();
    }

    @Override
    public Vec3 position() {
        return disc.position();
    }

    @Override
    @Nullable
    public Vec3 velocity() {
        if (disc.attached()) {
            Entity target = disc.attachmentTarget();
            return target == null ? Vec3.ZERO : DopplerMath.step(target);
        }
        return disc.embedded() ? null : disc.getDeltaMovement();
    }

    @Override
    public double seconds() {
        return disc.elapsedMusicSeconds();
    }

    @Override
    public double range() {
        return RANGE;
    }

    @Override
    public double gain(Entity ear) {
        return .9 * MusicEmitter.falloff(Math.sqrt(disc.distanceToSqr(ear)), 18);
    }
}
