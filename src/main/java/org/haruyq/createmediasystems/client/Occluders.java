package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Blocks on this client that stop light where their collision shapes don't, as a display's screen does: light traced
 * through the world (a stage light's) asks for them here. A block entity adds itself as it loads and is removed as it
 * goes.
 */
public final class Occluders {
    private static final WeakHashMap<Occluder, Boolean> OCCLUDERS = new WeakHashMap<>();

    private Occluders() {
    }

    /** A block that stops light: a block entity, whose own methods answer the first three. */
    public interface Occluder {
        @Nullable
        Level getLevel();

        boolean isRemoved();

        BlockPos getBlockPos();

        boolean occludes();

        /** What stops light, in its level's block space as placed (Sable moves it with its block). */
        AABB occluderBox();
    }

    public static synchronized void add(Occluder occluder) {
        OCCLUDERS.put(occluder, Boolean.TRUE);
    }

    public static synchronized void remove(Occluder occluder) {
        OCCLUDERS.remove(occluder);
    }

    public static synchronized List<Occluder> in(Level level) {
        List<Occluder> found = new ArrayList<>();
        for (Occluder occluder : OCCLUDERS.keySet()) {
            if (!occluder.isRemoved() && occluder.getLevel() == level && occluder.occludes()) {
                found.add(occluder);
            }
        }
        return found;
    }
}
