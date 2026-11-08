package org.haruyq.createmediasystems.lighting;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.client.Workers;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blocks around the camera as spot_light.fsh traces a stage light's light through them, a pixel at a time: each
 * block's collision shape as which of its eight half-block corners it fills (a bit each, see bits), in a 3D texture
 * SIZE blocks a side. Built in the background as the camera moves on, a changed section mended on the render thread.
 * A light whose light or lamp lies outside it, or on a Sable sub-level, is traced on the CPU as before.
 */
final class LightingOcclusion {
    /** Blocks a side; a multiple of 16. */
    static final int SIZE = 128;
    /** Blocks the camera may move from the grid's middle before it is built anew around it. */
    private static final int RECENTER = 24;
    /** Sections mended a frame at most (each a 16³ upload). */
    private static final int MENDS_PER_FRAME = 8;
    /** The texture unit the grid is bound to while lights are drawn, clear of Minecraft's and Iris's 2D ones. */
    static final int UNIT = 7;
    /** Collision shapes as bits, by block state; shapes that depend on where the block is take their first one's. */
    // ponytail: a few hundred states at most; cleared with the level.
    private static final Map<BlockState, Byte> BITS = new ConcurrentHashMap<>();

    private static int texture;
    private static Level level;
    /** The uploaded grid's lowest corner; null while there is none. */
    private static BlockPos origin;
    private static CompletableFuture<Built> building;
    /** Sections changed since the grid now shown, or the one being built, was read. */
    private static final LongOpenHashSet changed = new LongOpenHashSet();
    private static final ByteBuffer SECTION = MemoryUtil.memAlloc(16 * 16 * 16);

    private record Built(Level level, BlockPos origin, ByteBuffer data) {
    }

    private LightingOcclusion() {
    }

    /** Once a frame on the render thread: builds the grid around the camera anew when it has moved off, mends it. */
    static void update() {
        Minecraft minecraft = Minecraft.getInstance();
        Level current = minecraft.level;
        if (current != level) {
            forget();
            level = current;
        }
        if (current == null) {
            return;
        }
        if (building != null && building.isDone()) {
            Built built = building.getNow(null);
            building = null;
            if (built != null && built.level == current) {
                upload(built);
            } else if (built != null) {
                MemoryUtil.memFree(built.data);
            }
        }
        BlockPos camera = BlockPos.containing(minecraft.gameRenderer.getMainCamera().getPosition());
        BlockPos wanted = new BlockPos((camera.getX() >> 4 << 4) - SIZE / 2, (camera.getY() >> 4 << 4) - SIZE / 2,
                (camera.getZ() >> 4 << 4) - SIZE / 2);
        if (building == null && (origin == null || Math.abs(camera.getX() - origin.getX() - SIZE / 2) > RECENTER
                || Math.abs(camera.getY() - origin.getY() - SIZE / 2) > RECENTER
                || Math.abs(camera.getZ() - origin.getZ() - SIZE / 2) > RECENTER)) {
            changed.clear();
            building = CompletableFuture.supplyAsync(() -> build(current, wanted), Workers.LIGHT)
                    .exceptionally(failed -> {
                        LogUtils.getLogger().warn("Stage lights couldn't map the blocks around the camera", failed);
                        return null;
                    });
        }
        if (origin != null && !changed.isEmpty()) {
            int mended = 0;
            for (var it = changed.iterator(); it.hasNext() && mended < MENDS_PER_FRAME; mended++) {
                mend(it.nextLong());
                it.remove();
            }
        }
    }

    /** A block in section ({@code x}, {@code y}, {@code z}) changed. */
    static void sectionChanged(int x, int y, int z) {
        changed.add(SectionPos.asLong(x, y, z));
    }

    /** Chunk column ({@code x}, {@code z}) was loaded anew. */
    static void chunkChanged(int x, int z) {
        if (origin == null) {
            return;
        }
        for (int y = origin.getY() >> 4; y < (origin.getY() + SIZE) >> 4; y++) {
            changed.add(SectionPos.asLong(x, y, z));
        }
    }

    /** Whether the grid holds all of {@code bounds} (in the world), a block in from its edges. */
    static boolean covers(AABB bounds) {
        return origin != null && bounds.minX >= origin.getX() + 1 && bounds.minY >= origin.getY() + 1
                && bounds.minZ >= origin.getZ() + 1 && bounds.maxX <= origin.getX() + SIZE - 1
                && bounds.maxY <= origin.getY() + SIZE - 1 && bounds.maxZ <= origin.getZ() + SIZE - 1;
    }

    /** Binds the grid for {@code shader}'s Occupancy, with its corner as seen from the lamp at {@code lamp}. */
    static void bind(net.minecraft.client.renderer.ShaderInstance shader, BlockPos lamp) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        shader.safeGetUniform("GridOrigin").set((float) (origin.getX() - lamp.getX()),
                (float) (origin.getY() - lamp.getY()), (float) (origin.getZ() - lamp.getZ()));
        shader.safeGetUniform("GridSize").set(SIZE);
        // A sampler Minecraft doesn't know of: its unit is set on the program itself, which keeps it.
        int location = GlStateManager._glGetUniformLocation(shader.getId(), "Occupancy");
        if (location >= 0) {
            int bound = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL20.glUseProgram(shader.getId());
            GL20.glUniform1i(location, UNIT);
            GL20.glUseProgram(bound);
        }
    }

    private static Built build(Level level, BlockPos origin) {
        ByteBuffer data = MemoryUtil.memCalloc(SIZE * SIZE * SIZE);
        for (int sx = 0; sx < SIZE / 16; sx++) {
            for (int sz = 0; sz < SIZE / 16; sz++) {
                for (int sy = 0; sy < SIZE / 16; sy++) {
                    read(level, SectionPos.of(origin.offset(sx * 16, sy * 16, sz * 16)), data, origin, SIZE);
                }
            }
        }
        return new Built(level, origin, data);
    }

    /**
     * Section {@code at}'s blocks into {@code out}, a grid {@code size} blocks a side from {@code corner} (x fastest,
     * then y, then z). Off the render thread it reads the live client level, as the light's face scans do.
     */
    private static void read(Level level, SectionPos at, ByteBuffer out, BlockPos corner, int size) {
        if (!(level.getChunkSource().getChunk(at.x(), at.z(), ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
            return;
        }
        int index = level.getSectionIndexFromSectionY(at.y());
        if (index < 0 || index >= chunk.getSections().length) {
            return;
        }
        LevelChunkSection section = chunk.getSections()[index];
        if (section == null || section.hasOnlyAir()) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    pos.set(at.minBlockX() + x, at.minBlockY() + y, at.minBlockZ() + z);
                    byte bits = BITS.computeIfAbsent(state, s -> bits(s.getCollisionShape(level, pos, CollisionContext.empty())));
                    if (bits != 0) {
                        int gx = pos.getX() - corner.getX(), gy = pos.getY() - corner.getY(), gz = pos.getZ() - corner.getZ();
                        out.put(gx + (gy + gz * size) * size, bits);
                    }
                }
            }
        }
    }

    /**
     * Which of a block's eight half-block corners {@code shape} fills, by their middles: bit x + 2y + 4z for the
     * corner at (x, y, z), each 0 or 1. A slab fills four, a carpet or a pane none (light passes it).
     */
    static byte bits(VoxelShape shape) {
        if (shape.isEmpty()) {
            return 0;
        }
        if (shape == Shapes.block()) {
            return (byte) 0xFF;
        }
        List<AABB> boxes = shape.toAabbs();
        int bits = 0;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) * 0.5 + 0.25, y = (corner >> 1 & 1) * 0.5 + 0.25, z = (corner >> 2 & 1) * 0.5 + 0.25;
            for (AABB box : boxes) {
                if (box.contains(x, y, z)) {
                    bits |= 1 << corner;
                    break;
                }
            }
        }
        return (byte) bits;
    }

    private static void upload(Built built) {
        RenderSystem.assertOnRenderThread();
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            unpackPlainly();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8UI, SIZE, SIZE, SIZE, 0, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_BYTE, built.data);
        } else {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            unpackPlainly();
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_BYTE, built.data);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        MemoryUtil.memFree(built.data);
        origin = built.origin;
    }

    /** Section {@code key} read again into the grid, if it is in it. */
    private static void mend(long key) {
        SectionPos at = SectionPos.of(key);
        int gx = at.minBlockX() - origin.getX(), gy = at.minBlockY() - origin.getY(), gz = at.minBlockZ() - origin.getZ();
        if (gx < 0 || gy < 0 || gz < 0 || gx >= SIZE || gy >= SIZE || gz >= SIZE) {
            return;
        }
        MemoryUtil.memSet(SECTION, 0);
        read(level, at, SECTION, at.origin(), 16);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        unpackPlainly();
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, gx, gy, gz, 16, 16, 16, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE,
                SECTION);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /** Tightly packed bytes from client memory, whatever an earlier upload left set. */
    private static void unpackPlainly() {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
    }

    static void forget() {
        if (building != null) {
            // Not cancelled: a build already reading finishes regardless, and its memory is freed then.
            building.thenAccept(built -> {
                if (built != null) {
                    MemoryUtil.memFree(built.data);
                }
            });
            building = null;
        }
        origin = null;
        changed.clear();
        BITS.clear();
    }
}
