package org.haruyq.createmediasystems.dev;

import java.nio.file.Files;
import java.nio.file.Path;

import org.haruyq.createmediasystems.lighting.AnalysisPackets;
import org.haruyq.createmediasystems.lighting.AnalysisPlayback;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "create_media_systems", value = Dist.CLIENT)
public final class LightingNetworkClient {
    private static boolean seeded;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!LightingLab.NETWORK || minecraft.level == null) {
            return;
        }
        if (Files.exists(minecraft.gameDirectory.toPath().resolve("disconnect"))) {
            minecraft.stop();
        }
        if (seeded || minecraft.level.getGameTime() % 20 != 0
                || !Files.exists(minecraft.gameDirectory.toPath().resolve("prepare-next"))) {
            return;
        }
        for (AnalysisPlayback.Snapshot playback : AnalysisPlayback.snapshots()) {
            AnalysisPackets.State state = playback.state();
            if (!state.next().isEmpty()) {
                LightingDemoCache.seedNext(state.next(), Path.of("..", "server").resolve(LightingLab.media()), state.height());
                seeded = true;
            }
        }
    }
}
