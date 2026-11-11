package org.haruyq.createmediasystems.dev;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.lighting.AnalysisPlayback;
import org.haruyq.createmediasystems.lighting.AnalysisTimeline;
import org.haruyq.createmediasystems.playback.VideoCache;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

public final class LightingDemoCache {
    private static boolean received;
    private static boolean reused;

    private LightingDemoCache() {
    }

    public static void seedNext(String next, Path media, int discHeight) {
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        if (LightingLab.SABLE || !LightingLab.NETWORK && !game.getFileName().toString().equals("run-lighting-demo")) {
            return;
        }
        if (ClientConfig.VIDEO_CACHE_MEGABYTES.get() <= 0) {
            LogUtils.getLogger().warn("Lighting demo next-track cache skipped: video cache is disabled");
            return;
        }
        try {
            URI source = URI.create(next);
            int height = ClientConfig.maxHeight(discHeight);
            VideoCache.seed(source, height, media, Integer.getInteger("cms.demoSeconds", 60));
            LogUtils.getLogger().info("Lighting demo next-track cache ready: url={}, height={}", source, height);
        } catch (IOException | RuntimeException error) {
            LogUtils.getLogger().error("Could not seed the lighting demo next-track cache", error);
        }
    }

    public static void verify() {
        if (reused || LightingLab.SABLE) {
            return;
        }
        for (AnalysisPlayback.Snapshot playback : AnalysisPlayback.snapshots()) {
            if (!received && playback.next() instanceof AnalysisTimeline next && next.end() >= 59_000) {
                received = true;
                LogUtils.getLogger().info("Lighting demo received complete next-track analysis before playback");
            }
            if (received && playback.state().track().endsWith("?next=1")
                    && playback.timeline() instanceof AnalysisTimeline current && current.sample(1000) != null) {
                reused = true;
                LogUtils.getLogger().info("Lighting demo reused synchronized analysis after the playlist advanced");
            }
        }
    }
}
