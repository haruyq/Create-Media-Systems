package org.haruyq.createmediasystems.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * A vanilla jukebox with a custom disc: a {@link MediaSource} that is also its own emitter, heard over its
 * {@link EmitterRange}, as the range quill set it. Its clock follows its song player. Hooked into the jukebox by JukeboxBlockEntityMixin.
 */
public final class Jukeboxes {
    private Jukeboxes() {
    }

    @Nullable
    public static Streams.Source nowPlaying(JukeboxBlockEntity jukebox) {
        PlaybackClock clock = jukebox.getData(AudioFeature.JUKEBOX_CLOCK);
        return plays(jukebox) && clock.running()
                ? new Streams.Source(MediaItem.url(jukebox.getTheItem()), clock.position(), null) : null;
    }

    /** Whether its song player plays a custom disc. */
    private static boolean plays(JukeboxBlockEntity jukebox) {
        return MediaItem.fits(jukebox.getTheItem(), DiscKind.Slot.JUKEBOX) && jukebox.getSongPlayer().isPlaying();
    }

    /** The disc changed: current listeners stop now, and everyone in range gets the new disc (if any) from its top. */
    public static void restart(ServerLevel level, JukeboxBlockEntity jukebox) {
        Streams.stop(jukebox);
        PlaybackClock clock = jukebox.getData(AudioFeature.JUKEBOX_CLOCK);
        if (plays(jukebox)) {
            clock.start(0);
            Streams.loading(jukebox, null, clock, level.getGameTime());
        } else {
            clock.stop();
        }
        relay(level, jukebox);
    }

    /**
     * Every tick: its clock counts once its listeners have its track, and the disc stops once the track has ended. A
     * disc playing since before its chunk loaded is taken up where its song player is.
     */
    public static void tick(ServerLevel level, JukeboxBlockEntity jukebox) {
        PlaybackClock clock = jukebox.getData(AudioFeature.JUKEBOX_CLOCK);
        boolean plays = plays(jukebox);
        if (plays && !clock.running()) {
            clock.start(jukebox.getSongPlayer().getTicksSinceSongStarted());
        } else if (!plays && clock.running()) {
            clock.stop();
        }
        long now = level.getGameTime();
        Streams.tick(jukebox, clock, now);
        Streams.Source playing = Streams.source(level, jukebox.getBlockPos());
        if (playing != null && clock.ended(playing.url(), now)) {
            jukebox.getSongPlayer().stop(level, jukebox.getBlockState());
        }
        if (Streams.relayDue(level)) {
            relay(level, jukebox);
        }
    }

    /** Sends its track to players in range: every RELAY_TICKS, and at once when its settings change. */
    public static void relay(ServerLevel level, JukeboxBlockEntity jukebox) {
        if (nowPlaying(jukebox) != null) {
            // Saves how far in it is: vanilla marks a jukebox changed only as its song starts or stops, so after a
            // server restart its track, and every speaker on it, went back to where the chunk was last saved.
            jukebox.setChanged();
        }
        Streams.relay(level, jukebox, new Streams.Sound(jukebox.getBlockPos().getCenter(), Optional.empty(), Streams.Channel.MIX,
                jukebox.getBlockPos()), jukebox.getData(AudioFeature.JUKEBOX_SETTINGS));
    }
}
