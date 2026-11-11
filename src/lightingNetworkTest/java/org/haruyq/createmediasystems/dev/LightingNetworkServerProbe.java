package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

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
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            if (!warned) {
                warned = true;
                LogManager.getLogger().warn("Lighting server network probe failed", failure);
            }
        }
    }

    private static void write(MinecraftServer server, long now) throws ReflectiveOperationException, java.io.IOException {
        if (output == null) {
            Path path = server.getServerDirectory().resolve("lighting-network-server.jsonl");
            output = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Map<?, ?> levels = (Map<?, ?>) field("org.haruyq.createmediasystems.lighting.AnalysisCoordinator", "LEVELS").get(null);
        for (Map<?, ?> sessions : levels.values().stream().map(value -> (Map<?, ?>) value).toList()) {
            for (Object session : sessions.values()) {
                Object lease = get(session, "lease");
                @SuppressWarnings("unchecked") Map<String, ?> tracks = (Map<String, ?>) get(session, "tracks");
                String track = (String) get(session, "track");
                output.write('{');
                output.write("\"wallNanos\":" + now + ",\"wallMillis\":" + System.currentTimeMillis() + ",\"source\":" + q(get(session, "source")) + ",\"session\":" + q(get(session, "session")));
                output.write(",\"track\":" + q(track) + ",\"next\":" + q(get(session, "next")) + ",\"generation\":" + get(lease, "generation"));
                output.write(",\"owner\":" + q(get(lease, "owner")) + ",\"sequence\":" + get(lease, "sequence") + ",\"published\":" + get(lease, "published"));
                output.write(",\"liveSequence\":" + get(lease, "liveSequence") + ",\"readyCount\":" + ((Map<?, ?>) get(session, "ready")).size()
                        + ",\"trackEnd\":" + end(tracks.get(track)) + ",\"nextEnd\":" + end(tracks.get((String) get(session, "next"))));
                output.write("}\n");
            }
        }
        output.flush();
    }

    private static long end(Object timeline) throws ReflectiveOperationException {
        return timeline == null ? -1 : (long) timeline.getClass().getMethod("end").invoke(timeline);
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

    private static String q(Object value) {
        return value == null ? "null" : '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
