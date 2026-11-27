package org.haruyq.createmediasystems.api.media.client;

import org.haruyq.createmediasystems.api.media.RawAudio;
import org.haruyq.createmediasystems.playback.LiveFeeds;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Sound another mod makes on this client, played by CMS like any track: a source given
 * {@link RawAudio#feed its feed's link} (a media block, a train's announcement) sounds through the speakers linked to
 * it, each with its range, direction and volume. Getting the sound to each client (a synth, a voice) is the mod's own.
 */
@ApiStatus.Experimental
public final class LiveAudioFeeds {
    /** The rate (Hz) a feed gives its sound at. */
    public static final int RATE = 48_000;

    private LiveAudioFeeds() {
    }

    /** Sound as it is made, for one speaker. */
    @ApiStatus.OverrideOnly
    public interface Feed {
        /**
         * Fills {@code stereo} with up to {@code frames} frames of 16-bit signed samples at {@link #RATE}, left and
         * right in turn; how many it filled (what it hasn't is played as silence). Called on the sound thread every
         * few milliseconds: it must not wait.
         */
        int read(short[] stereo, int frames);

        /** The speaker stopped playing it. */
        default void close() {
        }
    }

    @ApiStatus.OverrideOnly
    public interface Opener {
        /**
         * A feed for one speaker of the source at {@code source} (null for one that is no block, such as a train's);
         * null to leave that speaker silent. Anyone who can type a link can play a feed, so refuse here what is not
         * theirs to play. Called off the game thread.
         */
        @Nullable
        Feed open(@Nullable BlockPos source);
    }

    /** {@code id}'s feed opens through {@code opener} on this client, in place of any before. */
    public static void provide(ResourceLocation id, Opener opener) {
        LiveFeeds.provide(id, opener);
    }

    /** {@code id} has no feed here any more; its speakers already playing play on until they stop. */
    public static void remove(ResourceLocation id) {
        LiveFeeds.provide(id, null);
    }
}
