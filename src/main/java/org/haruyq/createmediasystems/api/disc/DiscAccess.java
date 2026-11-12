package org.haruyq.createmediasystems.api.disc;

import java.nio.file.Path;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * How FFmpeg reaches a disc a {@link DiscReader} opened: by default, its files as they are. Closed with the disc, or
 * when {@link CmsDiscs#open} fails.
 */
@ApiStatus.OverrideOnly
public interface DiscAccess extends AutoCloseable {
    /** The URL FFmpeg reads one of the disc's files ({@code file}: a Blu-ray clip) from. */
    default String media(Path file) {
        return "file:" + file.toAbsolutePath().toString().replace('\\', '/');
    }

    /** FFmpeg's protocol whitelist for those URLs. */
    default String protocols() {
        return "file,pipe";
    }

    /** Environment variables FFmpeg and FFprobe are started with. */
    default Map<String, String> environment() {
        return Map.of();
    }

    @Override
    default void close() {
    }
}
