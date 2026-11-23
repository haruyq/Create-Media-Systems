package org.haruyq.createmediasystems.screen;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.disc.DiscClient;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.playback.ScreenPlayer;
import org.haruyq.createmediasystems.stream.DiscUrl;

import org.jetbrains.annotations.Nullable;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/** This client's side of screen sharing: its own share (see ScreenShare) and how it plays the others'. Game thread. */
public final class ScreenClient {
    /** The server block this player's share goes through; null without one. */
    @Nullable
    private static BlockPos server;
    private static String what = "";

    private ScreenClient() {
    }

    public static void register(IEventBus modBus) {
        ScreenPlayer.opens(ScreenClient::open);
        ScreenMonitor.register(modBus);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ScreenShare.stopCurrent();
            server = null;
        });
    }

    /** A share's link as this client plays it: its own capture, or the host's over P2P. Off the game thread. */
    static ScreenPlayer.Feed open(DiscUrl url) throws IOException {
        var player = Minecraft.getInstance().player;
        if (player != null && url.owner().equals(player.getUUID())) {
            ScreenShare own = ScreenShare.current();
            if (own != null && own.url.equals(url)) {
                return own;
            }
            SoundShare sound = SoundShare.current();
            if (sound != null && sound.url.equals(url)) {
                return sound;
            }
            throw new IOException("This screen share has ended");
        }
        Transport session = DiscClient.join(url.owner());
        return new ScreenPlayer.Feed() {
            @Override
            public void watch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
                session.watch(pictures, sound);
            }

            @Override
            public void unwatch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
                session.unwatch(pictures, sound);
            }

            @Override
            public boolean ended() {
                return session.state() == Transport.State.CLOSED || P2pClient.session(url.owner()) != session;
            }
        };
    }

    @Nullable
    static BlockPos server() {
        return server;
    }

    /**
     * Shares {@code settings} through the server at {@code pos}, in place of any share before; {@code failed} hears
     * why it couldn't, on the game thread.
     */
    static void start(BlockPos pos, ScreenShare.Settings settings, Consumer<String> failed) {
        var mc = Minecraft.getInstance();
        UUID me = mc.player.getUUID();
        if (!P2pClient.connect(me)) {
            failed.accept("Could not host a P2P session; check /cms p2p status");
            return;
        }
        if (server != null && !server.equals(pos)) {
            send(server, "", "");
        }
        server = pos.immutable();
        what = name(settings.source());
        Thread.ofPlatform().daemon().name("CMS screen share setup").start(() -> {
            try {
                ScreenShare share = ScreenShare.start(me, settings);
                mc.execute(() -> {
                    if (ScreenShare.current() == share && pos.equals(server)) {
                        send(pos, share.url.toString(), what);
                    }
                });
            } catch (IOException e) {
                mc.execute(() -> failed.accept(e.getMessage()));
            }
        });
    }

    /** Goes on sharing with {@code settings}, under the same link: the viewers keep watching. */
    static void change(ScreenShare.Settings settings) {
        ScreenShare share = ScreenShare.current();
        if (share == null || server == null) {
            return;
        }
        what = name(settings.source());
        send(server, share.url.toString(), what);
        Thread.ofPlatform().daemon().name("CMS screen share setup").start(() -> share.change(settings));
    }

    static void stop() {
        ScreenShare.stopCurrent();
        if (server != null) {
            send(server, "", "");
        }
        server = null;
    }

    private static String name(ScreenShare.Source source) {
        String title = source.title();
        return title.length() > 64 ? title.substring(0, 64) : title;
    }

    private static void send(BlockPos pos, String link, String shows) {
        PacketDistributor.sendToServer(new ScreenServerBlock.Update(pos, link, shows));
    }

    /** Once a second while sharing: how each viewer's connection does, for the server's goggle tooltip. */
    private static void tick() {
        var mc = Minecraft.getInstance();
        if (server == null || mc.player == null || mc.level == null || mc.level.getGameTime() % 20 != 0) {
            return;
        }
        // Its server broken or its monitor taken off: the server ended the share, so this stops capturing it too.
        if (ScreenShare.current() == null || mc.level.isLoaded(server)
                && !(mc.level.getBlockState(server).getBlock() instanceof ScreenServerBlock && ScreenServerBlock.monitored(mc.level, server))) {
            stop();
            return;
        }
        Transport transport = P2pClient.session(mc.player.getUUID());
        if (transport == null || !transport.host()) {
            return;
        }
        transport.measure();
        List<DriveEmulatorBlock.Viewer> viewers = transport.links().stream().limit(64)
                .map(link -> new DriveEmulatorBlock.Viewer(link.peer(), link.open() && link.measured(),
                        (int) Math.max(0, link.rttNanos() / 1_000_000), (int) (link.bps() / 1000))).toList();
        PacketDistributor.sendToServer(new ScreenServerBlock.Viewers(server, viewers));
    }

    /** Every tick of every server here: one that says it shares this player's screen, which it no longer does, is told. */
    static void tick(ScreenServerBlock.Entity entity) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || entity.getLevel().getGameTime() % 40 != 0 || !mc.player.getUUID().equals(entity.host())) {
            return;
        }
        if (ScreenShare.current() == null || !entity.getBlockPos().equals(server)) {
            send(entity.getBlockPos(), "", "");
        }
    }
}
