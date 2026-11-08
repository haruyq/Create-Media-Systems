package org.haruyq.createmediasystems.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * A vanilla jukebox with a custom disc: a {@link MediaSource} that is also its own emitter, heard over its
 * {@link JukeboxSettings}. Hooked into the jukebox by JukeboxBlockEntityMixin.
 */
public final class Jukeboxes {
    private Jukeboxes() {
    }

    @Nullable
    public static Streams.Source nowPlaying(JukeboxBlockEntity jukebox) {
        return jukebox.getTheItem().is(CreateMediaSystems.CUSTOM_DISC.get()) && jukebox.getSongPlayer().isPlaying()
                ? new Streams.Source(jukebox.getTheItem().get(CreateMediaSystems.URL.get()),
                        jukebox.getSongPlayer().getTicksSinceSongStarted(), null)
                : null;
    }

    /** The disc changed: current listeners stop now, and everyone in range gets the new disc (if any). */
    public static void restart(ServerLevel level, JukeboxBlockEntity jukebox) {
        Streams.stop(jukebox);
        Streams.loading(jukebox, null);
        relay(level, jukebox);
    }

    /** Sends its track to players in range, and stops the disc once the track has ended. */
    public static void relay(ServerLevel level, JukeboxBlockEntity jukebox) {
        Streams.Source playing = Streams.source(level, jukebox.getBlockPos());
        if (playing != null && Streams.endedAt(playing)) {
            jukebox.getSongPlayer().stop(level, jukebox.getBlockState());
        }
        if (nowPlaying(jukebox) != null) {
            // Saves how far in it is: vanilla marks a jukebox changed only as its song starts or stops, so after a
            // server restart its track, and every speaker on it, went back to where the chunk was last saved.
            jukebox.setChanged();
        }
        Streams.relay(level, jukebox, new Streams.Sound(jukebox.getBlockPos().getCenter(), Optional.empty(), Streams.Channel.MIX,
                jukebox.getBlockPos()), jukebox.getData(CreateMediaSystems.JUKEBOX_SETTINGS));
    }
}
