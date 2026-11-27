package org.haruyq.createmediasystems.blocks.display;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * Which of the screen's points each vertex of a display's lit faces sees, and the screen's light that reaches it by
 * way of other faces (display_shadow.fsh's two passes), computed from a block grid in bounded GPU batches without a
 * CPU readback, and that grid, for the pixels of a face whose corners don't agree to look for themselves
 * (display_light.fsh).
 */
final class DisplayShadows implements AutoCloseable {
    private static final int WIDTH = 1024;
    private static final int RAYS_PER_FRAME = 262_144;
    /**
     * Rays a vertex traces: to each of display_shadow.fsh's SPOTS for the screen's light, and for the light by way of
     * other faces, as many to those faces and one from each on to the screen.
     */
    private static final int RAYS = 96;
    /**
     * display_light.json's Voxels, and Shapes on the unit after: units of their own, as no unit may hold a 2D and a 3D
     * sampler's textures.
     */
    private static final int VOXEL_UNIT = 3;
    private static ShaderInstance shader;
    private static final Budget BUDGET = new Budget(RAYS_PER_FRAME);
    private final TextureTarget visibility;
    private final BlockPos min;
    private final DisplayBlock.Screen screen;
    private final float width;
    private final float height;
    private final int rows;
    private int voxels;
    private int shapes;
    private int shaded;

    static void setShader(ShaderInstance loaded) {
        shader = loaded;
    }

    static final class Budget {
        private final int limit;
        private long frame = -1;
        private int remaining;

        Budget(int limit) {
            this.limit = limit;
        }

        int take(long frame, int vertices, int rays) {
            if (this.frame != frame) {
                this.frame = frame;
                remaining = limit;
            }
            int count = Math.min(vertices, remaining / rays);
            remaining -= count * rays;
            return count;
        }
    }

    /**
     * The cells of a block's 4 × 4 × 4 whose middles the box holds, a bit each (x + 4y + 16z): what of the block
     * stands in the light's way, to a quarter of a block.
     */
    static long cells(double x0, double y0, double z0, double x1, double y1, double z1) {
        long bits = 0;
        for (int i = 0; i < 64; i++) {
            double x = ((i & 3) + 0.5) / 4, y = ((i >> 2 & 3) + 0.5) / 4, z = ((i >> 4) + 0.5) / 4;
            if (x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1) {
                bits |= 1L << i;
            }
        }
        return bits;
    }

    /**
     * Two bytes per block: what stands in the light's way there (0 nothing, 255 all of it, else which of shapes), and
     * its map colour's id, for the light it reflects.
     */
    static final class Grid implements AutoCloseable {
        final BlockPos min;
        final int width;
        final int height;
        final int depth;
        final ByteBuffer cells;
        /** What less than a whole block fills of it (see DisplayShadows.cells), from 1. */
        final long[] shapes = new long[255];
        private int count = 1;

        Grid(BlockPos min, BlockPos max) {
            this.min = min;
            width = max.getX() - min.getX() + 1;
            height = max.getY() - min.getY() + 1;
            depth = max.getZ() - min.getZ() + 1;
            cells = MemoryUtil.memCalloc(Math.multiplyExact(Math.multiplyExact(width, height), depth * 2));
        }

        void block(int x, int y, int z, long shape, int colour) {
            if (shape == 0) {
                return;
            }
            int kind = 255;
            if (shape != -1L) {
                int known = 1;
                while (known < count && shapes[known] != shape) {
                    known++;
                }
                // ponytail: past 254 shapes about one display, the rest stand as whole blocks; two bytes a block if
                // a room ever has more.
                if (known < shapes.length) {
                    shapes[known] = shape;
                    count = Math.max(count, known + 1);
                    kind = known;
                }
            }
            int index = 2 * (x - min.getX() + width * (y - min.getY() + height * (z - min.getZ())));
            cells.put(index, (byte) kind).put(index + 1, (byte) colour);
        }

        @Override
        public void close() {
            MemoryUtil.memFree(cells);
        }
    }

    DisplayShadows(Grid grid, DisplayBlock.Screen screen, float width, float height, int vertices) {
        min = grid.min;
        this.screen = screen;
        this.width = width;
        this.height = height;
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            // The second pass's texels above the first's.
            rows = Math.max(1, (vertices + WIDTH - 1) / WIDTH);
            visibility = new TextureTarget(WIDTH, rows * 2, false, Minecraft.ON_OSX);
            RenderSystem.viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] stores = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL12.GL_UNPACK_IMAGE_HEIGHT,
                GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL12.GL_UNPACK_SKIP_IMAGES};
        int[] values = new int[stores.length];
        for (int i = 0; i < stores.length; i++) {
            values[i] = GL11.glGetInteger(stores[i]);
        }
        voxels = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, voxels);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < stores.length; i++) {
                GL11.glPixelStorei(stores[i], i == 0 ? 1 : 0);
            }
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG8UI, grid.width, grid.height, grid.depth, 0,
                    GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_BYTE, grid.cells);
            // A row of shapes, each one's low half in red, its high half in green; and a row of the map colours, in
            // red.
            shapes = TextureUtil.generateTextureId();
            RenderSystem.bindTexture(shapes);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bits = stack.calloc(2 * 256 * Long.BYTES);
                bits.asLongBuffer().put(grid.shapes);
                for (int id = 0; id < 64; id++) {
                    bits.putLong((256 + id) * Long.BYTES, MapColor.byId(id).col);
                }
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32UI, 256, 2, 0, GL30.GL_RG_INTEGER,
                        GL11.GL_UNSIGNED_INT, bits);
            }
        } catch (Throwable error) {
            close();
            throw error;
        } finally {
            for (int i = 0; i < stores.length; i++) {
                GL11.glPixelStorei(stores[i], values[i]);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
            RenderSystem.activeTexture(active);
        }
    }

    int texture() {
        return visibility.getColorTextureId();
    }

    boolean advance(long frame, VertexBuffer[] buffers, int[] counts) {
        int total = counts[0] + counts[1];
        if (shaded == total) {
            return true;
        }
        if (shader == null) {
            return false;
        }
        int count = BUDGET.take(frame, total - shaded, RAYS);
        if (count == 0) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            RenderSystem.bindTexture(shapes);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean pointSize = GL11.glIsEnabled(GL32.GL_PROGRAM_POINT_SIZE);
            ByteBuffer color = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, color);
            try {
                visibility.bindWrite(true);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);
                GL11.glColorMask(true, true, true, true);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, voxels);
                shader.safeGetUniform("Size").set((float) WIDTH, (float) visibility.height);
                shader.safeGetUniform("VoxelMin").set((float) min.getX(), (float) min.getY(), (float) min.getZ());
                Vec3 origin = screen.origin();
                Vec3 right = screen.right();
                Vec3 out = screen.out();
                shader.safeGetUniform("ScreenOrigin").set((float) origin.x, (float) origin.y, (float) origin.z);
                shader.safeGetUniform("ScreenRight").set((float) right.x, (float) right.y, (float) right.z);
                shader.safeGetUniform("ScreenOut").set((float) out.x, (float) out.y, (float) out.z);
                shader.safeGetUniform("PictureSize").set(width, height);
                int end = shaded + count;
                for (int pass = 0; pass < 2; pass++) {
                    shader.safeGetUniform("Pass").set(pass);
                    int above = pass * rows * WIDTH;
                    if (shaded < counts[0]) {
                        draw(buffers[0], shaded, Math.min(end, counts[0]) - shaded, above);
                    }
                    if (end > counts[0]) {
                        int start = Math.max(shaded, counts[0]);
                        draw(buffers[1], start - counts[0], end - start, counts[0] + above);
                    }
                }
                shaded = end;
            } finally {
                shader.clear();
                VertexBuffer.unbind();
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
                RenderSystem.activeTexture(active);
                if (depth) {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                }
                if (blend) {
                    GL11.glEnable(GL11.GL_BLEND);
                }
                if (scissor) {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                }
                if (!pointSize) {
                    GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
                }
                GL11.glColorMask(color.get(0) != 0, color.get(1) != 0, color.get(2) != 0, color.get(3) != 0);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                RenderSystem.viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        }
        return shaded == total;
    }

    /** The block grid as {@code light}'s Voxels and Shapes, to find each pixel's shadow in (display_light.fsh). */
    void bindVoxels(ShaderInstance light) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + VOXEL_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, voxels);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + VOXEL_UNIT + 1);
        RenderSystem.bindTexture(shapes);
        RenderSystem.activeTexture(active);
        light.safeGetUniform("VoxelMin").set((float) min.getX(), (float) min.getY(), (float) min.getZ());
    }

    private void draw(VertexBuffer buffer, int first, int count, int offset) {
        shader.safeGetUniform("VertexOffset").set(offset);
        buffer.bind();
        shader.apply();
        GL11.glDrawArrays(GL11.GL_POINTS, first, count);
    }

    private void releaseVoxels() {
        if (voxels != 0) {
            GL11.glDeleteTextures(voxels);
            voxels = 0;
            TextureUtil.releaseTextureId(shapes);
        }
    }

    @Override
    public void close() {
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        releaseVoxels();
        visibility.destroyBuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
    }
}
