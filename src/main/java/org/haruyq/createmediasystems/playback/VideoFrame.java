package org.haruyq.createmediasystems.playback;

import static org.haruyq.createmediasystems.playback.Vlc.LIB;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Rgb;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * A native player's pictures, RGBA, which VLC writes into memory of ours and the render thread copies into a texture
 * (see {@link #texture}). Two pictures: VLC writes into the back one while the render thread copies the other,
 * the last VLC finished, without holding {@link #guard}, so neither waits on the other's copy.
 */
public final class VideoFrame {
    private static final AtomicInteger IDS = new AtomicInteger();

    private final Object guard = new Object();
    /** Guarded by guard: buffers[back] is VLC's to write, the other holds the latest picture. */
    private final Memory[] buffers = new Memory[2];
    private int back;
    private int width;
    private int height;
    /** VLC is between lock and unlock, writing a picture. */
    private boolean writing;
    /** The render thread is copying the latest picture into a texture; it stays where it is till done. */
    private boolean reading;
    /** VLC finished a picture while reading: it's still in the back buffer, swapped in once the copy is done. */
    private boolean missed;
    /** A picture VLC finished that the texture doesn't have yet. */
    private volatile boolean fresh;
    /**
     * On the render thread. Two textures taking turns: a new picture goes into the one not drawn last frame, so
     * the driver needn't wait for the GPU to finish reading it first.
     */
    private final ResourceLocation[] ids = new ResourceLocation[2];
    private final Texture[] textures = new Texture[2];
    private final int[] allocated = new int[4];
    private int current = 1;
    /** The texture with the latest picture (one of ids), and its size. */
    @Nullable
    private ResourceLocation id;
    private int textureWidth;
    private int textureHeight;
    private float brightness;
    private volatile int shownWidth;
    private volatile int shownHeight;
    /** When VLC finished its first picture (System.nanoTime); 0 before. */
    volatile long firstPicture;
    volatile long lastPicture;
    /** The player's taps, offered each picture as it is shown. */
    private final MediaTaps taps;

    VideoFrame(MediaTaps taps) {
        this.taps = taps;
    }

    // Held here: VLC calls them as long as it plays.
    final Vlc.VideoSetup setup = (opaque, chroma, width, height, pitches, lines) -> {
        int w = width.getInt(0);
        int h = height.getInt(0);
        if (w <= 0 || h <= 0) {
            return 0;
        }
        chroma.write(0, "RGBA".getBytes(StandardCharsets.US_ASCII), 0, 4);
        pitches.setInt(0, w * 4);
        lines.setInt(0, h);
        synchronized (guard) {
            if (!awaitReading()) {
                return 0;
            }
            for (int i = 0; i < 2; i++) {
                if (buffers[i] != null) {
                    buffers[i].close();
                }
                buffers[i] = new Memory((long) w * h * 4);
                buffers[i].clear();
            }
            back = 0;
            missed = false;
            fresh = false;
            this.width = w;
            this.height = h;
            lastPicture = 0;
        }
        return 1;
    };
    final Vlc.VideoLock lock = (opaque, planes) -> {
        synchronized (guard) {
            missed = false;
            writing = true;
            planes.setPointer(0, buffers[back]);
        }
        return null;
    };
    final Vlc.VideoUnlock unlock = (opaque, picture, planes) -> {
        synchronized (guard) {
            writing = false;
        }
    };
    final Vlc.VideoDisplay display = (opaque, picture) -> {
        long now = System.nanoTime();
        lastPicture = now;
        if (firstPicture == 0) {
            firstPicture = now;
        }
        synchronized (guard) {
            Memory written = buffers[back];
            if (written == null) {
                return;
            }
            if (reading) {
                missed = true;
            } else {
                back ^= 1;
                fresh = true;
            }
            offer(written);
        }
    };

    /** Offers its taps the picture VLC just wrote into {@code written}; under guard. */
    private void offer(Memory written) {
        taps.picture(width, height, index -> {
            long at = (long) index * 4;
            return (written.getByte(at) & 255) << 16 | (written.getByte(at + 1) & 255) << 8 | written.getByte(at + 2) & 255;
        });
    }

    /** The texture with the latest picture, uploading it first if new; null before the first. Render thread. */
    @Nullable
    public ResourceLocation texture() {
        Picture latest = beginRead();
        if (latest != null) {
            try {
                upload(latest.pixels(), latest.width(), latest.height());
            } finally {
                endRead();
            }
        }
        return shown();
    }

    /** A picture VLC finished, {@code width} by {@code height} RGBA pixels. */
    record Picture(Memory pixels, int width, int height) {
    }

    /**
     * The picture VLC finished since the last read, if any, kept from VLC till {@link #endRead}: VLC meanwhile
     * writes the next into the other buffer.
     */
    @Nullable
    Picture beginRead() {
        if (!fresh) {
            return null;
        }
        synchronized (guard) {
            Memory latest = buffers[back ^ 1];
            if (!fresh || latest == null) {
                return null;
            }
            fresh = false;
            reading = true;
            return new Picture(latest, width, height);
        }
    }

    /** Done with the picture {@link #beginRead} gave; one VLC finished meanwhile becomes the next to read. */
    void endRead() {
        synchronized (guard) {
            reading = false;
            if (missed) {
                missed = false;
                if (!writing) {
                    back ^= 1;
                    fresh = true;
                }
            }
            guard.notifyAll();
        }
    }

    /** Waits, under guard, till the render thread is done with the latest picture; false if interrupted. */
    private boolean awaitReading() {
        while (reading) {
            try {
                guard.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** The texture as last uploaded, without a newer picture: for a display out of sight. Render thread. */
    @Nullable
    public ResourceLocation shown() {
        return textureWidth > 0 ? id : null;
    }

    /**
     * The picture's own size, once {@link #texture} has one. VLC's pictures can be a few rows taller than the
     * video (the decoder's padding), with the video stretched to fill them: drawn at this size, it looks right.
     */
    public int width() {
        return shownWidth > 0 ? shownWidth : textureWidth;
    }

    public int height() {
        return shownHeight > 0 ? shownHeight : textureHeight;
    }

    /** Asks VLC the picture's own size (see {@link #width}); under the player's lock. */
    void measure(Pointer player) {
        IntByReference w = new IntByReference();
        IntByReference h = new IntByReference();
        if (LIB.libvlc_video_get_size(player, 0, w, h) == 0 && w.getValue() > 0 && h.getValue() > 0) {
            shownWidth = w.getValue();
            shownHeight = h.getValue();
        }
    }

    private void upload(Memory pixels, int width, int height) {
        int next = current ^ 1;
        Texture texture = textures[next];
        if (texture == null) {
            texture = textures[next] = new Texture();
            ids[next] = CreateMediaSystems.id("video/" + IDS.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(ids[next], texture);
        }
        GlStateManager._bindTexture(texture.getId());
        if (allocated[next * 2] != width || allocated[next * 2 + 1] != height) {
            texture.setFilter(true, true);
            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                // Sharp at a slant too, where mipmaps alone blur text; past the GPU's own maximum, it takes that.
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D,
                        EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, 16f);
            }
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, null);
            allocated[next * 2] = width;
            allocated[next * 2 + 1] = height;
        }
        current = next;
        id = ids[next];
        textureWidth = width;
        textureHeight = height;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, Pointer.nativeValue(pixels));
        // For the picture's own sampling, and a display's glow samples a small mipmap as its blur (see DisplayRenderer).
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        // ponytail: 8 × 8 pixels' luma, plenty for a light level 0..15; average the mipmap's last level if not.
        float sum = 0;
        for (int i = 1; i < 16; i += 2) {
            for (int j = 1; j < 16; j += 2) {
                long at = ((long) (i * height / 16) * width + j * width / 16) * 4;
                sum += Rgb.luma((pixels.getByte(at) & 0xFF) << 16 | (pixels.getByte(at + 1) & 0xFF) << 8
                        | pixels.getByte(at + 2) & 0xFF);
            }
        }
        brightness = sum / (64f * 255);
    }

    /** How bright the latest picture is, 0..1, once {@link #texture} has one. Render thread. */
    public float brightness() {
        return brightness;
    }

    /** The player is gone: frees the pictures now and the texture on the render thread. */
    void close() {
        freePictures();
        Minecraft.getInstance().execute(() -> {
            for (ResourceLocation each : ids) {
                if (each != null) {
                    Minecraft.getInstance().getTextureManager().release(each);
                }
            }
            textureWidth = 0;
        });
    }

    /** Frees the pictures, once the render thread is done reading one. */
    void freePictures() {
        synchronized (guard) {
            // Interrupted, left to JNA's cleaner: freed now, the copy under way would read freed memory.
            boolean idle = awaitReading();
            for (int i = 0; i < 2; i++) {
                if (idle && buffers[i] != null) {
                    buffers[i].close();
                }
                buffers[i] = null;
            }
            fresh = false;
            lastPicture = 0;
        }
    }

    /** Sampled through its mipmaps, so a video shown smaller than it is doesn't shimmer into noise. */
    private static final class Texture extends AbstractTexture {
        /** Render types ask for unfiltered pictures, as they would for text; this one always keeps its own. */
        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, true);
        }

        @Override
        public void load(ResourceManager resourceManager) {
        }
    }
}
