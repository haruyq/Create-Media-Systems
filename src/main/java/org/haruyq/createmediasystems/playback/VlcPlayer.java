package org.haruyq.createmediasystems.playback;

import static org.haruyq.createmediasystems.playback.Vlc.LIB;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.Streams;

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
 * One VLC player. Its audio goes to the streams that tap it (see VlcAudio); with video, its pictures go to a texture
 * a display shows (see Frame). libvlc is only called under {@link #lock}, which {@link #release} holds while it stops
 * and frees the player on a thread of its own, so no call ever reaches a freed player; {@link #status} only tries
 * the lock, since the sound thread asks it on every read.
 */
public final class VlcPlayer implements NativeAudio.Backend {
    private static final ExecutorService RELEASER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("CMS VLC release").factory());

    private final ReentrantLock lock = new ReentrantLock();
    final VlcAudio audio = new VlcAudio();
    @Nullable
    final Frame frame;
    /** Guarded by lock; null before start and once released. */
    @Nullable
    private Pointer player;
    private boolean released;
    private volatile boolean resumed;
    private boolean live;
    /** The length the resolver gave (see NativeMediaResolver.Prepared#duration); 0 if VLC is to tell. */
    private double duration;
    /** See {@link #seconds}; set as status is asked. */
    private volatile double seconds;
    @Nullable

    /** The tallest picture its video plays at (0 for any); unused without video. */
    private final int maxHeight;
    /** What its video's playback took, sent as it is released (see Diagnostics); null without video. */
    @Nullable
    final Diagnostics diagnostics;

    /** A player of {@code video}, or of a record's audio alone if null, asked to play at {@code requestedAt}. */
    VlcPlayer(@Nullable Streams.Video video, long requestedAt) {
        frame = video == null ? null : new Frame();
        maxHeight = video == null ? 0 : MediaSources.height(video);
        diagnostics = video == null ? null : new Diagnostics(video, frame, audio, maxHeight, requestedAt);
    }

    @Override
    public net.minecraft.client.sounds.AudioStream tap(Streams.Channel channel) {
        return audio.open(channel);
    }


    @Override
    public void start(URI source) throws IOException {
        if (diagnostics != null) {
            diagnostics.resolving(source);
        }
        NativeMediaResolver.Prepared media = MediaSources.resolve(source, frame != null, maxHeight);
        if (diagnostics != null) {
            diagnostics.resolved(media.live());
        }
        lock.lock();
        try {
            if (released) {
                return;
            }
            Pointer opened = LIB.libvlc_media_new_location(Vlc.INSTANCE, media.uri().toASCIIString());
            if (opened == null) {
                throw new IOException("The native player could not open this source");
            }
            try {
                for (String option : media.options()) {
                    LIB.libvlc_media_add_option(opened, option);
                }
                // Paused once open, so it can seek to where the others are first (see NativeAudio#open).
                LIB.libvlc_media_add_option(opened, ":start-paused");
                if (frame == null) {
                    // A record neither fetches nor decodes a video track.
                    LIB.libvlc_media_add_option(opened, ":no-video");
                }
                player = LIB.libvlc_media_player_new_from_media(opened);
            } finally {
                LIB.libvlc_media_release(opened);
            }
            if (player == null) {
                throw new IOException("The native player could not open this source");
            }
            // Set before playing: VLC never briefly plays on the computer's own speakers.
            LIB.libvlc_audio_set_callbacks(player, audio.play, null, null, audio.flush, null, null);
            LIB.libvlc_audio_set_format(player, "S16N", Pcm.RATE, VlcAudio.CHANNELS);
            if (frame != null) {
                LIB.libvlc_video_set_format_callbacks(player, frame.setup, null);
                LIB.libvlc_video_set_callbacks(player, frame.lock, frame.unlock, frame.display, null);
            }
            live = media.live();
            duration = media.duration();
            LIB.libvlc_media_player_play(player);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void failed(URI source) {
        MediaSources.forget(source);
    }

    @Override
    public double seconds() {
        return seconds;
    }

    @Override
    public NativeAudio.Status status() {
        if (!lock.tryLock()) {
            return NativeAudio.Status.WAITING;
        }
        try {
            if (player == null) {
                return NativeAudio.Status.BROKEN;
            }
            int state = LIB.libvlc_media_player_get_state(player);
            if (frame != null) {
                frame.measure(player);
            }
            boolean seekable = LIB.libvlc_media_player_is_seekable(player) != 0;
            // Opened paused (start-paused), unless it can't pause (some live streams), when it just plays. Playing
            // while it can pause is the moment before start-paused pauses it: not ready yet, or it would stay paused.
            boolean ready = state == Vlc.Lib.PAUSED
                    || state == Vlc.Lib.PLAYING && (resumed || LIB.libvlc_media_player_can_pause(player) == 0);
            long length = LIB.libvlc_media_player_get_length(player);
            // A video on demand of a known length is one, though HLS has no length until it plays a little.
            boolean isLive = duration <= 0 && NativeAudio.classifyLive(live, ready, length, seekable);
            seconds = duration > 0 ? duration : ready && !isLive ? length / 1000.0 : 0;
            return new NativeAudio.Status(ready, isLive, seekable,
                    state == Vlc.Lib.ENDED || resumed && state == Vlc.Lib.STOPPED, state == Vlc.Lib.ERROR);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void seek(long millis) {
        lock.lock();
        try {
            if (player != null) {
                LIB.libvlc_media_player_set_time(player, millis);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Never waits: asked from the sound thread (see NativeAudio.Shared#correct). */
    @Override
    public long time() {
        if (!lock.tryLock()) {
            return -1;
        }
        try {
            return player == null ? -1 : LIB.libvlc_media_player_get_time(player);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resume() {
        lock.lock();
        try {
            if (player != null) {
                LIB.libvlc_media_player_set_pause(player, 0);
                resumed = true;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release() {
        lock.lock();
        try {
            if (released) {
                return;
            }
            released = true;
        } finally {
            lock.unlock();
        }
        if (diagnostics != null) {
            diagnostics.send();
        }
        // Stopping waits for VLC's threads, which may take a moment: never on the game's or the sound engine's.
        RELEASER.execute(() -> {
            lock.lock();
            try {
                if (player != null) {
                    LIB.libvlc_media_player_stop(player);
                    LIB.libvlc_media_player_release(player);
                    player = null;
                }
            } finally {
                lock.unlock();
            }
            if (frame != null) {
                frame.close();
            }
        });
    }

    /**
     * The player's pictures, RGBA, which VLC writes into memory of ours and the render thread copies into a texture
     * (see {@link #texture}). They take turns on {@link #guard}: an upload never reads a picture half written.
     */
    public static final class Frame {
        private static final AtomicInteger IDS = new AtomicInteger();

        private final Object guard = new Object();
        /** Guarded by guard. */
        @Nullable
        private Memory pixels;
        private int width;
        private int height;
        /** VLC is between lock and unlock, writing a picture. */
        private boolean writing;
        /** A picture VLC finished that the texture doesn't have yet. */
        private volatile boolean fresh;
        /** On the render thread. */
        @Nullable
        private ResourceLocation id;
        @Nullable
        private Texture texture;
        private int textureWidth;
        private int textureHeight;
        private float brightness;
        private volatile int shownWidth;
        private volatile int shownHeight;
        /** When VLC finished its first picture (System.nanoTime); 0 before. */
        volatile long firstPicture;

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
                if (pixels != null) {
                    pixels.close();
                }
                pixels = new Memory((long) w * h * 4);
                pixels.clear();
                this.width = w;
                this.height = h;
            }
            return 1;
        };
        final Vlc.VideoLock lock = (opaque, planes) -> {
            synchronized (guard) {
                writing = true;
                planes.setPointer(0, pixels);
            }
            return null;
        };
        final Vlc.VideoUnlock unlock = (opaque, picture, planes) -> {
            synchronized (guard) {
                writing = false;
            }
        };
        final Vlc.VideoDisplay display = (opaque, picture) -> {
            if (firstPicture == 0) {
                firstPicture = System.nanoTime();
            }
            fresh = true;
        };

        /** The texture with the latest picture, uploading it first if new; null before the first. Render thread. */
        @Nullable
        public ResourceLocation texture() {
            if (fresh) {
                synchronized (guard) {
                    if (!writing && pixels != null) {
                        upload();
                        fresh = false;
                    }
                }
            }
            return shown();
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
        private void measure(Pointer player) {
            IntByReference w = new IntByReference();
            IntByReference h = new IntByReference();
            if (LIB.libvlc_video_get_size(player, 0, w, h) == 0 && w.getValue() > 0 && h.getValue() > 0) {
                shownWidth = w.getValue();
                shownHeight = h.getValue();
            }
        }

        private void upload() {
            if (texture == null) {
                texture = new Texture();
                id = CreateMediaSystems.id("video/" + IDS.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(id, texture);
            }
            GlStateManager._bindTexture(texture.getId());
            if (textureWidth != width || textureHeight != height) {
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
                textureWidth = width;
                textureHeight = height;
            }
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, Pointer.nativeValue(pixels));
            // For the picture's own sampling, and a display's glow samples a small mipmap as its blur (see DisplayRenderer).
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            // ponytail: 8 × 8 pixels' luma, plenty for a light level 0..15; average the mipmap's last level if not.
            long sum = 0;
            for (int i = 1; i < 16; i += 2) {
                for (int j = 1; j < 16; j += 2) {
                    long at = ((long) (i * height / 16) * width + j * width / 16) * 4;
                    sum += (pixels.getByte(at) & 0xFF) * 54 + (pixels.getByte(at + 1) & 0xFF) * 183
                            + (pixels.getByte(at + 2) & 0xFF) * 19;
                }
            }
            brightness = sum / (64f * 256 * 255);
        }

        /** How bright the latest picture is, 0..1, once {@link #texture} has one. Render thread. */
        public float brightness() {
            return brightness;
        }

        /** The player is gone: frees the pictures now and the texture on the render thread. */
        private void close() {
            synchronized (guard) {
                if (pixels != null) {
                    pixels.close();
                    pixels = null;
                }
                fresh = false;
            }
            Minecraft.getInstance().execute(() -> {
                if (id != null) {
                    Minecraft.getInstance().getTextureManager().release(id);
                }
                textureWidth = 0;
            });
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
}
