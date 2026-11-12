package org.haruyq.createmediasystems.api.disc;

import java.io.IOException;
import java.nio.file.Path;

import org.haruyq.createmediasystems.disc.DiscReaders;
import org.haruyq.createmediasystems.disc.DiscSource;
import org.jetbrains.annotations.Nullable;

/** Disc readers: client side, from the mod's constructor or client setup. */
public final class CmsDiscs {
    private CmsDiscs() {
    }

    public static void register(DiscReader reader) {
        DiscReaders.register(reader);
    }

    /**
     * Opens the requested disc as CMS does, with FFmpeg and FFprobe at these paths, reaching its files through
     * {@code access} (null: as they are). {@code access} is closed if it can't be opened.
     */
    public static DiscHandle open(DiscRequest request, Path ffmpeg, Path ffprobe, @Nullable DiscAccess access)
            throws IOException {
        return DiscSource.open(ffmpeg.toString(), ffprobe.toString(), request.path(), request.title(), access);
    }
}
