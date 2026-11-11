package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.haruyq.createmediasystems.playback.mixin.ChannelAccessor;
import org.haruyq.createmediasystems.playback.mixin.SoundEngineAccessor;
import org.haruyq.createmediasystems.playback.mixin.SoundManagerAccessor;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.lwjgl.openal.AL10;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client side of the speaker stress test (-PspeakerStress only). Connects to the test server, again whenever it is
 * disconnected; every second writes how each side of each source plays here: its emitters, whether its stream has a
 * channel and plays, and how strong 440 Hz (the file's left) and 660 Hz (its right) are in what it last played.
 * Files in the game directory: "client-disconnect" leaves the server (to come back), "client-hold" keeps it away,
 * "client-quit" closes the game.
 */
@EventBusSubscriber(modid = "create_media_systems", value = Dist.CLIENT)
public final class SpeakerStressClient {
    private static final String ADDRESS = "127.0.0.1:25590";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon()
            .name("cms-stress-probe").unstarted(r));
    private static BufferedWriter out;
    private static long lastConnect;
    private static int ticks;
    private static boolean warned;
    private static String lastReport;

    private SpeakerStressClient() {
    }

    /** One side as the main thread saw it; the sound thread adds what OpenAL says. */
    private record Seen(String name, int emitters, boolean present, boolean nearby, double gain, boolean playing,
                        boolean active, boolean waiting, int source, long newestPts, double p440, double p660,
                        double rms, int samples) {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath();
        try {
            if (Files.deleteIfExists(dir.resolve("client-quit"))) {
                mc.stop();
                return;
            }
            if (Files.deleteIfExists(dir.resolve("client-disconnect")) && mc.level != null) {
                LogUtils.getLogger().info("Speaker stress: leaving the server as asked");
                mc.level.disconnect();
                mc.disconnect();
                mc.setScreen(new TitleScreen());
                lastConnect = System.nanoTime();
                return;
            }
            if (mc.level == null && !Files.exists(dir.resolve("client-hold"))
                    && (mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen
                    || mc.screen instanceof JoinMultiplayerScreen)
                    && System.nanoTime() - lastConnect > 4_000_000_000L) {
                lastConnect = System.nanoTime();
                LogUtils.getLogger().info("Speaker stress: connecting to {}", ADDRESS);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ADDRESS),
                        new ServerData("Speaker stress", ADDRESS, ServerData.Type.OTHER), false, null);
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Speaker stress control file failed", e);
        }
        if (++ticks % 20 == 0) {
            try {
                probe(mc, dir);
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (!warned) {
                    warned = true;
                    LogUtils.getLogger().warn("Speaker stress probe failed", e);
                }
            }
        }
    }

    private static void probe(Minecraft mc, Path dir) throws ReflectiveOperationException {
        long now = System.currentTimeMillis();
        boolean connected = mc.level != null && mc.player != null;
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        var engine = ((SoundManagerAccessor) mc.getSoundManager()).create_media_systems$soundEngine();
        Map<SoundInstance, ChannelAccess.ChannelHandle> channels =
                ((SoundEngineAccessor) engine).create_media_systems$instanceToChannel();
        Map<?, ?> playing = (Map<?, ?>) staticField(ClientStreams.class, "PLAYING");
        Map<?, ?> groups = (Map<?, ?>) staticField(ClientStreams.class, "GROUPS");
        List<Seen> seen = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Object side = entry.getKey();
            Object group = entry.getValue();
            BlockPos source = (BlockPos) call(side, "source");
            String name = SpeakerStressServer.name(source) + "/" + call(side, "channel");
            Object sound = playing.get(side);
            boolean active = sound != null && mc.getSoundManager().isActive((SoundInstance) sound);
            boolean waiting = sound != null && (boolean) call(sound, "waitingForChannel");
            int al = 0;
            ChannelAccess.ChannelHandle handle = sound == null ? null : channels.get(sound);
            if (handle != null) {
                Object channel = field(handle, "channel");
                al = channel == null ? 0 : ((ChannelAccessor) channel).create_media_systems$source();
            }
            double[] heard = heard(sound);
            seen.add(new Seen(name, ((Map<?, ?>) field(group, "emitters")).size(), (boolean) field(group, "present"),
                    (boolean) field(group, "nearby"), (double) field(group, "gain"), sound != null, active, waiting,
                    al, (long) heard[0], heard[1], heard[2], heard[3], (int) heard[4]));
        }
        int orphans = 0;
        for (Object key : playing.keySet()) {
            orphans += groups.containsKey(key) ? 0 : 1;
        }
        int streamsActive = 0;
        for (SoundInstance sound : channels.keySet()) {
            streamsActive += sound instanceof StreamSound ? 1 : 0;
        }
        int orphanStreams = orphans;
        int engineStreams = streamsActive;
        String players = players();
        var report = org.haruyq.createmediasystems.client.Reports.last();
        String said = report == null ? null : report.getString();
        if (said != null && !said.equals(lastReport)) {
            lastReport = said;
            LogUtils.getLogger().info("Speaker stress: the game said \"{}\"", said);
        }
        // OpenAL is asked on the sound thread, where the engine keeps its context.
        ((SoundEngineAccessor) engine).create_media_systems$executor().execute(() -> {
            List<int[]> states = new ArrayList<>();
            for (Seen side : seen) {
                states.add(side.source == 0 ? new int[]{0, 0}
                        : new int[]{AL10.alGetSourcei(side.source, AL10.AL_SOURCE_STATE),
                        AL10.alGetSourcei(side.source, AL10.AL_BUFFERS_QUEUED)});
            }
            WRITER.execute(() -> write(dir, now, connected, screen, seen, states, orphanStreams, engineStreams, players));
        });
    }

    /** The native players open here (see NativeAudio#playbacks), as JSON. */
    private static String players() throws ReflectiveOperationException {
        StringBuilder json = new StringBuilder("[");
        for (Object player : org.haruyq.createmediasystems.playback.NativeAudio.playbacks()) {
            Object id = field(player, "id");
            String source = String.valueOf(call(id, "track"));
            var playing = (java.util.concurrent.CompletableFuture<?>) field(player, "playing");
            Object owner = field(player, "owner");
            Object status = owner.getClass().getMethod("status").invoke(owner);
            long time = (long) owner.getClass().getMethod("time").invoke(owner);
            json.append(json.length() > 1 ? "," : "").append("{\"src\":\"")
                    .append(source.substring(source.indexOf("src=") + 4)).append("\",\"users\":")
                    .append(field(player, "users")).append(",\"tapped\":").append(field(player, "tapped"))
                    .append(",\"playing\":\"").append(!playing.isDone() ? "pending"
                            : playing.isCompletedExceptionally() ? "failed" : "done")
                    .append("\",\"status\":\"").append(status).append("\",\"timeMillis\":").append(time).append('}');
        }
        return json.append(']').toString();
    }

    private static void write(Path dir, long now, boolean connected, String screen, List<Seen> seen,
                              List<int[]> states, int orphans, int engineStreams, String players) {
        try {
            if (out == null) {
                out = Files.newBufferedWriter(dir.resolve("stress-client.jsonl"), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            StringBuilder json = new StringBuilder("{\"wallMillis\":").append(now).append(",\"connected\":")
                    .append(connected).append(",\"screen\":\"").append(screen).append("\",\"orphans\":")
                    .append(orphans).append(",\"engineStreams\":").append(engineStreams)
                    .append(",\"players\":").append(players).append(",\"sides\":{");
            for (int i = 0; i < seen.size(); i++) {
                Seen s = seen.get(i);
                int[] state = states.get(i);
                json.append(i == 0 ? "" : ",").append('"').append(s.name).append("\":{")
                        .append("\"emitters\":").append(s.emitters).append(",\"present\":").append(s.present)
                        .append(",\"nearby\":").append(s.nearby).append(",\"gain\":").append(round(s.gain))
                        .append(",\"playing\":").append(s.playing).append(",\"active\":").append(s.active)
                        .append(",\"waiting\":").append(s.waiting).append(",\"al\":").append(alState(state[0]))
                        .append(",\"queued\":").append(state[1]).append(",\"newestPts\":").append(s.newestPts)
                        .append(",\"p440\":").append(round(s.p440)).append(",\"p660\":").append(round(s.p660))
                        .append(",\"rms\":").append(round(s.rms)).append(",\"samples\":").append(s.samples).append('}');
            }
            out.write(json.append("}}\n").toString());
            out.flush();
        } catch (IOException e) {
            LogUtils.getLogger().warn("Speaker stress probe could not write", e);
        }
    }

    private static String alState(int state) {
        return switch (state) {
            case AL10.AL_PLAYING -> "\"playing\"";
            case AL10.AL_PAUSED -> "\"paused\"";
            case AL10.AL_STOPPED -> "\"stopped\"";
            case AL10.AL_INITIAL -> "\"initial\"";
            default -> "null";
        };
    }

    private static double round(double value) {
        return Double.isFinite(value) ? Math.round(value * 1e6) / 1e6 : -1;
    }

    /**
     * What the stream last played (the native player's tap keeps about a second of it): the VLC time of its newest
     * chunk, the normalized power at 440 and 660 Hz and the RMS of its last half second, and how many samples.
     */
    private static double[] heard(Object sound) throws ReflectiveOperationException {
        double[] none = {-1, 0, 0, 0, 0};
        if (sound == null) {
            return none;
        }
        Object direct = field(sound, "direct");
        if (direct == null) {
            return none;
        }
        Object tap = field(direct, "source");
        Object queue = field(tap, "queue");
        List<byte[]> chunks = new ArrayList<>();
        long newest = -1;
        synchronized (queue) {
            for (Object chunk : (ArrayDeque<?>) field(tap, "history")) {
                chunks.add((byte[]) call(chunk, "pcm"));
                newest = (long) call(chunk, "pts");
            }
        }
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length / 2;
        }
        int n = Math.min(total, 24_000);
        short[] samples = new short[n];
        int skip = total - n;
        int o = 0;
        for (byte[] chunk : chunks) {
            for (int i = 0; i + 1 < chunk.length; i += 2) {
                if (skip > 0) {
                    skip--;
                } else if (o < n) {
                    samples[o++] = (short) (chunk[i] & 0xff | chunk[i + 1] << 8);
                }
            }
        }
        double sum = 0;
        for (short s : samples) {
            sum += (double) s * s;
        }
        double rms = n == 0 ? 0 : Math.sqrt(sum / n) / 32768;
        return new double[]{newest, goertzel(samples, 440), goertzel(samples, 660), rms, n};
    }

    /** Power at {@code hz} as a share of a full-scale sine's (48 kHz samples). */
    static double goertzel(short[] samples, double hz) {
        if (samples.length == 0) {
            return 0;
        }
        double coeff = 2 * Math.cos(2 * Math.PI * hz / 48_000);
        double s1 = 0;
        double s2 = 0;
        for (short sample : samples) {
            double s0 = sample / 32768.0 + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return power / ((double) samples.length * samples.length / 4);
    }

    private static Object staticField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
