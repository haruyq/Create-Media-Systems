package org.haruyq.createmediasystems.stream;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A block that mixes several sources into one line (a line mixer). It has no track of its own: an emitter linked to
 * it plays every source it feeds, each at that feed's gain, as if it were linked to them all (see Streams#relay).
 */
public interface MixBus extends MediaSource {
    /** A source it passes on, and how loud (0 to 1): its channel's fader through the master's. */
    record Feed(BlockPos source, float gain) {
    }

    /** What it passes on now: each source once, where that is now. */
    List<Feed> feeds(ServerLevel level);

    @Override
    @Nullable
    default Streams.Source nowPlaying() {
        return null;
    }
}
