package org.haruyq.createmediasystems.blocks.display;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.Reports;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Image viewers' pictures, like WaterMedia's images: each link fetched once on this computer, off the render thread,
 * read with Java's ImageIO (PNG and GIF with their transparency, JPEG, BMP; a GIF's first frame), and kept as a
 * texture while something shows it.
 */
final class Images {
    /** How long a picture nothing shows is kept, and how long until a link that failed is tried again. */
    private static final long KEEP_MILLIS = 60_000;
    private static final int MAX_BYTES = 32 << 20;
    /** Largest side and most pixels, checked before reading, so a small file can't claim a picture larger than memory. */
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 4096L * 4096;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Map<String, Picture> PICTURES = new HashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    private Images() {
    }

    /** One link's picture: its texture once loaded, and its size in pixels. Render thread. */
    static final class Picture {
        @Nullable
        ResourceLocation texture;
        int width;
        int height;
        private boolean failed;
        private long lastUsed = Util.getMillis();
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> sweep());
    }

    /** {@code url}'s picture, fetching it first if new; null while it loads, or if it can't be shown. Render thread. */
    @Nullable
    static Picture get(String url) {
        Picture picture = PICTURES.computeIfAbsent(url, Images::load);
        if (!picture.failed) {
            picture.lastUsed = Util.getMillis();
        }
        return picture.texture == null ? null : picture;
    }

    private static Picture load(String url) {
        Picture picture = new Picture();
        CompletableFuture.supplyAsync(() -> {
            try {
                return read(url);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.backgroundExecutor()).whenComplete((image, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                picture.failed = true;
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                Reports.show(Component.translatable("create_media_systems.image.failed",
                        cause.getMessage() == null ? cause.toString() : cause.getMessage()), true);
            } else if (PICTURES.get(url) != picture) {
                image.close(); // let go of while it loaded
            } else {
                picture.width = image.getWidth();
                picture.height = image.getHeight();
                picture.texture = CreateMediaSystems.id("image/" + IDS.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(picture.texture, new Texture(image));
            }
        }));
        return picture;
    }

    private static void sweep() {
        long now = Util.getMillis();
        PICTURES.values().removeIf(picture -> {
            boolean stale = now - picture.lastUsed > KEEP_MILLIS;
            if (stale && picture.texture != null) {
                Minecraft.getInstance().getTextureManager().release(picture.texture);
            }
            return stale;
        });
    }

    private static NativeImage read(String url) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(readable(URI.create(url))).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (CMS)").build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        byte[] bytes;
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            bytes = body.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB");
        }
        return decode(bytes);
    }

    /**
     * {@code uri}, asking Discord's media proxy for a PNG: its links say {@code format=webp}, which ImageIO can't read.
     * ponytail: WebP from any other site still can't be shown; a WebP reader (TwelveMonkeys imageio-webp) if it's needed.
     */
    static URI readable(URI uri) {
        if (!"media.discordapp.net".equalsIgnoreCase(uri.getHost())) {
            return uri;
        }
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().replaceAll("(^|&)format=[^&]*", "");
        String base = uri.toString().replaceFirst("[?#].*", "");
        return URI.create(base + "?" + (query.isEmpty() ? "" : query.replaceFirst("^&", "") + "&") + "format=png");
    }

    /** An image file's first picture, as RGBA pixels with their transparency. */
    static NativeImage decode(byte[] bytes) throws IOException {
        int[] argb;
        int width;
        int height;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("not a PNG, JPEG, GIF or BMP image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw new IOException(width + " × " + height + " pixels is too large");
                }
                argb = reader.read(0).getRGB(0, 0, width, height, null, 0, width);
            } finally {
                reader.dispose();
            }
        }
        NativeImage image = new NativeImage(width, height, false);
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            // ARGB to NativeImage's ABGR: swap red and blue.
            image.setPixelRGBA(i % width, i / width, c & 0xFF00FF00 | c >> 16 & 0xFF | (c & 0xFF) << 16);
        }
        return image;
    }

    /** A picture with its mipmaps, sampled smoothly so a large image shown small doesn't shimmer. */
    private static final class Texture extends AbstractTexture {
        Texture(NativeImage image) {
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight()));
            TextureUtil.prepareImage(getId(), levels, image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, true, false, true);
            bind();
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            setFilter(true, true);
        }

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
