package org.haruyq.createmediasystems.screen;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.playback.ScreenPlayer;
import org.haruyq.createmediasystems.stream.DiscUrl;

import org.jetbrains.annotations.Nullable;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * This player's share of sound alone, while it runs: one app's (a DJ controller's rekordbox) or a capture device's,
 * taken by cms-screen-audio.exe (see ScreenSound) and sent to each listener over the player's own P2P session, under
 * a screen share's kind of link. A session carries one sound, so it and a screen share end each other.
 */
public final class SoundShare implements ScreenPlayer.Feed {
    @Nullable
    private static volatile SoundShare current;

    public final DiscUrl url;
    private final ScreenSound sound;
    private volatile boolean stopped;

    private SoundShare(UUID owner, List<String> source) throws IOException {
        url = new DiscUrl(owner, UUID.randomUUID(), true);
        sound = new ScreenSound(ScreenSound.exe(), List.of(source), () -> 100, this::heard);
    }

    @Nullable
    public static SoundShare current() {
        return current;
    }

    /**
     * Shares {@code source} (the exe's arguments: {@code include <pid>} or {@code device <id>}) as {@code owner}, in
     * place of any share before. Off the game thread; the owner's P2P session is up already (see P2pClient#connect).
     */
    public static SoundShare start(UUID owner, List<String> source) throws IOException {
        stopCurrent();
        ScreenShare.stopCurrent();
        SoundShare share = new SoundShare(owner, source);
        current = share;
        return share;
    }

    public static void stopCurrent() {
        SoundShare share = current;
        current = null;
        if (share != null) {
            share.stopped = true;
            share.sound.close();
        }
    }

    /** The capture devices a share may take its sound from, as ids and names. Off the game thread. */
    public static List<String[]> devices() throws IOException {
        return ScreenSound.devices(ScreenSound.exe()).stream().map(device -> new String[]{device.id(), device.name()}).toList();
    }

    /** The loudest sample of the last 10 ms, 0 to 1. */
    public float level() {
        return sound.level;
    }

    private void heard(byte[] chunk) {
        Transport session = P2pClient.session(url.owner());
        if (session != null && session.host() && session.state() != Transport.State.CLOSED) {
            session.sound(chunk);
        }
    }

    /** Its host hears their own app as it is. */
    @Override
    public void watch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
    }

    @Override
    public void unwatch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound) {
    }

    @Override
    public boolean ended() {
        return stopped;
    }
}
