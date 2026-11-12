package org.haruyq.createmediasystems.api.disc;

import java.nio.file.Path;

import org.jetbrains.annotations.ApiStatus;

/** The disc a player asked to load. CMS implements it; more may be added. */
@ApiStatus.NonExtendable
public interface DiscRequest {
    /** The drive or folder chosen: a disc's root, or its VIDEO_TS or BDMV folder. */
    Path path();

    /** The DVD title or Blu-ray playlist number; -1 for the disc's default. */
    int title();

    /** Tells the player that something is being downloaded first (a runtime, keys), which takes a while. */
    void downloading();
}
