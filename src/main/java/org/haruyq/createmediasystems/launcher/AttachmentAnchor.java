package org.haruyq.createmediasystems.launcher;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;

/**
 * Where on its target an attached disc sits: a point and a forward direction kept local to the anchor, so the disc
 * follows the target as it moves and animates. A living body anchors in its body or head frame, an ender dragon in one
 * of its animated bones.
 */
public sealed interface AttachmentAnchor {
    /** Whether the disc struck its target's head. */
    boolean head();
    /** The dragon part the disc struck, or -1. */
    int part();
    /** The dragon bone the disc sits on, or -1. */
    int bone();

    /** Where {@code local} on {@code target} is in the world, {@code partial} into the tick. */
    Vec3 position(LivingEntity target, Vec3 local, float partial);

    /** Which way the local direction {@code local} on {@code target} points in the world. */
    Vec3 direction(LivingEntity target, Vec3 local, float partial);

    /** Where a disc that hit {@code target} at {@code point}, flying along {@code incoming}, sits on it. */
    Placed place(LivingEntity target, Vec3 point, Vec3 incoming);

    /** How far from the anchor's origin, in its local units, a saved point may be. */
    float pointBound();

    /** A disc's place on its target: its anchor, and its point and forward direction local to that anchor. */
    record Placed(AttachmentAnchor anchor, Vec3 point, Vec3 direction) {}

    /** What a hit struck: the entity the disc attaches to, its anchor there, and whether it was a headshot. */
    record Strike(Entity target, AttachmentAnchor anchor, boolean head) {}

    /** The body, or the head, of a living target. Normalized offsets follow crouching and changes in body size. */
    record LivingFrame(boolean head) implements AttachmentAnchor {
        @Override public int part() { return -1; }
        @Override public int bone() { return -1; }
        @Override public Vec3 position(LivingEntity target, Vec3 local, float partial) {
            return DiscAttachmentFrame.at(target, head, partial).world(local);
        }
        @Override public Vec3 direction(LivingEntity target, Vec3 local, float partial) {
            return DiscAttachmentFrame.at(target, head, partial).worldDirection(local).normalize();
        }
        @Override public Placed place(LivingEntity target, Vec3 point, Vec3 incoming) {
            var frame = DiscAttachmentFrame.at(target, head, 1);
            // The swept hit is just outside the skin. Move the blade centre slightly in, leaving its outer half visible.
            return new Placed(this, frame.local(point.add(incoming.scale(.10))), frame.localDirection(incoming));
        }
        @Override public float pointBound() { return 2; }
    }

    /**
     * One of an ender dragon's parts, and the visible bone of it the disc sits on. A bone of -1 (saved before bones)
     * follows the part's collider instead.
     */
    record DragonBone(int part, int bone) implements AttachmentAnchor {
        public DragonBone {
            part = Math.clamp(part, 0, DiscDragonAttachment.PARTS - 1);
            bone = Math.clamp(bone, -1, DiscDragonAttachment.BONES - 1);
        }
        @Override public boolean head() { return part == 0; }
        @Override public Vec3 position(LivingEntity target, Vec3 local, float partial) {
            if (!(target instanceof EnderDragon dragon)) return new LivingFrame(false).position(target, local, partial);
            return bone >= 0 ? DiscDragonAttachment.bone(dragon, bone, partial).position(local)
                    : DiscAttachmentFrame.atDragonPart(dragon, part, partial).world(local);
        }
        @Override public Vec3 direction(LivingEntity target, Vec3 local, float partial) {
            if (!(target instanceof EnderDragon dragon)) return new LivingFrame(false).direction(target, local, partial);
            return bone >= 0 ? DiscDragonAttachment.bone(dragon, bone, partial).direction(local)
                    : DiscAttachmentFrame.atDragonPart(dragon, part, partial).worldDirection(local).normalize();
        }
        @Override public Placed place(LivingEntity target, Vec3 point, Vec3 incoming) {
            if (!(target instanceof EnderDragon dragon)) return new LivingFrame(false).place(target, point, incoming);
            var contact = DiscDragonAttachment.contact(dragon, part, point, incoming);
            return new Placed(new DragonBone(part, contact.bone()), contact.point(), contact.direction());
        }
        @Override public float pointBound() { return bone >= 0 ? 8 : 2; }
    }

    /** What a hit on {@code struck} at {@code at} struck: a dragon's part is its dragon's, at that part. */
    static Strike strike(Entity struck, Vec3 at) {
        if (struck instanceof EnderDragonPart part) {
            var parts = part.parentMob.getSubEntities();
            boolean head = part == part.parentMob.head;
            for (int index = 0; index < parts.length; index++) {
                if (parts[index] == part) return new Strike(part.parentMob, new DragonBone(index, -1), head);
            }
            return new Strike(part.parentMob, new LivingFrame(head), head);
        }
        boolean head = struck instanceof LivingEntity living && DiscHitZones.headshot(living, at);
        return new Strike(struck, new LivingFrame(head), head);
    }

    /** The anchor a disc's synced or saved {@code head}, {@code part} and {@code bone} describe. */
    static AttachmentAnchor of(boolean head, int part, int bone) {
        return part >= 0 ? new DragonBone(part, bone) : new LivingFrame(head);
    }

    default void save(CompoundTag tag) {
        tag.putBoolean("CmsDiscAttachHead", head());
        tag.putInt("CmsDiscAttachPart", part());
        tag.putInt("CmsDiscAttachBone", bone());
    }

    static AttachmentAnchor read(CompoundTag tag) {
        return of(tag.getBoolean("CmsDiscAttachHead"), tag.contains("CmsDiscAttachPart") ? tag.getInt("CmsDiscAttachPart") : -1,
                tag.contains("CmsDiscAttachBone") ? tag.getInt("CmsDiscAttachBone") : -1);
    }
}
