package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * What changes in the client's world, as the level renderer hears of it (mixin/LevelRendererMixin), for whatever keeps
 * something it worked out from the world: blocks changed, a chunk loaded, the level replaced or left, and the frames
 * drawn of it. Render thread; listeners are added from a feature's client setup.
 */
public final class WorldEvents {
    /** Blocks between these corners, inclusive, changed, or the faces of one of them. */
    @FunctionalInterface
    public interface BlocksChanged {
        void changed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }

    /** A block in section ({@code x}, {@code y}, {@code z}) changed, or one of its faces. */
    @FunctionalInterface
    public interface SectionChanged {
        void changed(int x, int y, int z);
    }

    /** Chunk column ({@code x}, {@code z}) was loaded anew. */
    @FunctionalInterface
    public interface ChunkLoaded {
        void loaded(int x, int z);
    }

    private static final List<BlocksChanged> BLOCKS = new ArrayList<>();
    private static final List<SectionChanged> SECTIONS = new ArrayList<>();
    private static final List<ChunkLoaded> CHUNKS = new ArrayList<>();
    private static final List<Runnable> LEVELS = new ArrayList<>();
    private static final List<Runnable> FRAMES = new ArrayList<>();
    private static long frame;

    private WorldEvents() {
    }

    /** Adds its listener, once, from the client's setup. */
    public static void register() {
        // Last of all that is drawn after the level, so everything drawn this frame has been.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                frame++;
                FRAMES.forEach(Runnable::run);
            }
        });
    }

    public static void onBlocksChanged(BlocksChanged listener) {
        BLOCKS.add(listener);
    }

    public static void onSectionChanged(SectionChanged listener) {
        SECTIONS.add(listener);
    }

    public static void onChunkLoaded(ChunkLoaded listener) {
        CHUNKS.add(listener);
    }

    /** {@code listener} runs once the client's level is another one, or none (logged out); Minecraft#level is it. */
    public static void onLevelChanged(Runnable listener) {
        LEVELS.add(listener);
    }

    /** {@code listener} runs after each frame of the world, once {@link #frame()} counts the next one. */
    public static void onFrameEnded(Runnable listener) {
        FRAMES.add(listener);
    }

    /** The frame of the world being drawn, counted from 0. */
    public static long frame() {
        return frame;
    }

    /** The block at {@code pos} changed (LevelRenderer.setBlockDirty). */
    public static void blockChanged(BlockPos pos) {
        blocksChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Blocks between these corners, inclusive, changed (LevelRenderer.setBlocksDirty): so may the faces of the blocks
     * beside them, which are told of too.
     */
    public static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        changed(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
    }

    /** Section ({@code x}, {@code y}, {@code z}) and those around it are built again (setSectionDirtyWithNeighbors). */
    public static void sectionsChanged(int x, int y, int z) {
        changed((x - 1) << 4, (y - 1) << 4, (z - 1) << 4, (x + 1) << 4 | 15, (y + 1) << 4 | 15, (z + 1) << 4 | 15);
    }

    public static void chunkLoaded(int x, int z) {
        CHUNKS.forEach(listener -> listener.loaded(x, z));
    }

    /** The level renderer was given the client's new level, or none. */
    public static void levelChanged() {
        LEVELS.forEach(Runnable::run);
    }

    private static void changed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BLOCKS.forEach(listener -> listener.changed(minX, minY, minZ, maxX, maxY, maxZ));
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int y = minY >> 4; y <= maxY >> 4; y++) {
                for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                    for (SectionChanged listener : SECTIONS) {
                        listener.changed(x, y, z);
                    }
                }
            }
        }
    }
}
