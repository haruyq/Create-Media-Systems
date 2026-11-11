package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.PlaylistView;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * What one display playback took on this computer, sent to the API's {@code POST /diagnostics} (see API.md) to find
 * why videos start sooner for some players than for others: when each step was done (ms after the server said to
 * play it), how far it played off where it should be, whether it was played or kept before, this computer's client
 * config, versions, whether yt-dlp has YouTube cookies (never what they are), and the end of latest.log without chat.
 * Sent once: {@link #REPORT_AFTER_NANOS} into playing, or as its player is released first (stopped or failed).
 */
final class Diagnostics {
    static final long REPORT_AFTER_NANOS = 30_000_000_000L;
    private static final int LOG_BYTES = 128 * 1024;
    /** Pages displays played here since the game started. */
    // ponytail: never evicted, one entry per video played since start; cap it if that grows.
    private static final Set<String> PLAYED = ConcurrentHashMap.newKeySet();

    private final Streams.Video video;
    @Nullable
    private final VideoFrame frame;
    private final VlcAudio audio;
    private final int maxHeight;
    private final long requestedAt;
    private final Instant requestedWall;
    private final AtomicBoolean sent = new AtomicBoolean();
    @Nullable
    private volatile URI source;
    private volatile boolean playedBefore;
    private volatile boolean videoCached;
    private volatile boolean resolveCached;
    private volatile boolean live;
    private volatile boolean toldByServer;
    private volatile double targetSeconds;
    private volatile long resolved;
    private volatile long ready;
    volatile long playing;
    @Nullable
    private volatile String failure;
    /** Measured on the sound thread (see NativeAudio.Playback#correct); Long.MIN_VALUE until the first check. */
    private volatile long firstDrift = Long.MIN_VALUE;
    private volatile long maxDrift;
    private volatile int seeks;

    /**
     * Of the player whose pictures go to {@code frame} and sound to {@code audio}; {@code requestedAt}: when the
     * server's word to play arrived (System.nanoTime).
     */
    Diagnostics(Streams.Video video, @Nullable VideoFrame frame, VlcAudio audio, int maxHeight, long requestedAt) {
        this.video = video;
        this.frame = frame;
        this.audio = audio;
        this.maxHeight = maxHeight;
        this.requestedAt = requestedAt;
        requestedWall = Instant.now().minusNanos(System.nanoTime() - requestedAt);
    }

    /** Before resolving {@code source}: whether it was played, kept or resolved here already. */
    void resolving(URI source) {
        this.source = source;
        playedBefore = !PLAYED.add(source.toString());
        videoCached = VideoCache.get().has(source, maxHeight);
        resolveCached = NativeMediaResolver.cached(source, false, maxHeight);
    }

    void resolved(boolean live) {
        this.live = live;
        resolved = System.nanoTime();
    }

    /** VLC has it open, paused. */
    void ready() {
        ready = System.nanoTime();
    }

    /** Told to play from {@code targetSeconds} in, by the server's word or (not {@code toldByServer}) its own guess. */
    void playing(boolean toldByServer, double targetSeconds) {
        this.toldByServer = toldByServer;
        this.targetSeconds = targetSeconds;
        playing = System.nanoTime();
    }

    void failed(String reason) {
        failure = reason;
    }

    /** How far behind (+) or ahead (-) of where it should be it plays, in ms; whether that made it seek. */
    void drift(long millis, boolean sought) {
        if (firstDrift == Long.MIN_VALUE) {
            firstDrift = millis;
        }
        maxDrift = Math.max(maxDrift, Math.abs(millis));
        if (sought) {
            seeks++;
        }
    }

    /** Sends it, once, if this computer's player allows (sendDiagnostics). */
    void send() {
        if (!ClientConfig.SEND_DIAGNOSTICS.get() || sent.getAndSet(true)) {
            return;
        }
        long firstPicture = frame == null ? 0 : frame.firstPicture;
        long firstSamples = audio.firstSamples;
        String outcome = failure != null ? "failed" : playing == 0 ? "stopped before playing"
                : System.nanoTime() - playing < REPORT_AFTER_NANOS ? "stopped while playing" : "playing";
        // The playlist and the player's name are the game's: read on its thread; the files and the API off it (this
        // may be the sound thread).
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            JsonObject report = new JsonObject();
            report.addProperty("player", mc.getUser().getName());
            if (mc.level != null && mc.level.getBlockEntity(video.player()) instanceof PlaylistView disc) {
                report.addProperty("playlistIndex", disc.index());
                report.addProperty("playlistSize", disc.playlist().size());
            }
            CompletableFuture.runAsync(() -> post(report(report, outcome, firstPicture, firstSamples)),
                    Util.nonCriticalIoPool());
        });
    }

    private JsonObject report(JsonObject report, String outcome, long firstPicture, long firstSamples) {
        report.addProperty("version", ModList.get().getModContainerById(CreateMediaSystems.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("?"));
        report.addProperty("requestedAt", OffsetDateTime.ofInstant(requestedWall, ZoneId.systemDefault()).toString());
        report.addProperty("url", source == null ? null : source.toString());
        JsonArray pos = new JsonArray();
        pos.add(video.player().getX());
        pos.add(video.player().getY());
        pos.add(video.player().getZ());
        report.add("bluRayPlayer", pos);
        report.addProperty("outcome", outcome);
        report.addProperty("failure", failure);
        report.addProperty("playedBeforeThisSession", playedBefore);
        report.addProperty("videoCacheHit", videoCached);
        report.addProperty("resolveCacheHit", resolveCached);
        report.addProperty("live", live);
        report.addProperty("maxHeight", maxHeight);
        JsonObject millis = new JsonObject();
        millis.addProperty("resolved", since(resolved));
        millis.addProperty("ready", since(ready));
        millis.addProperty("playing", since(playing));
        millis.addProperty("firstPicture", since(firstPicture));
        millis.addProperty("firstSound", since(firstSamples));
        report.add("millisAfterRequest", millis);
        report.addProperty("toldByServer", toldByServer);
        report.addProperty("targetSeconds", targetSeconds);
        report.addProperty("firstDriftMillis", firstDrift == Long.MIN_VALUE ? null : firstDrift);
        report.addProperty("maxDriftMillis", maxDrift);
        report.addProperty("seeks", seeks);
        report.addProperty("clientConfig", read(FMLPaths.CONFIGDIR.get().resolve(ClientConfig.FILE_NAME)));
        JsonObject tools = new JsonObject();
        Tools.versions().forEach(tools::addProperty);
        report.add("tools", tools);
        report.add("youtubeCookies", cookies());
        report.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.addProperty("java", System.getProperty("java.version"));
        report.addProperty("log", log(FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log")));
        return report;
    }

    /** ms from the request to {@code at} (System.nanoTime); null if it never came. */
    @Nullable
    private Long since(long at) {
        return at == 0 ? null : (at - requestedAt) / 1_000_000;
    }

    /** Whether yt-dlp has YouTube cookies, and since when; never what they are. */
    private static JsonObject cookies() {
        JsonObject cookies = new JsonObject();
        cookies.addProperty("receiving", ClientConfig.YOUTUBE_COOKIES.get());
        boolean present = Files.isRegularFile(CookieReceiver.FILE);
        cookies.addProperty("present", present);
        try {
            cookies.addProperty("receivedAt", present ? Files.getLastModifiedTime(CookieReceiver.FILE).toString() : null);
        } catch (IOException unreadable) {
            // present, of unknown age
        }
        return cookies;
    }

    @Nullable
    private static String read(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException missing) {
            return null;
        }
    }

    /** The last {@link #LOG_BYTES} of {@code file}, whole lines only, without chat (other players' words). */
    @Nullable
    static String log(Path file) {
        try (FileChannel channel = FileChannel.open(file)) {
            long size = channel.size();
            channel.position(Math.max(0, size - LOG_BYTES));
            ByteBuffer tail = ByteBuffer.allocate((int) Math.min(size, LOG_BYTES));
            while (tail.hasRemaining() && channel.read(tail) > 0) {
                // until it has the tail
            }
            String text = new String(tail.array(), 0, tail.position(), StandardCharsets.UTF_8);
            if (size > LOG_BYTES) {
                text = text.substring(text.indexOf('\n') + 1);
            }
            return text.lines().filter(line -> !line.contains("[CHAT]")).collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static void post(JsonObject report) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ApiHttp.api(ServerConfig.API_URL.get(), "diagnostics")))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(report.toString())).build();
            int status = NativeMediaResolver.HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) {
                LogUtils.getLogger().info("The API did not take playback diagnostics (HTTP {})", status);
            }
        } catch (IOException | IllegalArgumentException failed) {
            LogUtils.getLogger().info("Could not send playback diagnostics: {}", failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
