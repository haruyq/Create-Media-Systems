package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DiscReaders {
    private static final CopyOnWriteArrayList<Opener> OPENERS = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface Opener {
        /** Return null to let CMS or another extension handle this disc. */
        DiscSource open(Path path, int title, Runnable downloading) throws IOException;
    }

    private DiscReaders() {
    }

    public static void register(Opener opener) {
        OPENERS.addIfAbsent(opener);
    }

    public static DiscSource open(Path directory, String ffmpeg, String ffprobe, Path path,
                                  int title, Runnable downloading) throws IOException {
        for (Opener opener : OPENERS) {
            DiscSource source = opener.open(path, title, downloading);
            if (source != null) return source;
        }
        var tools = DiscTools.ensure(directory, ffmpeg, ffprobe, downloading);
        return DiscSource.open(tools.ffmpeg(), tools.ffprobe(), path, title);
    }
}
