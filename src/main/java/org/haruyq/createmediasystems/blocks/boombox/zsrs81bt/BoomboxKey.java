package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt;

import java.util.Locale;

import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Everything on the CD radio a player works by pointing at it, and where each is on its face: in sixteenths, across
 * from the face's left as one looks at it and up. Its front's up is the block's; its top's up runs from the block's
 * front edge to its back.
 */
public enum BoomboxKey {
    POWER(Face.TOP, 3.6f, 4.3f, 4.8f, 5.3f),
    OPEN(Face.TOP, 7.2f, 4.3f, 8.8f, 5.3f),
    VOLUME_DOWN(Face.TOP, 10.3f, 4.3f, 11.3f, 5.3f),
    VOLUME_UP(Face.TOP, 11.5f, 4.3f, 12.5f, 5.3f),
    LID(Face.TOP, 4.6f, 5.7f, 11.4f, 11.6f),
    CD(Face.FRONT, 6.2f, 2.95f, 7.9f, 3.45f),
    FM(Face.FRONT, 8.1f, 2.95f, 9.8f, 3.45f),
    PLAY(Face.FRONT, 6.2f, 2.05f, 7.9f, 2.65f),
    STOP(Face.FRONT, 8.1f, 2.05f, 9.8f, 2.65f),
    PREVIOUS(Face.FRONT, 6.2f, 1.15f, 7.9f, 1.75f),
    NEXT(Face.FRONT, 8.1f, 1.15f, 9.8f, 1.75f);

    public enum Face { FRONT, TOP }

    /**
     * The set in its block's space (sixteenths; x across from its left as one faces its front, y up, z towards the
     * front): its back and front, and the top of its body. It is as wide as its block.
     */
    public static final float BACK = 3.5f, FRONT = 12.5f, TOP = 6.6f;

    public final Face face;
    public final float left;
    public final float bottom;
    public final float right;
    public final float top;

    BoomboxKey(Face face, float left, float bottom, float right, float top) {
        this.face = face;
        this.left = left;
        this.bottom = bottom;
        this.right = right;
        this.top = top;
    }

    public float centerX() {
        return (left + right) / 2;
    }

    public Component label() {
        return Component.translatable("create_media_systems.boombox.key." + name().toLowerCase(Locale.ROOT));
    }

    public static BoomboxKey byId(int id) {
        return values()[Mth.clamp(id, 0, values().length - 1)];
    }

    /**
     * The key a look from {@code eye} along {@code look} (both given in the block, 0 to 1) meets on the top or the
     * front of a set facing {@code facing}; null for none.
     */
    @Nullable
    public static BoomboxKey at(Direction facing, Vec3 eye, Vec3 look) {
        Vec3 from = HifiLayout.toStack(facing, eye);
        Vec3 along = HifiLayout.toStack(facing, eye.add(look)).subtract(from);
        BoomboxKey key = null;
        if (from.y > TOP && along.y < -1e-6) {
            Vec3 at = from.add(along.scale((TOP - from.y) / along.y));
            key = at(Face.TOP, at.x, 16 - at.z);
        }
        if (key == null && from.z > FRONT && along.z < -1e-6) {
            Vec3 at = from.add(along.scale((FRONT - from.z) / along.z));
            key = at(Face.FRONT, at.x, at.y);
        }
        return key;
    }

    @Nullable
    private static BoomboxKey at(Face face, double x, double y) {
        for (BoomboxKey key : values()) {
            if (key.face == face && x >= key.left && x <= key.right && y >= key.bottom && y <= key.top) {
                return key;
            }
        }
        return null;
    }
}
