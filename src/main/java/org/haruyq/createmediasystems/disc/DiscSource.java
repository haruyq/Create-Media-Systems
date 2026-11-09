package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;

public record DiscSource(String ffmpeg, Path root, boolean bluray, int title, double duration,
                         BlurayPlaylist playlist, Access access) implements AutoCloseable {
    public static final int SEGMENT_SECONDS = 4;
    public static final int MAX_SEGMENT_BYTES = 8 * 1024 * 1024;
    public static final double MAX_DURATION = 12 * 60 * 60;

    public interface Access extends AutoCloseable {
        default String media(Path file) {
            return "file:" + file.toAbsolutePath().toString().replace('\\', '/');
        }

        default String protocols() {
            return "file,pipe";
        }

        default Map<String, String> environment() {
            return Map.of();
        }

        @Override
        default void close() {
        }
    }

    public DiscSource(String ffmpeg, Path root, boolean bluray, int title, double duration, BlurayPlaylist playlist) {
        this(ffmpeg, root, bluray, title, duration, playlist, null);
    }

    public static DiscSource open(String ffmpeg, String ffprobe, Path path, int title) throws IOException {
        return open(ffmpeg, ffprobe, path, title, null);
    }

    public static DiscSource open(String ffmpeg, String ffprobe, Path path, int title, Access access) throws IOException {
        try {
            return read(ffmpeg, ffprobe, path, title, access);
        } catch (IOException | RuntimeException failed) {
            if (access != null) access.close();
            throw failed;
        }
    }

    private static DiscSource read(String ffmpeg, String ffprobe, Path path, int title, Access access) throws IOException {
        Path root = path.toRealPath();
        String name = root.getFileName() == null ? "" : root.getFileName().toString();
        if (name.equalsIgnoreCase("VIDEO_TS") || name.equalsIgnoreCase("BDMV")) {
            root = root.getParent();
        }
        boolean bluray = Files.isRegularFile(root.resolve("BDMV/index.bdmv"));
        if (!bluray && !Files.isRegularFile(root.resolve("VIDEO_TS/VIDEO_TS.IFO"))) {
            throw new IOException("Select a DVD drive or a folder containing VIDEO_TS or BDMV");
        }
        if (title < -1 || title > (bluray ? 99999 : 99)) {
            throw new IOException("The DVD title or Blu-ray playlist number is out of range");
        }
        if (access == null) unencrypted(root, bluray);
        BlurayPlaylist playlist = bluray ? BlurayPlaylist.open(root, title) : null;
        DiscSource source = new DiscSource(ffmpeg, root, bluray, title, 0, playlist, access);
        List<String> command = new ArrayList<>(List.of(ffprobe, "-v", "error"));
        command.addAll(source.input());
        command.addAll(List.of("-show_entries", "format=duration:stream=codec_type", "-of", "json"));
        try {
            var info = JsonParser.parseString(new String(run(command, source.stdin(), 256 * 1024, 60, access), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            double duration = playlist == null ? info.getAsJsonObject("format").get("duration").getAsDouble() : playlist.duration();
            boolean video = info.getAsJsonArray("streams").asList().stream()
                    .anyMatch(stream -> "video".equals(stream.getAsJsonObject().get("codec_type").getAsString()));
            if (!video || !Double.isFinite(duration) || duration <= 0 || duration > MAX_DURATION) {
                throw new IOException("The disc title has no video or no usable duration (maximum 12 hours)");
            }
            return new DiscSource(ffmpeg, root, bluray, title, duration, playlist, access);
        } catch (RuntimeException invalid) {
            throw new IOException("FFprobe could not read this disc title; check the selected title and FFmpeg's disc support");
        }
    }

    private static void unencrypted(Path root, boolean bluray) throws IOException {
        if (Files.exists(root.resolve("AACS")) || Files.exists(root.resolve("BDSVM"))) {
            throw new IOException("Encrypted Blu-ray discs are not supported; select an unencrypted disc");
        }
        if (!bluray) {
            try (var files = Files.list(root.resolve("VIDEO_TS"))) {
                for (Path file : files.filter(p -> p.getFileName().toString().toUpperCase(Locale.ROOT).endsWith(".VOB")).toList()) {
                    try (InputStream in = Files.newInputStream(file)) {
                        if (scrambled(in.readNBytes(1024 * 1024))) {
                            throw new IOException("CSS-encrypted DVDs are not supported; select an unencrypted disc");
                        }
                    }
                }
            }
        }
    }

    static boolean scrambled(byte[] bytes) {
        for (int i = 0; i + 6 < bytes.length; i++) {
            int id = bytes[i + 3] & 255;
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1
                    && (id == 0xbd || id >= 0xc0 && id <= 0xef) && (bytes[i + 6] & 0xc0) == 0x80
                    && (bytes[i + 6] & 0x30) != 0) {
                return true;
            }
        }
        return false;
    }

    List<String> input() {
        if (bluray) {
            return List.of("-f", "concat", "-safe", "0", "-segment_time_metadata", "1", "-protocol_whitelist", access == null ? "file,pipe" : access.protocols(), "-i", "pipe:0");
        }
        return List.of("-f", "dvdvideo", "-title", Integer.toString(Math.max(0, title)), "-i", root.toString());
    }

    private byte[] stdin() throws IOException {
        return playlist == null ? new byte[0] : playlist.concat(root, 0, duration == 0 ? playlist.duration() : duration, 0, access);
    }

    @Override
    public void close() {
        if (access != null) access.close();
    }

    public int segments() {
        return (int) Math.ceil(duration / SEGMENT_SECONDS);
    }

    public double seconds(int segment) {
        if (segment < 0 || segment >= segments()) {
            throw new IllegalArgumentException("Disc segment is out of range");
        }
        return Math.min(SEGMENT_SECONDS, duration - (double) segment * SEGMENT_SECONDS);
    }

    public record Quality(int height, int fps, int kbps) {
        public static Quality forBudget(long budget) {
            if (budget >= 11_000_000) {
                return new Quality(1080, 60, 8000);
            }
            if (budget >= 7_000_000) {
                return new Quality(1080, 30, 4500);
            }
            if (budget >= 4_000_000) {
                return new Quality(720, 30, 2500);
            }
            if (budget >= 2_000_000) {
                return new Quality(480, 30, 1100);
            }
            return new Quality(360, 24, (int) Math.max(200, Math.min(650, budget / 1000 * 3 / 5 - 192)));
        }
    }

    public String encoder() {
        for (String encoder : List.of("h264_nvenc", "h264_qsv", "h264_amf")) {
            try {
                run(List.of(ffmpeg, "-v", "error", "-nostdin", "-f", "lavfi", "-i", "color=s=1920x1080:r=60",
                        "-frames:v", "1", "-c:v", encoder, "-f", "null", "-"), 1024, 10);
                return encoder;
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        return "libx264";
    }

    public byte[] encode(int segment, Quality quality, String encoder) throws IOException {
        double seconds = seconds(segment);
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-v", "error", "-nostdin"));
        if (bluray) {
            // Decode references before each in-point; select removes the preroll on the playlist's timeline.
            command.addAll(List.of("-copyts", "-itsoffset", Integer.toString(-SEGMENT_SECONDS)));
        } else {
            command.addAll(List.of("-ss", Integer.toString(segment * SEGMENT_SECONDS)));
        }
        command.addAll(input());
        command.addAll(List.of("-t", Double.toString(seconds), "-map", "0:v:0", "-map", "0:a:0?",
                "-sn", "-dn", "-vf", (bluray ? "select=concatdec_select," : "")
                        + "bwdif=deint=interlaced,scale=w=trunc(oh*dar/2)*2:h=" + quality.height()
                        + ",setsar=1,fps=" + quality.fps() + ",setpts=PTS-STARTPTS",
                "-c:v", encoder, "-pix_fmt", "yuv420p", "-bf", "0", "-g", Integer.toString(quality.fps() * 2),
                "-b:v", quality.kbps() + "k", "-maxrate", quality.kbps() + "k", "-bufsize", quality.kbps() * 2 + "k"));
        if (encoder.equals("libx264")) {
            command.addAll(List.of("-preset", "veryfast", "-threads", "4"));
        }
        if (encoder.equals("h264_nvenc")) {
            command.addAll(List.of("-preset", "p4", "-rc", "cbr"));
        }
        // AC-3 frames divide four seconds exactly. Compensate its 256-sample encoder delay to keep audio at zero.
        command.addAll(List.of("-af", (bluray ? "aselect=concatdec_select," : "")
                        + "asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0,apad,atrim=duration=" + seconds
                        + ",asetpts=PTS+256/48000/TB", "-c:a", "ac3",
                "-b:a", "192k", "-ar", "48000", "-ac", "2", "-mpegts_flags", "+resend_headers+initial_discontinuity",
                "-muxdelay", "0", "-muxpreload", "0",
                "-output_ts_offset", Integer.toString(segment * SEGMENT_SECONDS),
                "-f", "mpegts", "pipe:1"));
        return run(command, playlist == null ? new byte[0] : playlist.concat(root, segment * SEGMENT_SECONDS, seconds, SEGMENT_SECONDS, access),
                MAX_SEGMENT_BYTES, 30, access);
    }

    static byte[] run(List<String> command, int limit, int timeout) throws IOException {
        return run(command, new byte[0], limit, timeout);
    }

    static byte[] run(List<String> command, byte[] input, int limit, int timeout) throws IOException {
        return run(command, input, limit, timeout, null);
    }

    private static byte[] run(List<String> command, byte[] input, int limit, int timeout, Access access) throws IOException {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (access != null) builder.environment().putAll(access.environment());
            process = builder.start();
        } catch (IOException missing) {
            throw new IOException("FFmpeg/FFprobe could not start; set discFfmpegPath and discFfprobePath in cms-client.toml");
        }
        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            io.submit(() -> {
                try (var out = process.getOutputStream()) {
                    out.write(input);
                }
                return null;
            });
            var output = io.submit(() -> {
                byte[] data = process.getInputStream().readNBytes(limit + 1);
                if (data.length > limit) {
                    process.destroyForcibly();
                    throw new IOException("The disc encoder exceeded its output limit");
                }
                return data;
            });
            var errors = io.submit(() -> {
                byte[] data = process.getErrorStream().readNBytes(8192);
                process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
                return new String(data, java.nio.charset.StandardCharsets.UTF_8);
            });
            try {
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    throw new IOException("The disc reader timed out; check the drive, disc and encoder");
                }
                String error = errors.get();
                if (process.exitValue() != 0 || access == null && error.toLowerCase(Locale.ROOT).contains("encrypted")) {
                    throw new IOException(access == null
                            ? "The disc reader failed; use an unencrypted disc and an FFmpeg build with DVD-Video, H.264 and AC-3 support"
                            : "The disc reader failed; check the disc, selected title and reader extension");
                }
                return output.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Disc reading was cancelled");
            } catch (java.util.concurrent.ExecutionException failed) {
                throw new IOException("Could not read the encoded disc segment");
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
