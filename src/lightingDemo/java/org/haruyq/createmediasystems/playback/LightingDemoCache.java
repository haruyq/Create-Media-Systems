package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.lighting.AnalysisPackets;
import org.haruyq.createmediasystems.lighting.AnalysisPlayback;
import org.haruyq.createmediasystems.lighting.AnalysisTimeline;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

public final class LightingDemoCache {
    private static boolean received;
    private static boolean reused;

    private LightingDemoCache() {
    }

    public static void seedNext(String next, Path media, int discHeight) {
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        if (Boolean.getBoolean("cms.sableLightingDemo") || !Boolean.getBoolean("cms.lightingNetworkTest")
                && !game.getFileName().toString().equals("run-lighting-demo")) {
            return;
        }
        if (ClientConfig.VIDEO_CACHE_MEGABYTES.get() <= 0) {
            LogUtils.getLogger().warn("Lighting demo next-track cache skipped: video cache is disabled");
            return;
        }
        try {
            URI source = URI.create(next);
            int height = ClientConfig.maxHeight(discHeight);
            VideoCache.get().keep(source, height, media, Integer.getInteger("cms.demoSeconds", 60));
            if (VideoCache.get().lookup(source, height) == null) {
                throw new IOException("Seeded next track could not be read by VideoCache");
            }
            LogUtils.getLogger().info("Lighting demo next-track cache ready: url={}, height={}", source, height);
        } catch (IOException | RuntimeException error) {
            LogUtils.getLogger().error("Could not seed the lighting demo next-track cache", error);
        }
    }

    public static void verify() {
        if (reused || Boolean.getBoolean("cms.sableLightingDemo")) {
            return;
        }
        try {
            Field playing = AnalysisPlayback.class.getDeclaredField("PLAYING");
            playing.setAccessible(true);
            for (Object playback : ((Map<?, ?>) playing.get(null)).values()) {
                Field stateField = playback.getClass().getDeclaredField("state");
                Field tracksField = playback.getClass().getDeclaredField("tracks");
                stateField.setAccessible(true);
                tracksField.setAccessible(true);
                AnalysisPackets.State state = (AnalysisPackets.State) stateField.get(playback);
                Map<?, ?> tracks = (Map<?, ?>) tracksField.get(playback);
                if (!received && tracks.get(state.next()) instanceof AnalysisTimeline next && next.end() >= 59_000) {
                    received = true;
                    LogUtils.getLogger().info("Lighting demo received complete next-track analysis before playback");
                }
                if (received && state.track().endsWith("?next=1")
                        && tracks.get(state.track()) instanceof AnalysisTimeline current && current.sample(1000) != null) {
                    reused = true;
                    LogUtils.getLogger().info("Lighting demo reused synchronized analysis after the playlist advanced");
                }
            }
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Could not verify lighting synchronization", failed);
        }
    }
}
