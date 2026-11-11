package org.haruyq.createmediasystems.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
    public static void tick(ClientTickEvent.Post event) throws ReflectiveOperationException {
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
        var playing = AnalysisPlayback.class.getDeclaredField("PLAYING");
        playing.setAccessible(true);
        for (Object playback : ((Map<?, ?>) playing.get(null)).values()) {
            var stateField = playback.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            AnalysisPackets.State state = (AnalysisPackets.State) stateField.get(playback);
            if (!state.next().isEmpty()) {
                LightingDemoCache.seedNext(state.next(), Path.of("..", "server").resolve(LightingLab.media()), state.height());
                seeded = true;
            }
        }
    }
}
