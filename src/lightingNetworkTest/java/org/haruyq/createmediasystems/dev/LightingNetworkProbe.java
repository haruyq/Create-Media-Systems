package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.NavigableMap;

import org.haruyq.createmediasystems.lighting.AnalysisFrame;
import org.haruyq.createmediasystems.lighting.AnalysisPackets;
import org.haruyq.createmediasystems.lighting.AnalysisPlayback;
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
        } catch (IOException failure) {
            if (!warned) {
                warned = true;
                LogUtils.getLogger().warn("Lighting network probe disabled after diagnostic failure", failure);
            }
        }
    }

    private static void write(long now) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        for (AnalysisPlayback.Snapshot playback : AnalysisPlayback.snapshots()) {
            line(now, minecraft, playback);
        }
    }

    private static void line(long now, Minecraft minecraft, AnalysisPlayback.Snapshot playback) throws IOException {
        if (output == null) {
            outputPath = minecraft.gameDirectory.toPath().resolve("lighting-network.jsonl");
            if (Files.exists(outputPath) && Files.size(outputPath) > 20_000_000) {
                Files.move(outputPath, outputPath.resolveSibling("lighting-network.previous.jsonl"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            output = Files.newBufferedWriter(outputPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        AnalysisPackets.State state = playback.state();
        AnalysisTimeline timeline = playback.timeline();
        long position = playback.samplePosition();
        long delay = playback.delay();
        AnalysisFrame sample = timeline == null ? null : timeline.sample(position, delay);
        StringBuilder json = new StringBuilder(512);
        json.append('{').append("\"wallNanos\":").append(now).append(",\"wallMillis\":").append(System.currentTimeMillis());
        json.append(",\"client\":").append(q(minecraft.player.getName().getString()));
        json.append(",\"uuid\":").append(q(minecraft.player.getUUID().toString()));
        json.append(",\"source\":").append(q(state.source().toString()));
        json.append(",\"session\":").append(q(state.session().toString()));
        json.append(",\"generation\":").append(state.generation());
        json.append(",\"owner\":").append(q(state.owner().toString()));
        json.append(",\"track\":").append(q(safeTrack(state.track()))).append(",\"positionMillis\":").append(position)
                .append(",\"delayMillis\":").append(delay);
        json.append(",\"localPositionMillis\":").append(playback.position());
        json.append(",\"active\":").append(state.active());
        json.append(",\"ownerLocal\":").append(playback.owner());
        if (timeline == null) {
            json.append(",\"hasData\":false");
        } else {
            NavigableMap<Long, AnalysisFrame> prepared = timeline.frames(false);
            NavigableMap<Long, AnalysisFrame> live = timeline.frames(true);
            json.append(",\"hasData\":true,\"prepared\":").append(timeline.prepared());
            json.append(",\"latestEnd\":").append(timeline.end());
            json.append(",\"preparedEnd\":").append(edge(prepared, true));
            json.append(",\"liveEnd\":").append(edge(live, true));
            json.append(",\"framesOldest\":").append(edge(prepared, false)).append(",\"framesNewest\":").append(edge(prepared, true));
            json.append(",\"liveOldest\":").append(edge(live, false)).append(",\"liveNewest\":").append(edge(live, true));
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
        var raw = timeline == null ? null : timeline.frames(true).lastEntry();
        if (raw != null) {
            frame(json, "rawLatest", raw.getValue());
        }
        var cached = timeline == null ? null : timeline.frames(false).floorEntry(position);
        if (cached != null) {
            frame(json, "rawPrepared", cached.getValue());
        }
        json.append(",\"receivedAgeMillis\":").append(Math.max(0, (System.nanoTime() - playback.received()) / 1_000_000));
        json.append(",\"pendingSequence\":").append(playback.pendingSequence() == null ? "null" : playback.pendingSequence());
        json.append(",\"sentAgeMillis\":").append(Math.max(0, (System.nanoTime() - playback.sentAt()) / 1_000_000));
        json.append('}');
        output.write(json.toString());
        output.newLine();
        output.flush();
    }

    private static void frame(StringBuilder json, String name, AnalysisFrame frame) {
        json.append(",\"").append(name).append("\":{\"millis\":").append(frame.millis()).append(",\"level\":").append(frame.level())
                .append(",\"left\":").append(frame.left()).append(",\"right\":").append(frame.right())
                .append(",\"motionPhase\":").append(frame.motionPhase()).append('}');
    }

    private static long edge(NavigableMap<Long, AnalysisFrame> frames, boolean newest) {
        return frames.isEmpty() ? -1 : newest ? frames.lastKey() : frames.firstKey();
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

    private static String q(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
