package org.haruyq.createmediasystems.api.client;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.jetbrains.annotations.ApiStatus;

/**
 * What changes in the client's world, for whatever keeps something worked out from it (shapes, light, occlusion).
 * Listeners run on the render thread and are added from client setup.
 */
public final class WorldChanges {
    private WorldChanges() {
    }

    /** Blocks between these corners, inclusive, changed, or the faces of one of them. */
    @FunctionalInterface
    @ApiStatus.OverrideOnly
    public interface Blocks {
        void changed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }

    /** A block in section ({@code x}, {@code y}, {@code z}) changed, or one of its faces. */
    @FunctionalInterface
    @ApiStatus.OverrideOnly
    public interface Section {
        void changed(int x, int y, int z);
    }

    /** Chunk column ({@code x}, {@code z}). */
    @FunctionalInterface
    @ApiStatus.OverrideOnly
    public interface Chunk {
        void changed(int x, int z);
    }

    public static void onBlocksChanged(Blocks listener) {
        WorldEvents.onBlocksChanged(listener::changed);
    }

    public static void onSectionChanged(Section listener) {
        WorldEvents.onSectionChanged(listener::changed);
    }

    public static void onChunkLoaded(Chunk listener) {
        WorldEvents.onChunkLoaded(listener::changed);
    }

    public static void onChunkUnloaded(Chunk listener) {
        WorldEvents.onChunkUnloaded(listener::changed);
    }

    /** Runs once the client's level is another one, or none (logged out). */
    public static void onLevelChanged(Runnable listener) {
        WorldEvents.onLevelChanged(listener);
    }

    /** Runs after each frame of the world, once {@link #frame()} counts the next one. */
    public static void onFrameEnded(Runnable listener) {
        WorldEvents.onFrameEnded(listener);
    }

    /** The frame of the world being drawn, counted from 0. */
    public static long frame() {
        return WorldEvents.frame();
    }

    /**
     * Tells every listener (CMS's own too) that blocks between these corners changed, and their neighbours' faces:
     * for a change the level renderer doesn't hear of, such as a block's model data. Render thread.
     */
    public static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        WorldEvents.blocksChanged(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
