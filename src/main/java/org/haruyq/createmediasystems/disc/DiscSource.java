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

import org.haruyq.createmediasystems.stream.DiscKind;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonParser;

/**
 * A disc as FFmpeg reads it for sharing: a Blu-ray's playlist, a DVD's title, or (with {@code cd}) an audio CD from
 * its first track to its last.
 */
public record DiscSource(String ffmpeg, Path root, boolean bluray, int title, double duration,
                         BlurayPlaylist playlist, Access access, CdAudio cd) implements AutoCloseable {
    public static final int SEGMENT_SECONDS = 4;
    public static final int MAX_SEGMENT_BYTES = 8 * 1024 * 1024;
    public static final double MAX_DURATION = 12 * 60 * 60;
    /** The most a DVD is encoded at: its own picture, which a larger one only takes longer to encode and send. */
    private static final Quality DVD = new Quality(480, 30, 2500);
    private static final java.util.regex.Pattern HARDWARE_ENCODER = java.util.regex.Pattern.compile("h264_(nvenc|qsv|amf)");

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

    public DiscSource(String ffmpeg, Path root, boolean bluray, int title, double duration, BlurayPlaylist playlist,
                      Access access) {
        this(ffmpeg, root, bluray, title, duration, playlist, access, null);
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

    /** The kind of the disc at {@code root}, as its files show it: a Blu-ray, a DVD or an audio CD; null for none. */
    @Nullable
    public static DiscKind kind(Path root) {
        return Files.isRegularFile(root.resolve("BDMV/index.bdmv")) ? DiscKind.BLU_RAY
                : Files.isRegularFile(root.resolve("VIDEO_TS/VIDEO_TS.IFO")) ? DiscKind.DVD
                : CdAudio.find(root) != null ? DiscKind.CD : null;
    }

    private static DiscSource read(String ffmpeg, String ffprobe, Path path, int title, Access access) throws IOException {
        Path root = path.toRealPath();
        String name = root.getFileName() == null ? "" : root.getFileName().toString();
        if (name.equalsIgnoreCase("VIDEO_TS") || name.equalsIgnoreCase("BDMV")) {
            root = root.getParent();
        }
        DiscKind kind = kind(root);
        boolean bluray = kind == DiscKind.BLU_RAY;
        if (!bluray && kind != DiscKind.DVD) {
            CdAudio cd = kind == DiscKind.CD && access == null ? CdAudio.open(root) : null;
            if (cd == null) {
                throw new IOException("Select a disc drive or a folder containing VIDEO_TS, BDMV or a CD image");
            }
            double duration = (double) cd.sectors() / 75;
            if (duration <= 0 || duration > MAX_DURATION) {
                cd.close();
                throw new IOException("This CD has no usable length");
            }
            return new DiscSource(ffmpeg, root, false, -1, duration, null, null, cd);
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
                    byte[] bytes;
                    try (InputStream in = Files.newInputStream(file)) {
                        bytes = in.readNBytes(1024 * 1024);
                    } catch (IOException refused) {
                        // A drive refuses CSS-scrambled sectors to anything but an authenticated reader.
                        throw new IOException("This DVD could not be read and is likely CSS-encrypted, which needs CMS Disc Extension ("
                                + refused.getMessage() + ")");
                    }
                    if (scrambled(bytes)) {
                        throw new IOException("CSS-encrypted DVDs are not supported; select an unencrypted disc");
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
        if (cd != null) cd.close();
    }

    /**
     * Whether each segment is encoded apart, to be asked for in any order and at any quality: a Blu-ray's and a
     * CD's. A DVD's come from one pass (see run).
     */
    public boolean segmented() {
        return bluray || cd != null;
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

    /** What it is encoded at for a viewer taking {@code budget} bits a second. */
    public Quality quality(long budget) {
        Quality quality = Quality.forBudget(budget);
        return segmented() || quality.height() <= DVD.height() ? quality : DVD;
    }

    public String encoder() {
        if (cd != null) {
            return "ac3";
        }
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
        if (cd != null) {
            return encodeCd(segment, seconds);
        }
        return run(command(segment, seconds, quality, encoder, false),
                playlist == null ? new byte[0] : playlist.concat(root, segment * SEGMENT_SECONDS, seconds, SEGMENT_SECONDS, access),
                MAX_SEGMENT_BYTES, 30, access);
    }

    /**
     * A CD's segment: its samples piped to FFmpeg with half a second before them and a little after, which the
     * resampler runs through before the cut, so segments encoded apart meet sample for sample. Half a second is a
     * whole number of samples at both rates, and in step between them (147 in for 160 out).
     */
    private byte[] encodeCd(int segment, double seconds) throws IOException {
        long start = (long) segment * SEGMENT_SECONDS * CdAudio.RATE;
        long from = Math.max(0, start - CdAudio.RATE / 2);
        long to = Math.min(cd.sectors() * CdAudio.SECTOR_SAMPLES, start + Math.round(seconds * CdAudio.RATE) + CdAudio.RATE / 10);
        long sector = from / CdAudio.SECTOR_SAMPLES;
        byte[] read = cd.read(sector, (int) ((to + CdAudio.SECTOR_SAMPLES - 1) / CdAudio.SECTOR_SAMPLES - sector));
        byte[] samples = java.util.Arrays.copyOfRange(read, (int) (from - sector * CdAudio.SECTOR_SAMPLES) * 4,
                (int) (to - sector * CdAudio.SECTOR_SAMPLES) * 4);
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-v", "error", "-f", "s16le",
                "-ar", Integer.toString(CdAudio.RATE), "-ac", "2", "-i", "pipe:0",
                "-af", "aresample=48000,atrim=start_sample=" + (start - from) * 160 / 147 + ",asetpts=PTS-STARTPTS,apad,atrim=duration="
                        + seconds + ",asetpts=PTS+256/48000/TB"));
        command.addAll(audioTs(segment));
        return run(command, samples, MAX_SEGMENT_BYTES, 30, null);
    }

    /** A segment's sound and container: AC-3, whose frames divide four seconds exactly, in MPEG-TS at its place in the disc. */
    private static List<String> audioTs(int segment) {
        return List.of("-c:a", "ac3", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-mpegts_flags", "+resend_headers+initial_discontinuity", "-muxdelay", "0", "-muxpreload", "0",
                "-output_ts_offset", Integer.toString(segment * SEGMENT_SECONDS), "-f", "mpegts", "pipe:1");
    }

    /**
     * A DVD title encoded in one pass from {@code segment} on (see DiscRun). FFmpeg's DVD reader seeks to the VOBU
     * before the time asked yet stamps it as that time, up to half a second off, so segments encoded apart jumped
     * back and forth at every boundary; one pass only seeks where it starts.
     */
    DiscRun run(int segment, Quality quality, String encoder) throws IOException {
        return new DiscRun(command(segment, duration - (double) segment * SEGMENT_SECONDS, quality, encoder, true),
                access, quality, encoder, segment);
    }

    private List<String> command(int segment, double seconds, Quality quality, String encoder, boolean continuous) {
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
        if (continuous) {
            // Every segment of a pass starts on an IDR frame, so a player can start at any of them.
            command.addAll(List.of("-force_key_frames", "expr:gte(t,n_forced*" + SEGMENT_SECONDS + ")"));
            if (!encoder.equals("libx264")) {
                command.addAll(List.of(encoder.equals("h264_nvenc") ? "-forced-idr" : "-forced_idr", "1"));
            }
        }
        // AC-3 frames divide four seconds exactly. Compensate its 256-sample encoder delay to keep audio at zero.
        command.addAll(List.of("-af", (bluray ? "aselect=concatdec_select," : "")
                        + "asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0,"
                        + (continuous ? "" : "apad,atrim=duration=" + seconds + ",") + "asetpts=PTS+256/48000/TB"));
        command.addAll(audioTs(segment));
        return command;
    }

    /** A hardware encoder would not encode: the disc may read well enough for another (see DiscStream). */
    static final class EncoderFailed extends IOException {
        EncoderFailed(String errors) {
            super("The hardware encoder failed", said(errors));
        }
    }

    /** What FFmpeg said as it failed, as the cause of what the player is told: for this computer's log alone. */
    static IOException said(String errors) {
        String text = errors.strip();
        return text.isEmpty() ? null : new IOException(text.length() > 2000 ? text.substring(text.length() - 2000) : text);
    }

    /**
     * Why a disc reader failed, from its exit code and errors; null if it did not. Its hardware encoder, if FFmpeg's
     * errors name it: anything else (a disc that can't be read, a title that isn't there) is the disc's.
     */
    static IOException failure(int exitCode, String errors, Access access) {
        if (exitCode == 0 && !(access == null && errors.toLowerCase(Locale.ROOT).contains("encrypted"))) {
            return null;
        }
        if (exitCode != 0 && HARDWARE_ENCODER.matcher(errors).find()) {
            return new EncoderFailed(errors);
        }
        return new IOException(access == null
                ? "The disc reader failed; use an unencrypted disc and an FFmpeg build with DVD-Video, H.264 and AC-3 support"
                : "The disc reader failed; check the disc, selected title and reader extension", said(errors));
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
            process = start(command, access);
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
                IOException failure = failure(process.exitValue(), errors.get(), access);
                if (failure != null) {
                    throw failure;
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

    static Process start(List<String> command, Access access) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (access != null) builder.environment().putAll(access.environment());
        return builder.start();
    }
}
