package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.LogManager;
import org.haruyq.createmediasystems.lighting.AnalysisCoordinator;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "create_media_systems")
public final class LightingNetworkServerProbe {
    private static long next;
    private static BufferedWriter output;
    private static boolean warned;

    private LightingNetworkServerProbe() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!LightingLab.NETWORK) {
            return;
        }
        long now = System.nanoTime();
        if (now < next) {
            return;
        }
        next = now + 200_000_000L;
        if (Files.exists(event.getServer().getServerDirectory().resolve("stop-test"))) {
            event.getServer().halt(false);
            return;
        }
        try {
            write(event.getServer(), now);
        } catch (IOException failure) {
            if (!warned) {
                warned = true;
                LogManager.getLogger().warn("Lighting server network probe failed", failure);
            }
        }
    }

    private static void write(MinecraftServer server, long now) throws IOException {
        if (output == null) {
            Path path = server.getServerDirectory().resolve("lighting-network-server.jsonl");
            output = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        for (AnalysisCoordinator.Snapshot session : AnalysisCoordinator.snapshots()) {
            output.write('{');
            output.write("\"wallNanos\":" + now + ",\"wallMillis\":" + System.currentTimeMillis() + ",\"source\":" + q(session.source()) + ",\"session\":" + q(session.session()));
            output.write(",\"track\":" + q(session.track()) + ",\"next\":" + q(session.next()) + ",\"generation\":" + session.generation());
            output.write(",\"owner\":" + q(session.owner()) + ",\"sequence\":" + session.sequence() + ",\"published\":" + session.published());
            output.write(",\"liveSequence\":" + session.liveSequence() + ",\"readyCount\":" + session.ready()
                    + ",\"trackEnd\":" + session.trackEnd() + ",\"nextEnd\":" + session.nextEnd());
            output.write("}\n");
        }
        output.flush();
    }

    private static String q(Object value) {
        return value == null ? "null" : '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
