package org.haruyq.createmediasystems.api.media;

import java.util.List;

import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

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

    /**
     * A screen showing a source's picture, as it stands in the world: the middle of its panel (in its block's space,
     * as block positions are), the way it faces, which is towards whoever watches it, and how wide and high its
     * panel is (blocks).
     */
    public record Screen(Vec3 middle, Direction facing, double width, double height) {
    }

    /**
     * The screens showing what the source at {@code pos} plays: each display linked to it that is loaded and has
     * taken up its track, which it does within half a second of the track starting. For a mod that lays something
     * out round the picture (a light show telling a stage's middle and its audience's side) rather than round the
     * source, which may stand anywhere. None while the source plays nothing, and none on a client: the server keeps
     * them.
     */
    public static List<Screen> screens(Level level, BlockPos pos) {
        return Streams.emitters(level, pos).stream().filter(DisplayBlock.Entity.class::isInstance)
                .map(DisplayBlock.Entity.class::cast)
                .map(display -> new Screen(display.center(), display.facing(), display.width(), display.height())).toList();
    }

    /**
     * Holds the track the source at {@code pos} plays where it is (pausing it), or lets it go on: for a mod that needs
     * a moment before it does, a show analysing the whole track say. Only a source a remote works (a disc player, a
     * hi-fi stack's) can be held. Whether it is now as asked: false for one that can't be, or plays nothing.
     */
    public static boolean hold(Level level, BlockPos pos, boolean hold) {
        if (!(level.getBlockEntity(pos) instanceof Remotable source) || !source.playing()) {
            return false;
        }
        if (source.paused() != hold) {
            source.control(Remotable.Action.PLAY_PAUSE, 0, null);
        }
        return source.paused() == hold;
    }

    /**
     * Sends the track the source at {@code pos} plays back to its start, held or playing, every listener loading it
     * there: whether it could (see hold).
     */
    public static boolean rewind(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof Remotable source) || !source.playing()) {
            return false;
        }
        source.control(Remotable.Action.SEEK, 0, null);
        return true;
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
