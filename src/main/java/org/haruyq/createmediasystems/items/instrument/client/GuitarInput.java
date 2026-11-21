package org.haruyq.createmediasystems.items.instrument.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiConsumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.haruyq.createmediasystems.items.instrument.GuitarFrame;
import org.haruyq.createmediasystems.playback.Pcm;

/** Opens exactly the recording endpoint the performer selects. Device reads and transcription run off the game thread. */
final class GuitarInput implements AutoCloseable {
    record Device(Mixer.Info info, int channels) {
        String name() { return info.getName(); }
    }
    enum Status { OFF, OPENING, LIVE, FAILED }
    private final BiConsumer<short[], byte[]> receiver;
    private volatile Status status = Status.OFF;
    private volatile int generation;
    private TargetDataLine openLine;
    private final Object deviceLock = new Object();
    private volatile double gain = 1;
    private volatile int peak;

    GuitarInput(BiConsumer<short[], byte[]> receiver) { this.receiver = receiver; }
    Status status() { return status; }
    int peak() { return peak; }
    void gainDb(int db) { gain = Math.pow(10, db / 20.0); }

    static List<Device> devices() {
        List<Device> result = new ArrayList<>();
        for (var info : AudioSystem.getMixerInfo()) {
            try {
                int channels = 0;
                for (var line : AudioSystem.getMixer(info).getTargetLineInfo()) {
                    if (!(line instanceof DataLine.Info data) || !TargetDataLine.class.isAssignableFrom(line.getLineClass())) continue;
                    channels = Math.max(1,channels);
                    for (AudioFormat format : data.getFormats()) {
                        int n = format.getChannels();
                        channels = Math.max(channels, n == AudioSystem.NOT_SPECIFIED ? 2 : Math.min(32,n));
                    }
                }
                if (channels > 0) result.add(new Device(info,channels));
            } catch (IllegalArgumentException ignored) { /* Unplugged during enumeration. */ }
        }
        return List.copyOf(result);
    }

    void start(Device device, int channel) {
        close();
        int token = generation;
        status = Status.OPENING;
        Thread.ofPlatform().daemon().name("CMS guitar input").start(() -> {
            synchronized (deviceLock) {
                if (generation == token) capture(device, channel, token);
            }
        });
    }

    private void capture(Device device, int channel, int token) {
        try (TargetDataLine line = open(device, channel)) {
            synchronized (this) {
                if (token != generation) return;
                openLine = line;
            }
            AudioFormat nativeFormat = line.getFormat();
            var canonical = new AudioFormat(Pcm.RATE,16,nativeFormat.getChannels(),true,false);
            var encoder = new GuitarPcm();
            var tracker = new GuitarTracker();
            try (var raw = new AudioInputStream(line);
                 var stream = canonical.matches(nativeFormat) ? raw : AudioSystem.getAudioInputStream(canonical,raw)) {
                line.start();
                synchronized (this) {
                    if (generation != token) return;
                    status = Status.LIVE;
                }
                byte[] interleaved = new byte[GuitarFrame.SAMPLES * canonical.getFrameSize()];
                while (generation == token) {
                    int used = 0;
                    while (used < interleaved.length && generation == token) {
                        int n = stream.read(interleaved,used,interleaved.length-used);
                        if (n < 0 || n == 0 && !line.isOpen()) throw new IOException("Input disconnected");
                        used += n;
                    }
                    if (generation != token) return;
                    short[] samples = new short[GuitarFrame.SAMPLES];
                    int maximum = channel(interleaved, canonical.getChannels(), channel, gain, samples);
                    byte[] encoded = encoder.encode(samples,tracker.analyze(samples));
                    synchronized (this) {
                        if (generation != token) return;
                        peak = Math.max(maximum, peak*3/4);
                        receiver.accept(samples, encoded);
                    }
                }
            }
        } catch (Exception e) {
            synchronized (this) {
                if (generation == token) {
                    status = Status.FAILED;
                    peak = 0;
                    receiver.accept(new short[0], new byte[0]);
                    com.mojang.logging.LogUtils.getLogger().warn("Guitar input {} channel {}: {}",
                            device.name(), channel + 1, e.toString());
                }
            }
        } finally {
            synchronized (this) { if (generation == token) openLine = null; }
        }
    }

    private static TargetDataLine open(Device device, int channel) throws LineUnavailableException {
        var mixer = AudioSystem.getMixer(device.info());
        var channels = new LinkedHashSet<Integer>(List.of(2,1));
        for (var info : mixer.getTargetLineInfo()) {
            if (info instanceof DataLine.Info data) {
                for (var format : data.getFormats())
                    if (format.getChannels() > 0 && format.getChannels() <= 32) channels.add(format.getChannels());
            }
        }
        LineUnavailableException last = new LineUnavailableException("No supported PCM input format");
        for (int rate : new int[] {48000,44100,96000,88200,192000,32000}) {
            for (int n : channels) {
                if (channel < 0 || n <= channel) continue;
                for (int bits : new int[] {16,24,32}) {
                    var format = new AudioFormat(rate,bits,n,true,false);
                    var wanted = new DataLine.Info(TargetDataLine.class,format);
                    if (!mixer.isLineSupported(wanted)) continue;
                    TargetDataLine line = null;
                    try {
                        line = (TargetDataLine)mixer.getLine(wanted);
                        line.open(format,rate/20*format.getFrameSize());
                        return line;
                    } catch (LineUnavailableException | IllegalArgumentException e) {
                        if (line != null) line.close();
                        if (e instanceof LineUnavailableException ex) last = ex;
                    }
                }
            }
        }
        throw last;
    }

    static int channel(byte[] input, int channels, int selected, double gain, short[] output) {
        int max = 0;
        for (int i = 0, at = selected*2; i < output.length; i++, at += channels*2) {
            short source = (short)((input[at]&255) | input[at+1]<<8);
            output[i] = (short)Math.clamp(Math.round(source*gain),-32768,32767);
            max = Math.max(max, Math.abs((int)output[i]));
        }
        return max;
    }

    @Override public synchronized void close() {
        generation++;
        status = Status.OFF;
        peak = 0;
        var line = openLine;
        openLine = null;
        if (line != null) Thread.ofPlatform().daemon().name("CMS guitar input close").start(line::close);
    }
}
