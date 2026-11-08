package org.haruyq.createmediasystems.display;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Cached vertex visibility, computed from a block grid in bounded GPU batches without a CPU readback. */
final class DisplayShadows implements AutoCloseable {
    private static final int WIDTH = 1024;
    private static final int RAYS_PER_FRAME = 65_536;
    private static ShaderInstance shader;
    private static int targetsProgram;
    private static int targetsLocation;
    private static final Budget BUDGET = new Budget(RAYS_PER_FRAME);
    private final TextureTarget visibility;
    private final BlockPos min;
    private final float[] targets;
    private final Vec3 right;
    private final Vec3 out;
    private int voxels;
    private int shaded;

    static void setShader(ShaderInstance loaded) {
        shader = loaded;
        targetsProgram = loaded.getId();
        targetsLocation = GL20.glGetUniformLocation(targetsProgram, "Targets");
    }

    static final class Budget {
        private final int limit;
        private int frame = -1;
        private int remaining;

        Budget(int limit) {
            this.limit = limit;
        }

        int take(int frame, int vertices, int rays) {
            if (this.frame != frame) {
                this.frame = frame;
                remaining = limit;
            }
            int count = Math.min(vertices, remaining / rays);
            remaining -= count * rays;
            return count;
        }
    }

    /** Eight block occupancy bits per texel, keeping even the largest screen's grid below 256 texels per side. */
    static final class Grid implements AutoCloseable {
        final BlockPos min;
        final int width;
        final int height;
        final int depth;
        final ByteBuffer cells;

        Grid(BlockPos min, BlockPos max) {
            this.min = min;
            width = (max.getX() - min.getX() + 2) / 2;
            height = (max.getY() - min.getY() + 2) / 2;
            depth = (max.getZ() - min.getZ() + 2) / 2;
            cells = MemoryUtil.memCalloc(Math.multiplyExact(Math.multiplyExact(width, height), depth));
        }

        void block(int x, int y, int z) {
            x -= min.getX();
            y -= min.getY();
            z -= min.getZ();
            int index = x / 2 + width * (y / 2 + height * (z / 2));
            int bit = (x & 1) | (y & 1) << 1 | (z & 1) << 2;
            cells.put(index, (byte) (cells.get(index) | 1 << bit));
        }

        @Override
        public void close() {
            MemoryUtil.memFree(cells);
        }
    }

    DisplayShadows(Grid grid, Vec3[] targets, DisplayBlock.Screen screen, int vertices) {
        min = grid.min;
        this.targets = new float[targets.length * 3];
        for (int i = 0; i < targets.length; i++) {
            this.targets[i * 3] = (float) targets[i].x;
            this.targets[i * 3 + 1] = (float) targets[i].y;
            this.targets[i * 3 + 2] = (float) targets[i].z;
        }
        right = screen.right();
        out = screen.out();
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            visibility = new TextureTarget(WIDTH, Math.max(1, (vertices + WIDTH - 1) / WIDTH), false, Minecraft.ON_OSX);
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
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8UI, grid.width, grid.height, grid.depth, 0,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, grid.cells);
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

    boolean advance(int frame, VertexBuffer[] buffers, int[] counts) {
        int total = counts[0] + counts[1];
        if (shaded == total) {
            releaseVoxels();
            return true;
        }
        if (shader == null) {
            return false;
        }
        int rays = targets.length / 3;
        int count = BUDGET.take(frame, total - shaded, rays);
        if (count == 0) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
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
                shader.safeGetUniform("ScreenRight").set((float) right.x, (float) right.y, (float) right.z);
                shader.safeGetUniform("ScreenOut").set((float) out.x, (float) out.y, (float) out.z);
                shader.safeGetUniform("RayCount").set(rays);
                int end = shaded + count;
                if (shaded < counts[0]) {
                    draw(buffers[0], shaded, Math.min(end, counts[0]) - shaded, 0);
                }
                if (end > counts[0]) {
                    int start = Math.max(shaded, counts[0]);
                    draw(buffers[1], start - counts[0], end - start, counts[0]);
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
        if (shaded == total) {
            releaseVoxels();
            return true;
        }
        return false;
    }

    private void draw(VertexBuffer buffer, int first, int count, int offset) {
        shader.safeGetUniform("VertexOffset").set(offset);
        buffer.bind();
        shader.apply();
        if (targetsProgram != shader.getId()) {
            targetsProgram = shader.getId();
            targetsLocation = GL20.glGetUniformLocation(targetsProgram, "Targets");
        }
        GL20.glUniform3fv(targetsLocation, targets);
        GL11.glDrawArrays(GL11.GL_POINTS, first, count);
    }

    private void releaseVoxels() {
        if (voxels != 0) {
            GL11.glDeleteTextures(voxels);
            voxels = 0;
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
