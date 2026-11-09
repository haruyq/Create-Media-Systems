package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record BlurayPlaylist(int title, List<Clip> clips) {
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
                data.position(at + size);
            }
            BlurayPlaylist playlist = new BlurayPlaylist(title, List.copyOf(clips));
            if (playlist.duration() > DiscSource.MAX_DURATION) {
                throw new IllegalArgumentException();
            }
            return playlist;
        } catch (RuntimeException invalid) {
            throw new IOException("The Blu-ray playlist is damaged or contains unsupported still pictures");
        }
    }

    byte[] concat(Path root) throws IOException {
        return concat(root, 0, duration(), 0);
    }

    byte[] concat(Path root, double start, double seconds) throws IOException {
        return concat(root, start, seconds, DiscSource.SEGMENT_SECONDS);
    }

    private byte[] concat(Path root, double start, double seconds, double preroll) throws IOException {
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
            String name = path.toAbsolutePath().toString().replace('\\', '/');
            if (name.contains("\n") || name.contains("\r")) {
                throw new IOException("The disc path contains a newline");
            }
            text.append("file 'file:").append(name.replace("'", "'\\''")).append("'\n")
                    .append("inpoint ").append(in - preroll).append("\noutpoint ").append(out)
                    .append("\nduration ").append(to - from).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
