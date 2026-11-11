package org.haruyq.createmediasystems.lighting;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;

/**
 * Beam strips expanded and clipped on the GPU from their corners, colours and hit planes, drawn with the stage
 * lights' beams (LightingRenderer.beamType()), after block entities. Other mods' fixtures draw their beams here too
 * (strip, while available): a strip is a quad from its near edge (a, b) to its far edge (d, c), faded along it
 * (Fade) and cut by up to three planes (Clip), as a beam stops at the blocks it meets.
 */
public final class LightingBeams {
    static final VertexFormat FORMAT = VertexFormat.builder().add("Position", VertexFormatElement.POSITION).build();
    private static final int ROWS = 1024;
    private static final int FLOATS = LightingGpu.ROW_FLOATS;
    private static final int VERTICES = LightingGpu.ROW_VERTICES;
    private static final List<Batch> BATCHES = new ArrayList<>();
    private static int program;
    private static int sampler;
    private static int computeArray;
    private static long frame = -1;
    private static int batch;
    private static boolean pending;

    private LightingBeams() {
    }

    static void setShader(ShaderInstance loaded) {
        // A private program preserves transform feedback when Veil recompiles Minecraft's shaders.
        int next = GL20.glCreateProgram();
        int[] attached = new int[GL20.glGetProgrami(loaded.getId(), GL20.GL_ATTACHED_SHADERS)];
        GL20.glGetAttachedShaders(loaded.getId(), (int[]) null, attached);
        for (int part : attached) {
            GL20.glAttachShader(next, part);
        }
        GL30.glTransformFeedbackVaryings(next, new String[] {"Point", "PackedColor"}, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(next);
        if (GL20.glGetProgrami(next, GL20.GL_LINK_STATUS) == 0) {
            String error = GL20.glGetProgramInfoLog(next);
            GL20.glDeleteProgram(next);
            throw new IllegalStateException(error);
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        program = next;
        sampler = GL20.glGetUniformLocation(program, "Sampler0");
    }

    /**
     * Whether strips for {@code level} are drawn on the GPU now: in the client's world, with the beam program
     * loaded (it can fail to load, under some shader packs). If not, draw beams in LightingRenderer.beamBuffers.
     */
    public static boolean available(Level level) {
        return program != 0 && WorldEvents.isClientWorld(level);
    }

    /**
     * How a strip fades along it, from its near edge to its far one: stage_beam.vsh's modes, in order. Drawn on the
     * CPU (LightingRenderer, where the GPU can't), it fades the same way, through steps, near and far.
     */
    public enum Fade {
        /** In eight steps, to a quarter: a beam's cone. */
        CONE,
        /** In eight steps, more gently, never quite out: a laser's sheet. */
        SHEET,
        /** Not at all, in one piece: a ray. */
        SOLID;

        int steps() {
            return this == SOLID ? 1 : LightingGpu.STEPS;
        }

        /** Its alpha at the near edge of step {@code step}, from {@code alpha} at its own. */
        int near(float alpha, int step) {
            return switch (this) {
                case CONE -> (int) Math.floor(alpha * (1 - step / (float) LightingGpu.STEPS * LightingGpu.CONE_FADE));
                case SHEET -> (int) Math.floor(alpha * (1 - step / (float) LightingGpu.SHEET_FADE_STEPS) + 0.5f);
                case SOLID -> (int) Math.floor(alpha + 0.5f);
            };
        }

        /** Its alpha at the far edge of step {@code step}. */
        int far(float alpha, int step) {
            return switch (this) {
                case CONE -> near(alpha, step + 1);
                case SHEET -> Math.max(1, near(alpha, step) - LightingGpu.SHEET_FAR_DROP);
                case SOLID -> near(alpha, step);
            };
        }
    }

    /** A plane a strip is cut at: the side its normal points to, its source's, is kept. */
    public record Clip(Vec3 point, Vec3 normal) {
        /** The plane through {@code point} across {@code normal}, the part toward {@code source} kept. */
        public static Clip toward(Vec3 point, Vec3 normal, Vec3 source) {
            Vec3 n = normal.normalize();
            return new Clip(point, source.subtract(point).dot(n) < 0 ? n.scale(-1) : n);
        }

        /** How far {@code at} is on the kept side (negative: cut away). */
        public double distance(Vec3 at) {
            return (at.x - point.x) * normal.x + (at.y - point.y) * normal.y + (at.z - point.z) * normal.z;
        }
    }

    /**
     * A strip this frame: {@code a} to {@code b} its near edge, {@code d} to {@code c} its far one, in the space
     * {@code matrix} takes into the camera's (a block entity renderer's pose); {@code colorA} along a to d,
     * {@code colorB} along b to c; {@code alpha} at its near edge, 0 to 255; kept on the source's side of each clip
     * (null for none). Render thread, while {@link #available(Level)}.
     */
    public static void strip(Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int colorA, int colorB, Fade fade,
                             float alpha, @Nullable Clip first, @Nullable Clip second, @Nullable Clip third) {
        long now = WorldEvents.frame();
        if (frame != now) {
            frame = now;
            batch = 0;
            BATCHES.forEach(buffer -> buffer.rows = 0);
        }
        if (batch < BATCHES.size() && BATCHES.get(batch).rows == ROWS) {
            batch++;
        }
        if (batch == BATCHES.size()) {
            BATCHES.add(new Batch());
        }
        Batch buffer = BATCHES.get(batch);
        FloatBuffer data = buffer.data;
        int at = buffer.rows++ * FLOATS;
        int corners = at + LightingGpu.TEXEL_CORNERS * 4;
        point(data, corners, a);
        point(data, corners + 4, b);
        point(data, corners + 8, c);
        point(data, corners + 12, d);
        int planes = at + LightingGpu.TEXEL_PLANES * 4;
        plane(data, planes, first);
        plane(data, planes + 4, second);
        plane(data, planes + 8, third);
        color(data, at + LightingGpu.TEXEL_COLORS * 4, colorA);
        color(data, at + LightingGpu.TEXEL_COLORS * 4 + 4, colorB);
        data.put(at + LightingGpu.TEXEL_SETTINGS * 4, fade.ordinal()).put(at + LightingGpu.TEXEL_SETTINGS * 4 + 1, alpha);
        matrix.get(at + LightingGpu.TEXEL_MATRIX * 4, data);
        pending = true;
    }

    static boolean pending() {
        return pending;
    }

    private static void point(FloatBuffer data, int at, Vec3 point) {
        data.put(at, (float) point.x).put(at + 1, (float) point.y).put(at + 2, (float) point.z);
    }

    private static void color(FloatBuffer data, int at, int color) {
        data.put(at, (color >> 16 & 255) / 255f).put(at + 1, (color >> 8 & 255) / 255f)
                .put(at + 2, (color & 255) / 255f);
    }

    static void plane(FloatBuffer data, int at, @Nullable Clip plane) {
        if (plane == null) {
            data.put(at, 0).put(at + 1, 0).put(at + 2, 0).put(at + 3, 1);
        } else {
            point(data, at, plane.normal());
            data.put(at + 3, (float) (-plane.normal().dot(plane.point()) - LightingGeometry.CLEARANCE));
        }
    }

    static void draw() {
        if (!pending) {
            return;
        }
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        for (Batch buffer : BATCHES) {
            if (buffer.rows > 0) {
                buffer.upload();
                buffer.expand();
                buffer.draw(view, projection);
                buffer.rows = 0;
            }
        }
        batch++;
        pending = false;
    }

    static void close() {
        BATCHES.forEach(Batch::close);
        BATCHES.clear();
        if (computeArray != 0) {
            GlStateManager._glDeleteVertexArrays(computeArray);
            computeArray = 0;
        }
        batch = 0;
        frame = -1;
        pending = false;
    }

    private static final class Batch implements AutoCloseable {
        final FloatBuffer data = MemoryUtil.memCallocFloat(ROWS * FLOATS);
        int rows;
        int drawn;
        int capacity;
        int texture;
        int parameters;
        int vertices;
        int array;

        void upload() {
            if (vertices == 0) {
                vertices = GlStateManager._glGenBuffers();
                array = GlStateManager._glGenVertexArrays();
                int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                GlStateManager._glBindVertexArray(array);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
                DefaultVertexFormat.POSITION_COLOR.setupBufferState();
                GlStateManager._glBindVertexArray(previousArray);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            }
            int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                if (parameters == 0) {
                    parameters = GlStateManager._glGenBuffers();
                    texture = GlStateManager._genTexture();
                }
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, parameters);
                data.limit(rows * FLOATS);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, parameters);
                drawn = rows;
            } finally {
                data.clear();
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
            }
        }

        void expand() {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int feedback = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
            long start = feedback == 0 ? 0 : GL32.glGetInteger64i(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START, 0);
            long size = feedback == 0 ? 0 : GL32.glGetInteger64i(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE, 0);
            int buffer = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean discard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            try {
                if (computeArray == 0) {
                    computeArray = GlStateManager._glGenVertexArrays();
                }
                GlStateManager._glBindVertexArray(computeArray);
                GlStateManager._glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, vertices);
                capacity = Math.max(capacity, Math.max(1, Integer.highestOneBit(drawn - 1) * 2));
                GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, (long) capacity * VERTICES * 16, GL15.GL_STREAM_DRAW);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, vertices);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
                GlStateManager._glUseProgram(LightingBeams.program);
                GL20.glUniform1i(sampler, 0);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                try {
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, drawn * VERTICES);
                } finally {
                    GL30.glEndTransformFeedback();
                }
            } finally {
                if (!discard) {
                    GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                }
                if (feedback == 0) {
                    GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
                } else {
                    GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, feedback, start, size);
                }
                GlStateManager._glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, buffer);
                GlStateManager._glUseProgram(program);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
                RenderSystem.activeTexture(active);
                GlStateManager._glBindVertexArray(previousArray);
            }
        }

        void draw(Matrix4f view, Matrix4f projection) {
            if (vertices == 0) {
                return;
            }
            RenderType type = LightingRenderer.beamType();
            type.setupRenderState();
            int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            ShaderInstance drawing = RenderSystem.getShader();
            try {
                GlStateManager._glBindVertexArray(array);
                drawing.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, view, projection, Minecraft.getInstance().getWindow());
                drawing.apply();
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, drawn * VERTICES);
            } finally {
                drawing.clear();
                GlStateManager._glBindVertexArray(previousArray);
                type.clearRenderState();
            }
        }

        @Override
        public void close() {
            MemoryUtil.memFree(data);
            if (vertices != 0) {
                GlStateManager._glDeleteBuffers(vertices);
                GlStateManager._glDeleteVertexArrays(array);
                vertices = 0;
                array = 0;
            }
            if (texture != 0) {
                GlStateManager._deleteTexture(texture);
                GlStateManager._glDeleteBuffers(parameters);
            }
        }
    }
}
