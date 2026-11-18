package org.haruyq.createmediasystems.screen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFiles;

import com.mojang.logging.LogUtils;

/**
 * A screen share's sound: cms-screen-audio.exe (tools/screen-audio) takes each source, an app's (its process tree),
 * the whole PC's but this game's, or a microphone's, as 16-bit 48 kHz stereo, and this mixes them into 10 ms chunks.
 */
final class ScreenSound implements AutoCloseable {
    /** Built by tools/screen-audio/build.sh. */
    private static final String BUNDLED = "/create_media_systems/screen-audio/cms-screen-audio.exe";
    /** 10 ms of 48 kHz stereo, 16-bit. */
    static final int CHUNK = 480 * 4;
    /** Chunks a source may get ahead of the mix before its oldest go: its clock and ours drift apart. */
    private static final int MAX_QUEUED = 6;

    /** A microphone or other capture device, as the exe lists it. */
    record Device(String id, String name) {
    }

    private final List<Process> processes = new ArrayList<>();
    private final List<ArrayDeque<byte[]>> queues = new ArrayList<>();
    /** How loud the mix goes out, in percent: 100 as heard, up to 200. */
    private final IntSupplier volume;
    private final ScheduledExecutorService mixer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("CMS screen sound").factory());
    /** The loudest sample of the last chunk, 0..1, for the preview's meter. */
    volatile float level;

    /**
     * Each of {@code sources} (the exe's arguments, as {@code include <pid>}) mixed into {@code out} every 10 ms, at
     * {@code volume} percent.
     */
    ScreenSound(Path exe, List<List<String>> sources, IntSupplier volume, Consumer<byte[]> out) throws IOException {
        this.volume = volume;
        try {
            for (List<String> source : sources) {
                List<String> command = new ArrayList<>(List.of(exe.toString()));
                command.addAll(source);
                Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start();
                ArrayDeque<byte[]> queue = new ArrayDeque<>();
                processes.add(process);
                queues.add(queue);
                Thread.ofPlatform().daemon().name("CMS screen sound source").start(() -> read(process, queue));
            }
        } catch (IOException failed) {
            close();
            throw failed;
        }
        mixer.scheduleAtFixedRate(() -> out.accept(mix()), 10, 10, TimeUnit.MILLISECONDS);
    }

    private static void read(Process process, ArrayDeque<byte[]> queue) {
        try (InputStream in = process.getInputStream()) {
            while (true) {
                byte[] chunk = in.readNBytes(CHUNK);
                if (chunk.length < CHUNK) {
                    break;
                }
                synchronized (queue) {
                    queue.addLast(chunk);
                    while (queue.size() > MAX_QUEUED) {
                        queue.removeFirst();
                    }
                }
            }
            String why = new String(process.getErrorStream().readNBytes(512), StandardCharsets.UTF_8).trim();
            if (!why.isEmpty()) {
                LogUtils.getLogger().warn("Screen share sound source stopped: {}", why);
            }
        } catch (IOException closed) {
            // stopped
        }
    }

    private byte[] mix() {
        int[] sum = new int[CHUNK / 2];
        for (ArrayDeque<byte[]> queue : queues) {
            byte[] chunk;
            synchronized (queue) {
                chunk = queue.pollFirst();
            }
            if (chunk != null) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += (short) (chunk[i * 2] & 0xff | chunk[i * 2 + 1] << 8);
                }
            }
        }
        byte[] out = new byte[CHUNK];
        int peak = 0;
        int gain = volume.getAsInt();
        for (int i = 0; i < sum.length; i++) {
            int sample = Math.clamp(sum[i] * gain / 100, Short.MIN_VALUE, Short.MAX_VALUE);
            peak = Math.max(peak, Math.abs(sample));
            out[i * 2] = (byte) sample;
            out[i * 2 + 1] = (byte) (sample >> 8);
        }
        level = peak / 32768f;
        return out;
    }

    @Override
    public void close() {
        mixer.shutdownNow();
        // Closing its input ends the exe (it reads stdin for this); killing it is the fallback.
        for (Process process : processes) {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            process.destroy();
        }
    }

    /** The capture devices the exe lists (microphones and the like); none if it can't. */
    static List<Device> devices(Path exe) {
        List<Device> devices = new ArrayList<>();
        try {
            Process list = new ProcessBuilder(exe.toString(), "list").redirectErrorStream(true).start();
            list.getOutputStream().close();
            String text = new String(list.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            if (list.waitFor(5, TimeUnit.SECONDS) && list.exitValue() == 0) {
                for (String line : text.split("\r?\n")) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length == 2) {
                        devices.add(new Device(parts[0], parts[1]));
                    }
                }
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Could not list capture devices: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return devices;
    }

    /** The exe: the configured path, else the one in this mod's jar, written out when missing or changed. Off the game thread. */
    static synchronized Path exe() throws IOException {
        String configured = ClientConfig.SCREEN_AUDIO_PATH.get();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        byte[] bundled;
        try (InputStream in = ScreenSound.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new IOException("The screen sound helper is missing from the mod");
            }
            bundled = in.readAllBytes();
        }
        Path exe = ClientFiles.DIR.resolve("screen-audio").resolve("cms-screen-audio.exe");
        if (Files.isRegularFile(exe) && Arrays.equals(bundled, Files.readAllBytes(exe))) {
            return exe;
        }
        Files.createDirectories(exe.getParent());
        Path part = Files.createTempFile(exe.getParent(), "cms-screen-audio", ".part");
        Files.write(part, bundled);
        Files.move(part, exe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return exe;
    }
}
