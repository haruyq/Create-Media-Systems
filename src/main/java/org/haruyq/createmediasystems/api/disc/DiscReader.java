package org.haruyq.createmediasystems.api.disc;

import java.io.IOException;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Reads discs CMS can't read itself (an encrypted one, say), added with {@link CmsDiscs#register}. Asked in turn on
 * a disc reader thread of the client before CMS reads a disc itself.
 */
@FunctionalInterface
@ApiStatus.OverrideOnly
public interface DiscReader {
    /**
     * The disc opened through {@link CmsDiscs#open}, or null to leave it to CMS or another reader. A failure stops the
     * disc from loading, with its message shown to the player.
     */
    @Nullable
    DiscHandle open(DiscRequest request) throws IOException;
}
