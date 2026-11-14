package org.haruyq.createmediasystems.api.media;

import org.haruyq.createmediasystems.stream.ExternalSource;
import org.jetbrains.annotations.ApiStatus;

/**
 * A block entity of another mod that plays media as CMS's own sources do. Speakers and displays link to it with their
 * quills, lighting consoles follow it, and {@link MediaSources} tells of it as of any source; what it plays, it is
 * told through its {@link MediaPlayer}.
 *
 * <p>The block entity implements this (on both sides: a quill asks its client too), keeps one player, ticks it on the
 * server and saves it:
 * <pre>{@code
 * private final MediaPlayer media = MediaPlayer.of(this);
 *
 * public MediaPlayer media() { return media; }
 * // its server ticker: media.tick();
 * // saveAdditional: media.save(tag);   loadAdditional: media.load(tag);
 * }</pre>
 * Nothing else of it is for a mod to override.
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
public interface MediaBlock extends ExternalSource {
    /** Its player: the one it made with {@link MediaPlayer#of} and keeps. */
    @Override
    MediaPlayer media();
}
