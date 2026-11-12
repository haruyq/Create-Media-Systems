package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import org.haruyq.createmediasystems.api.disc.DiscHandle;
import org.haruyq.createmediasystems.api.disc.DiscReader;
import org.haruyq.createmediasystems.api.disc.DiscRequest;

/** The disc readers extensions add (api.disc.CmsDiscs), asked in turn before CMS reads a disc itself. */
public final class DiscReaders {
    private static final CopyOnWriteArrayList<DiscReader> READERS = new CopyOnWriteArrayList<>();

    /** Linked by CMS Disc Extension 0.1.0 (src/test/resources/api/legacy-links.txt); use api.disc.DiscReader. */
    @Deprecated(forRemoval = true)
    @FunctionalInterface
    public interface Opener {
        /** Return null to let CMS or another extension handle this disc. */
        DiscSource open(Path path, int title, Runnable downloading) throws IOException;
    }

    private DiscReaders() {
    }

    /** Linked by CMS Disc Extension 0.1.0; use api.disc.CmsDiscs#register. */
    @Deprecated(forRemoval = true)
    public static void register(Opener opener) {
        register((DiscReader) request -> opener.open(request.path(), request.title(), request::downloading));
    }

    public static void register(DiscReader reader) {
        READERS.addIfAbsent(reader);
    }

    public static DiscSource open(Path directory, String ffmpeg, String ffprobe, Path path,
                                  int title, Runnable downloading) throws IOException {
        DiscRequest request = new DiscRequest() {
            @Override
            public Path path() {
                return path;
            }

            @Override
            public int title() {
                return title;
            }

            @Override
            public void downloading() {
                downloading.run();
            }
        };
        for (DiscReader reader : READERS) {
            DiscHandle handle = reader.open(request);
            if (handle instanceof DiscSource source) return source;
            if (handle != null) {
                handle.close();
                throw new IOException("A disc reader extension returned a disc CMS did not open");
            }
        }
        var tools = DiscTools.ensure(directory, ffmpeg, ffprobe, downloading);
        return DiscSource.open(tools.ffmpeg(), tools.ffprobe(), path, title);
    }
}
