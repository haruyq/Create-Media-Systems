package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.haruyq.createmediasystems.Downloads;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.stream.AudioClips;
import org.haruyq.createmediasystems.stream.MediaKind;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Plays a server's audio clips ({@link MediaKind#CLIP}): each is fetched from the server once, checked against its
 * hash, and kept as a file for VLC to play until this client leaves the server.
 */
final class AudioClipClient {
    private static final Path DIR = ClientFiles.DIR.resolve("clips");
    private static final long WAIT_SECONDS = 60;
    /** The clips on their way or here, by hash; a failed one is dropped so a later play asks again. */
    private static final Map<String, CompletableFuture<Path>> CLIPS = new HashMap<>();
    /** The parts of each clip received so far. */
    private static final Map<String, byte[][]> PARTS = new HashMap<>();

    private AudioClipClient() {
    }

    static void register() {
        MediaSources.opens(MediaKind.CLIP, AudioClipClient::open);
        AudioClips.client(AudioClipClient::part);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    private static MediaSources.Opened open(URI link, double startSeconds) throws IOException {
        String hash = link.toString().substring(MediaKind.CLIP_SCHEME.length());
        CompletableFuture<Path> clip;
        synchronized (AudioClipClient.class) {
            clip = CLIPS.get(hash);
            if (clip == null) {
                CLIPS.put(hash, clip = new CompletableFuture<>());
                Minecraft.getInstance().execute(() -> PacketDistributor.sendToServer(new AudioClips.Ask(hash)));
            }
        }
        Path file;
        try {
            file = clip.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Clip loading was cancelled");
        } catch (TimeoutException | ExecutionException failed) {
            fail(hash, clip);
            throw new IOException(failed instanceof TimeoutException ? "The server didn't send the audio clip"
                    : failed.getCause().getMessage());
        }
        return new MediaSources.Opened(file.toUri(), 0, startSeconds <= 0 ? List.of()
                : List.of(String.format(Locale.ROOT, ":start-time=%.3f", startSeconds)));
    }

    /** A part of a clip from the server; once all are here, the clip is checked and kept. */
    private static synchronized void part(AudioClips.Part part) {
        CompletableFuture<Path> clip = CLIPS.get(part.hash());
        if (clip == null || clip.isDone()) {
            return;
        }
        if (part.count() <= 0 || part.count() > AudioClips.MAX_PARTS
                || part.index() < 0 || part.index() >= part.count()) {
            PARTS.remove(part.hash());
            fail(part.hash(), clip);
            clip.completeExceptionally(new IOException("The server has no such audio clip"));
            return;
        }
        byte[][] parts = PARTS.computeIfAbsent(part.hash(), hash -> new byte[part.count()][]);
        if (parts.length != part.count()) {
            return;
        }
        parts[part.index()] = part.bytes();
        for (byte[] each : parts) {
            if (each == null) {
                return;
            }
        }
        PARTS.remove(part.hash());
        int length = 0;
        for (byte[] each : parts) {
            length += each.length;
        }
        byte[] bytes = new byte[length];
        int at = 0;
        for (byte[] each : parts) {
            System.arraycopy(each, 0, bytes, at, each.length);
            at += each.length;
        }
        if (!Downloads.sha256(bytes).equals(part.hash())) {
            fail(part.hash(), clip);
            clip.completeExceptionally(new IOException("The audio clip arrived damaged"));
            return;
        }
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(part.hash() + ".clip"), written = DIR.resolve(part.hash() + ".part");
            Files.write(written, bytes);
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            clip.complete(file);
        } catch (IOException failed) {
            LogUtils.getLogger().warn("Could not keep an audio clip", failed);
            fail(part.hash(), clip);
            clip.completeExceptionally(new IOException("Could not keep the audio clip"));
        }
    }

    private static synchronized void fail(String hash, CompletableFuture<Path> clip) {
        CLIPS.remove(hash, clip);
    }

    /** Leaving the server: its clips go, and anything still waiting on one gives up. */
    private static synchronized void clear() {
        CLIPS.values().forEach(clip -> clip.completeExceptionally(new IOException("Left the server")));
        CLIPS.clear();
        PARTS.clear();
        try (var files = Files.list(DIR)) {
            files.forEach(file -> file.toFile().delete());
        } catch (IOException noneKept) {
            // Nothing was kept, or it goes next time.
        }
    }
}
