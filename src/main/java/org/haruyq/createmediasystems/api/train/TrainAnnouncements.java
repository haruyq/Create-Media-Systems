package org.haruyq.createmediasystems.api.train;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.blocks.train.TrainAnnouncer;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.server.MinecraftServer;

/**
 * Announcements on Create trains, heard through a train's train speakers by those aboard the carriage each is in, as
 * a train disc reader's schedule plays them. A train is known by its Create id ({@code Train#id}). On the server.
 */
@ApiStatus.Experimental
public final class TrainAnnouncements {
    private TrainAnnouncements() {
    }

    /** An announcement as it plays: its address, and how far into it (ms, by the server's clock). */
    public record Announcement(String url, long millis) {
    }

    /**
     * Plays {@code url} on {@code train} at {@code volume} (0 to 1), from its start, whoever is aboard hearing it from
     * where it is when they board. One at a time for each train: another cuts off the one this played before.
     * Whether it plays: false for no such train, or an address the server's settings don't allow.
     */
    public static boolean play(MinecraftServer server, UUID train, String url, float volume) {
        return TrainAnnouncer.play(server, train, url, volume);
    }

    /** Stops what {@link #play} plays on {@code train}, not its disc readers': whether anything was playing. */
    public static boolean stop(MinecraftServer server, UUID train) {
        return TrainAnnouncer.stop(train);
    }

    /** What plays on {@code train} now: each of its disc readers' announcements, then {@link #play}'s. */
    public static List<Announcement> playing(MinecraftServer server, UUID train) {
        return TrainAnnouncer.playing(train).stream()
                .map(source -> new Announcement(source.url(), source.ticks() * PlaybackClock.NANOS_PER_TICK / 1_000_000))
                .toList();
    }

    /** Told when a train's disc reader starts an announcement from its schedule. */
    @FunctionalInterface
    @ApiStatus.OverrideOnly
    public interface Announced {
        void announced(MinecraftServer server, UUID train, String url);
    }

    /** Runs {@code announced} on the server each time a train's disc reader starts an announcement. */
    public static void onAnnounced(Announced announced) {
        TrainAnnouncer.onAnnounced(announced::announced);
    }
}
