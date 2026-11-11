package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.P2pOverlay;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.playback.MediaSources;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.video.VideoDiscItem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class DiscClient {
    private static volatile DiscSource source;
    private static volatile DiscUrl shared;
    private static volatile DiscStream stream;
    private static Thread reading;
    private static volatile int generation;

    private DiscClient() {
    }

    public static void register(IEventBus modBus) {
        DriveClient.register();
        MediaSources.opens(MediaKind.DISC, DiscClient::open);
        P2pOverlay.add(DiscClient::overlay);
        NeoForge.EVENT_BUS.addListener(DiscClient::commands);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        P2pClient.changed(DiscClient::changed);
        P2pClient.media(new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                DiscStream current = stream;
                if (current != null) {
                    current.data(from, payload);
                }
            }

            @Override
            public void control(UUID from, byte[] payload) {
                DiscStream current = stream;
                if (current != null) {
                    current.control(from, payload);
                }
            }

            @Override
            public void status(String message) {
            }
        });
    }

    private static synchronized void changed(Transport transport) {
        DiscStream current = stream;
        stream = null;
        if (current != null) {
            current.close();
        }
        if (transport != null && transport.host() && source != null && shared != null) {
            try {
                stream = new DiscStream(transport, shared.source(), source, DiscClient::log);
            } catch (IOException failed) {
                say("failed", true, failed.getMessage());
            }
        }
    }

    /** {@code /cms disc}; the server's {@code /cms config} and {@code /cms p2p} (see P2pClient) share the root. */
    private static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(CreateMediaSystems.COMMAND).then(Commands.literal("disc")
                .then(Commands.literal("load").then(Commands.argument("path", StringArgumentType.string())
                        .executes(c -> load(StringArgumentType.getString(c, "path"), -1))
                        .then(Commands.argument("title", IntegerArgumentType.integer(0, 99999))
                                .executes(c -> load(StringArgumentType.getString(c, "path"), IntegerArgumentType.getInteger(c, "title"))))))
                .then(Commands.literal("unload").executes(c -> {
                    clear();
                    say("unloaded", false);
                    return 1;
                }))));
    }

    /** What a disc is read for: the disc held for {@code /cms disc load}, or a drive emulator (see DriveClient). */
    public interface Reader {
        /** FFmpeg is downloaded first. */
        void downloading();

        /** It is read, and shared as {@code url} if this says true: false drops it, as it is no longer wanted. */
        boolean loaded(DiscUrl url, DiscSource disc);

        void failed(String message);
    }

    private static int load(String path, int title) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof VideoDiscItem)) {
            say("hold_disc", true);
            return 0;
        }
        if (busy()) {
            say("busy", true);
            return 0;
        }
        var held = mc.player.getMainHandItem().copy();
        if (load(path, title, new Reader() {
            @Override
            public void downloading() {
                say("downloading", false);
            }

            @Override
            public boolean loaded(DiscUrl url, DiscSource disc) {
                if (!net.minecraft.world.item.ItemStack.matches(held, mc.player.getMainHandItem())) {
                    say("hold_disc", true);
                    return false;
                }
                PacketDistributor.sendToServer(new VideoDiscItem.SetPlaylist(List.of(url.toString())));
                say("loaded", false, String.format(java.util.Locale.ROOT, "%.0f", disc.duration()));
                return true;
            }

            @Override
            public void failed(String message) {
                say("failed", true, message);
            }
        }) == 0) {
            return 0;
        }
        say("reading", false);
        return 1;
    }

    /** Whether a disc is still being read: another waits for it (see load). */
    public static boolean busy() {
        return reading != null && reading.isAlive();
    }

    /** Whether load {@code load} is still the disc read or shared: not since unloaded, or another loaded. */
    public static boolean current(int load) {
        return load == generation;
    }

    /**
     * Reads the disc at {@code path} for {@code reader}, in place of the one shared, and hosts a P2P session to share
     * it over. Says which load it is (see current), or 0 if it can't start one now.
     */
    public static int load(String path, int title, Reader reader) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || busy() || !P2pClient.connect(mc.player.getUUID())) {
            return 0;
        }
        clear();
        int request = generation;
        UUID owner = mc.player.getUUID();
        reading = Thread.ofPlatform().daemon().name("CMS disc reader").start(() -> {
            try {
                Path disc = Path.of(path).toRealPath();
                DiscSource opened = DiscReaders.open(ClientFiles.DIR.resolve("ffmpeg"), ClientConfig.DISC_FFMPEG_PATH.get(),
                        ClientConfig.DISC_FFPROBE_PATH.get(), disc, title, () -> mc.execute(() -> {
                            if (request == generation) {
                                reader.downloading();
                            }
                        }));
                try {
                    Transport connected = connection(owner);
                    DiscUrl url = new DiscUrl(owner, UUID.randomUUID());
                    DiscStream prepared = new DiscStream(connected, url.source(), opened, DiscClient::log);
                    mc.execute(() -> {
                        if (request != generation || mc.player == null || connected != P2pClient.current()
                                || !reader.loaded(url, opened)) {
                            prepared.close();
                            opened.close();
                            return;
                        }
                        source = opened;
                        shared = url;
                        stream = prepared;
                    });
                } catch (IOException | RuntimeException failed) {
                    opened.close();
                    throw failed;
                }
            } catch (IOException | RuntimeException failed) {
                mc.execute(() -> {
                    if (request == generation) {
                        reader.failed(failed instanceof IOException ? failed.getMessage() : "The disc path is invalid");
                    }
                });
            }
        });
        return request;
    }

    /** What the P2P debug overlay shows of the disc stream (see DiscStream#stats); nothing without one. */
    private static List<P2pOverlay.Line> overlay(boolean hosting, long now) {
        DiscStream current = stream;
        return current == null ? List.of() : current.stats().lines(hosting, now);
    }

    /**
     * A shared disc's stream on this computer, from {@code startSeconds} in: joins its host's P2P session (or, as the
     * host, plays the one it shares) and serves its segments here as HLS, which VLC buffers 2 s of.
     */
    private static MediaSources.Opened open(URI link, double startSeconds) throws IOException {
        DiscUrl url = DiscUrl.parse(link.toString());
        if (url == null) {
            throw new IOException("Not a disc's link");
        }
        int request = generation;
        var mc = Minecraft.getInstance();
        CompletableFuture<Boolean> joined = new CompletableFuture<>();
        mc.execute(() -> joined.complete(request == generation && P2pClient.connect(url.owner())));
        try {
            if (!joined.get(10, TimeUnit.SECONDS)) {
                throw new IOException("Could not join the disc host's P2P session");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Disc loading was cancelled");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IOException("Could not join the disc host");
        }
        Transport connected = connection(url.owner());
        DiscStream current;
        synchronized (DiscClient.class) {
            if (request != generation || connected != P2pClient.current()) {
                throw new IOException("Disc loading was cancelled");
            }
            current = stream;
            if (current == null || !current.matches(connected, url.source())) {
                if (connected.host() && !url.equals(shared)) {
                    throw new IOException("Load this disc again with /cms disc load");
                }
                if (current != null) {
                    current.close();
                }
                current = new DiscStream(connected, url.source(), connected.host() ? source : null, DiscClient::log);
                stream = current;
            }
        }
        return new MediaSources.Opened(current.prepare(startSeconds), current.duration(), List.of(":network-caching=2000"));
    }

    private static Transport connection(UUID owner) throws IOException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < until) {
            Transport current = P2pClient.current();
            if (current != null && current.hostId().equals(owner) && current.state() != Transport.State.CLOSED
                    && (current.host() || current.connected().contains(owner))) {
                return current;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Disc connection was cancelled");
            }
        }
        throw new IOException("Could not connect directly to the disc host; check /cms p2p status");
    }

    /** Stops reading and sharing the disc. */
    public static synchronized void clear() {
        generation++;
        if (reading != null) {
            reading.interrupt();
        }
        DiscStream current = stream;
        stream = null;
        shared = null;
        DiscSource loaded = source;
        source = null;
        if (current != null) {
            current.close();
        }
        if (loaded != null) loaded.close();
    }

    private static void log(String message) {
        LogUtils.getLogger().info("{}", message);
    }

    private static void say(String key, boolean error, Object... args) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("create_media_systems.disc_stream." + key, args)
                    .withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY), false);
        }
    }
}
