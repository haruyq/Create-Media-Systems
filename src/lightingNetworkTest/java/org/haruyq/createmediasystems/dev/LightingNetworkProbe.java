package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.haruyq.createmediasystems.lighting.AnalysisFrame;
import org.haruyq.createmediasystems.lighting.AnalysisTimeline;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "create_media_systems", value = Dist.CLIENT)
public final class LightingNetworkProbe {
    private static final long PERIOD = 200_000_000L;
    private static long next;
    private static BufferedWriter output;
    private static Path outputPath;
    private static boolean warned;

    private LightingNetworkProbe() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!LightingLab.NETWORK) {
            return;
        }
        long now = System.nanoTime();
        if (now < next) {
            return;
        }
        next = now + PERIOD;
        try {
            write(now);
        } catch (ReflectiveOperationException | IOException failure) {
            if (!warned) {
                warned = true;
                LogUtils.getLogger().warn("Lighting network probe disabled after diagnostic failure", failure);
            }
        }
    }

    private static void write(long now) throws ReflectiveOperationException, IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Field playingField = field("org.haruyq.createmediasystems.lighting.AnalysisPlayback", "PLAYING");
        Map<?, ?> playing = (Map<?, ?>) playingField.get(null);
        for (Object playback : playing.values()) {
            Object state = get(playback, "state");
            if (state == null) {
                continue;
            }
            String track = safeTrack((String) get(state, "track"));
            long position = (long) invoke(playback, "samplePosition");
            @SuppressWarnings("unchecked")
            Map<String, AnalysisTimeline> tracks = (Map<String, AnalysisTimeline>) get(playback, "tracks");
            AnalysisTimeline timeline = tracks.get((String) get(state, "track"));
            long delay = delay(state, position);
            AnalysisFrame sample = timeline == null ? null : timeline.sample(position, delay);
            AnalysisFrame raw = timeline == null ? null : latest(timeline);
            line(now, minecraft, state, playback, track, position, delay, timeline, sample, raw);
        }
    }

    private static void line(long now, Minecraft minecraft, Object state, Object playback, String track, long position,
                             long delay, AnalysisTimeline timeline, AnalysisFrame sample, AnalysisFrame raw) throws IOException, ReflectiveOperationException {
        if (output == null) {
            outputPath = minecraft.gameDirectory.toPath().resolve("lighting-network.jsonl");
            if (Files.exists(outputPath) && Files.size(outputPath) > 20_000_000) {
                Files.move(outputPath, outputPath.resolveSibling("lighting-network.previous.jsonl"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            output = Files.newBufferedWriter(outputPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        StringBuilder json = new StringBuilder(512);
        json.append('{').append("\"wallNanos\":").append(now).append(",\"wallMillis\":").append(System.currentTimeMillis());
        json.append(",\"client\":").append(q(minecraft.player.getName().getString()));
        json.append(",\"uuid\":").append(q(minecraft.player.getUUID().toString()));
        json.append(",\"source\":").append(q(get(state, "source").toString()));
        json.append(",\"session\":").append(q(get(state, "session").toString()));
        json.append(",\"generation\":").append(get(state, "generation"));
        json.append(",\"owner\":").append(q(get(state, "owner").toString()));
        json.append(",\"track\":").append(q(track)).append(",\"positionMillis\":").append(position).append(",\"delayMillis\":").append(delay);
        json.append(",\"localPositionMillis\":").append(invoke(playback, "position"));
        json.append(",\"active\":").append(get(state, "active"));
        json.append(",\"ownerLocal\":").append(invoke(playback, "owner"));
        if (timeline == null) {
            json.append(",\"hasData\":false");
        } else {
            json.append(",\"hasData\":true,\"prepared\":").append(timeline.prepared());
            json.append(",\"latestEnd\":").append(timeline.end());
            json.append(",\"preparedEnd\":").append(edge(timeline, "frames", true));
            json.append(",\"liveEnd\":").append(edge(timeline, "live", true));
            json.append(",\"framesOldest\":").append(edge(timeline, "frames", false)).append(",\"framesNewest\":").append(edge(timeline, "frames", true));
            json.append(",\"liveOldest\":").append(edge(timeline, "live", false)).append(",\"liveNewest\":").append(edge(timeline, "live", true));
        }
        if (sample == null) {
            double phase = timeline == null ? Double.NaN : timeline.phaseAt(position, delay);
            json.append(",\"motionPhase\":").append(Double.isFinite(phase) ? Double.toString(phase) : "null").append(",\"sample\":null");
        } else {
            json.append(",\"sample\":{\"millis\":").append(sample.millis()).append(",\"audio\":").append(sample.audio())
                    .append(",\"video\":").append(sample.video()).append(",\"level\":").append(sample.level())
                    .append(",\"left\":").append(sample.left()).append(",\"right\":").append(sample.right())
                    .append(",\"motionPhase\":").append(sample.motionPhase()).append('}');
        }
        if (raw != null) {
            json.append(",\"rawLatest\":{\"millis\":").append(raw.millis()).append(",\"level\":").append(raw.level())
                    .append(",\"left\":").append(raw.left()).append(",\"right\":").append(raw.right())
                    .append(",\"motionPhase\":").append(raw.motionPhase()).append('}');
        }
        AnalysisFrame cached = timeline == null ? null : cached(timeline, position);
        if (cached != null) {
            json.append(",\"rawPrepared\":{\"millis\":").append(cached.millis()).append(",\"level\":").append(cached.level())
                    .append(",\"left\":").append(cached.left()).append(",\"right\":").append(cached.right())
                    .append(",\"motionPhase\":").append(cached.motionPhase()).append('}');
        }
        json.append(",\"receivedAgeMillis\":").append(Math.max(0, (System.nanoTime() - (long) get(playback, "received")) / 1_000_000));
        Object pending = get(playback, "pending");
        json.append(",\"pendingSequence\":").append(pending == null ? "null" : get(pending, "sequence"));
        json.append(",\"sentAgeMillis\":").append(Math.max(0, (System.nanoTime() - (long) get(playback, "sentAt")) / 1_000_000));
        json.append('}');
        output.write(json.toString());
        output.newLine();
        output.flush();
    }

    private static long delay(Object state, long position) throws ReflectiveOperationException {
        int from = (int) get(state, "delayFrom");
        int to = (int) get(state, "delayTo");
        long start = (long) get(state, "delayStart");
        return Math.round(from + (to - from) * Math.max(0, Math.min(1, (position - start) / 2000.0)));
    }

    private static long edge(AnalysisTimeline timeline, String name, boolean newest) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        java.util.NavigableMap<Long, AnalysisFrame> frames = (java.util.NavigableMap<Long, AnalysisFrame>) get(timeline, name);
        return frames.isEmpty() ? -1 : newest ? frames.lastKey() : frames.firstKey();
    }

    private static AnalysisFrame latest(AnalysisTimeline timeline) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        java.util.NavigableMap<Long, AnalysisFrame> frames = (java.util.NavigableMap<Long, AnalysisFrame>) get(timeline, "live");
        return frames.isEmpty() ? null : frames.lastEntry().getValue();
    }

    private static AnalysisFrame cached(AnalysisTimeline timeline, long position) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        java.util.NavigableMap<Long, AnalysisFrame> frames = (java.util.NavigableMap<Long, AnalysisFrame>) get(timeline, "frames");
        var entry = frames.floorEntry(position);
        return entry == null ? null : entry.getValue();
    }

    private static String safeTrack(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            return "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())
                    ? (uri.getRawPath() == null ? "" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    : "external";
        } catch (IllegalArgumentException ignored) {
            return "invalid";
        }
    }

    private static Field field(String type, String name) throws ReflectiveOperationException {
        Field field = Class.forName(type).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static String q(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
