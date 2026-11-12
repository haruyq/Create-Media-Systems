package org.haruyq.createmediasystems.api.disc;

import org.jetbrains.annotations.ApiStatus;

/** A disc CMS opened ({@link CmsDiscs#open}), for a {@link DiscReader} to hand back as it is. */
@ApiStatus.NonExtendable
public interface DiscHandle extends AutoCloseable {
    @Override
    void close();
}
