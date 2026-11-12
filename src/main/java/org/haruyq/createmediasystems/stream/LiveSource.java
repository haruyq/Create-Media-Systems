package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A source that is played live (an instrument) and has no track: nothing is streamed or kept in step, each client
 * makes its sound itself as it is played. Speakers link to it as to any source; every relay they tell it they are
 * there (see Streams#relay), and it tells its clients where it sounds from.
 */
public interface LiveSource extends MediaSource {
    @Override
    default Streams.Source nowPlaying() {
        return null;
    }

    /**
     * The emitter at {@code emitter} is linked to it, sounding from {@code origin} (in its block space), loudest
     * towards {@code facing} if it has one, playing {@code channel} at {@code volume} (0..1).
     */
    void heardThrough(ServerLevel level, BlockPos emitter, Vec3 origin, Optional<Direction> facing,
                      Streams.Channel channel, float volume);
}
