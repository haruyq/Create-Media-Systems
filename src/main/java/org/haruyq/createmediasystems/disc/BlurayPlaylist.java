package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * A Blu-ray's playlist: its clips, and the languages of the first clip's sound and subtitle streams in the order of
 * their PIDs, which is the order FFmpeg numbers them in. Only the playlist names them: the clips themselves do not.
 */
record BlurayPlaylist(int title, List<Clip> clips, List<String> audio, List<String> subtitles) {
    record Clip(String id, double in, double out) {}

    double duration() {
        return clips.stream().mapToDouble(clip -> clip.out - clip.in).sum();
    }

    static BlurayPlaylist open(Path root, int title) throws IOException {
        Path directory = root.resolve("BDMV/PLAYLIST");
        if (title >= 0) {
            return read(directory.resolve(String.format(Locale.ROOT, "%05d.mpls", title)), title);
        }
        BlurayPlaylist longest = null;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[0-9]{5}\\.mpls")).limit(10000).sorted().toList()) {
                try {
                    BlurayPlaylist candidate = read(path, Integer.parseInt(path.getFileName().toString().substring(0, 5)));
                    if (longest == null || candidate.duration() > longest.duration()) {
                        longest = candidate;
                    }
                } catch (IOException unsupported) {
                    // Menu playlists can contain still pictures; auto selection chooses a movie.
                }
            }
        }
        if (longest == null) {
            throw new IOException("No Blu-ray playlist was found");
        }
        return longest;
    }

    private static BlurayPlaylist read(Path file, int title) throws IOException {
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(1024 * 1024 + 1);
        }
        if (bytes.length > 1024 * 1024) {
            throw new IOException("Blu-ray playlist is too large");
        }
        return parse(bytes, title);
    }

    static BlurayPlaylist parse(byte[] bytes, int title) throws IOException {
        try {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            if (data.getInt() != 0x4d504c53) {
                throw new IllegalArgumentException();
            }
            String version = new String(bytes, 4, 4, StandardCharsets.US_ASCII);
            if (!List.of("0100", "0200", "0300").contains(version)) {
                throw new IllegalArgumentException();
            }
            int start = data.getInt(8);
            if (start < 20 || start > bytes.length - 10) {
                throw new IllegalArgumentException();
            }
            int length = data.getInt(start);
            if (length < 6 || length > bytes.length - start - 4) {
                throw new IllegalArgumentException();
            }
            data.limit(start + 4 + length).position(start + 6);
            int count = Short.toUnsignedInt(data.getShort());
            data.getShort();
            if (count == 0 || count > 512) {
                throw new IllegalArgumentException();
            }
            List<Clip> clips = new ArrayList<>();
            TreeMap<Integer, String> audio = new TreeMap<>(), subtitles = new TreeMap<>();
            for (int i = 0; i < count; i++) {
                int size = Short.toUnsignedInt(data.getShort());
                int at = data.position();
                if (size < 32 || size > data.remaining()) {
                    throw new IllegalArgumentException();
                }
                String id = new String(bytes, at, 5, StandardCharsets.US_ASCII);
                String codec = new String(bytes, at + 5, 4, StandardCharsets.US_ASCII);
                if (!id.matches("[0-9]{5}") || !codec.equals("M2TS")) {
                    throw new IllegalArgumentException();
                }
                long in = Integer.toUnsignedLong(data.getInt(at + 12));
                long out = Integer.toUnsignedLong(data.getInt(at + 16));
                // Still pictures have no continuous movie timeline to share.
                if (out <= in || bytes[at + 29] != 0) {
                    throw new IllegalArgumentException();
                }
                clips.add(new Clip(id, in / 45000.0, out / 45000.0));
                if (i == 0) {
                    languages(bytes, at, at + size, audio, subtitles);
                }
                data.position(at + size);
            }
            BlurayPlaylist playlist = new BlurayPlaylist(title, List.copyOf(clips), List.copyOf(audio.values()),
                    List.copyOf(subtitles.values()));
            if (playlist.duration() > DiscSource.MAX_DURATION) {
                throw new IllegalArgumentException();
            }
            return playlist;
        } catch (RuntimeException invalid) {
            throw new IOException("The Blu-ray playlist is damaged or contains unsupported still pictures");
        }
    }

    /**
     * The languages in the stream table of the play item from {@code at} to {@code end}, by PID: its primary sound
     * streams and its subtitle pictures (PGS), as far as they are in its own clip. A table it can't read names none.
     */
    private static void languages(byte[] bytes, int at, int end, TreeMap<Integer, String> audio,
                                  TreeMap<Integer, String> subtitles) {
        try {
            // After the item's angles, if it has more than one: ten bytes each.
            int table = at + 32 + ((bytes[at + 10] & 0x10) == 0 ? 0 : 2 + ((bytes[at + 32] & 255) - 1) * 10);
            if (table + 16 > end) {
                throw new IllegalArgumentException();
            }
            int video = bytes[table + 4] & 255, sound = bytes[table + 5] & 255, pictures = bytes[table + 6] & 255;
            int entry = table + 16;
            for (int i = 0; i < video + sound + pictures; i++) {
                int attributes = entry + 1 + (bytes[entry] & 255);
                if (attributes + 6 > end) {
                    throw new IllegalArgumentException();
                }
                // Type 1 is a stream of the clip itself; then its coding, a sound stream's format, and the language.
                if (i >= video && bytes[entry + 1] == 1) {
                    int pid = (bytes[entry + 2] & 255) << 8 | bytes[entry + 3] & 255;
                    boolean picture = (bytes[attributes + 1] & 255) == 0x90;
                    if (picture || i < video + sound) {
                        (picture ? subtitles : audio).put(pid,
                                new String(bytes, attributes + (picture ? 2 : 3), 3, StandardCharsets.US_ASCII));
                    }
                }
                entry = attributes + 1 + (bytes[attributes] & 255);
            }
        } catch (RuntimeException unreadable) {
            audio.clear();
            subtitles.clear();
        }
    }

    byte[] concat(Path root) throws IOException {
        return concat(root, 0, duration(), 0, null);
    }

    byte[] concat(Path root, double start, double seconds) throws IOException {
        return concat(root, start, seconds, DiscSource.SEGMENT_SECONDS, null);
    }

    byte[] concat(Path root, double start, double seconds, double preroll, DiscSource.Access access) throws IOException {
        StringBuilder text = new StringBuilder("ffconcat version 1.0\n");
        double at = 0;
        for (Clip clip : clips) {
            double from = Math.max(start, at), to = Math.min(start + seconds, at + clip.out - clip.in);
            double in = clip.in + from - at, out = clip.in + to - at;
            at += clip.out - clip.in;
            if (to <= from) {
                continue;
            }
            Path path = root.resolve("BDMV/STREAM/" + clip.id + ".m2ts");
            if (!Files.isRegularFile(path)) {
                throw new IOException("A Blu-ray playlist clip is missing");
            }
            String name = access == null ? "file:" + path.toAbsolutePath().toString().replace('\\', '/') : access.media(path);
            if (name.contains("\n") || name.contains("\r")) {
                throw new IOException("The disc path contains a newline");
            }
            text.append("file '").append(name.replace("'", "'\\''")).append("'\n")
                    .append("inpoint ").append(in - preroll).append("\noutpoint ").append(out)
                    .append("\nduration ").append(to - from).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
