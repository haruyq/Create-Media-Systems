package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
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

    public static void register() {
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
        event.getDispatcher().register(Commands.literal("cms").then(Commands.literal("disc")
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

    private static int load(String path, int title) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof VideoDiscItem)) {
            say("hold_disc", true);
            return 0;
        }
        if (reading != null && reading.isAlive()) {
            say("busy", true);
            return 0;
        }
        if (!P2pClient.connect(mc.player.getUUID())) {
            return 0;
        }
        clear();
        int request = generation;
        UUID owner = mc.player.getUUID();
        var held = mc.player.getMainHandItem().copy();
        say("reading", false);
        reading = Thread.ofPlatform().daemon().name("CMS disc reader").start(() -> {
            try {
                Path disc = Path.of(path).toRealPath();
                DiscSource opened = DiscReaders.open(ClientConfig.DIR.resolve("ffmpeg"), ClientConfig.DISC_FFMPEG_PATH.get(),
                        ClientConfig.DISC_FFPROBE_PATH.get(), disc, title, () -> mc.execute(() -> {
                            if (request == generation) {
                                say("downloading", false);
                            }
                        }));
                try {
                    Transport connected = connection(owner);
                    DiscUrl url = new DiscUrl(owner, UUID.randomUUID());
                    DiscStream prepared = new DiscStream(connected, url.source(), opened, DiscClient::log);
                    mc.execute(() -> {
                        if (request != generation || mc.player == null || connected != P2pClient.current()) {
                            prepared.close();
                            opened.close();
                            return;
                        }
                        if (!net.minecraft.world.item.ItemStack.matches(held, mc.player.getMainHandItem())) {
                            prepared.close();
                            opened.close();
                            say("hold_disc", true);
                            return;
                        }
                        source = opened;
                        shared = url;
                        stream = prepared;
                        PacketDistributor.sendToServer(new VideoDiscItem.SetPlaylist(List.of(url.toString())));
                        say("loaded", false, String.format(java.util.Locale.ROOT, "%.0f", opened.duration()));
                    });
                } catch (IOException | RuntimeException failed) {
                    opened.close();
                    throw failed;
                }
            } catch (IOException | RuntimeException failed) {
                mc.execute(() -> {
                    if (request == generation) {
                        say("failed", true, failed instanceof IOException ? failed.getMessage() : "The disc path is invalid");
                    }
                });
            }
        });
        return 1;
    }

    public record Prepared(URI uri, double duration) {}

    /** The disc stream's {@link DiscStream#stats}, for the P2P debug overlay; null if none. */
    public static DiscStream.Stats stats() {
        DiscStream current = stream;
        return current == null ? null : current.stats();
    }

    public static Prepared prepare(DiscUrl url, double startSeconds) throws IOException {
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
        return new Prepared(current.prepare(startSeconds), current.duration());
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

    private static synchronized void clear() {
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
