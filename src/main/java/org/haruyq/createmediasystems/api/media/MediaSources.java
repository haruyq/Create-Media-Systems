package org.haruyq.createmediasystems.api.media;

import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** What a media source (a jukebox, a Blu-ray player, a hi-fi stack: any block that plays tracks) is playing. */
@ApiStatus.Experimental
public final class MediaSources {
    private MediaSources() {
    }

    /**
     * A track as its source plays it: its address, as clients are given it; how far into it the source is (ms, by
     * the server's clock); the tallest picture (pixels) its disc plays, 0 for sound alone; and which load of it this
     * is, another each time the source starts a track or takes one up elsewhere.
     */
    public record Track(String url, long millis, int videoHeight, long load) {
    }

    /** What the source at {@code pos} plays now, its listeners having it loaded; null for nothing, or while it loads. */
    @Nullable
    public static Track playing(Level level, BlockPos pos) {
        Streams.Source source = Streams.source(level, pos);
        BlockEntity entity = source == null ? null : level.getBlockEntity(pos);
        if (entity == null || !Streams.loaded(entity)) {
            return null;
        }
        return new Track(source.url(), source.ticks() * PlaybackClock.NANOS_PER_TICK / 1_000_000,
                source.video() == null ? 0 : source.video().maxHeight(), Streams.loadId(entity));
    }
}
