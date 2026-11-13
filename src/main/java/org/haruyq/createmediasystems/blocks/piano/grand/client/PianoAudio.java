package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;

/** The game streams a second at a time. Live playing uses a single short-buffer stereo line instead. */
final class PianoAudio {
    static final PianoAudio INSTANCE = new PianoAudio();

    record Position(float gain, float pan, boolean lidOpen) {}

    static final class Source {
        private final ArrayBlockingQueue<PianoEvent> pending = new ArrayBlockingQueue<>(1536);
        private final PianoSynth synth = new PianoSynth();
        volatile Position position = new Position(0, 0, false);

        void play(List<PianoEvent> events) {
            for (PianoEvent event : events) {
                if (!pending.offer(event)) {
                    pending.clear();
                    pending.offer(PianoEvent.control(PianoEvent.ALL_SOUND_OFF, 0));
                    break;
                }
            }
        }

        void render(PianoSamples samples, float[] left, float[] right, int frames, int rate) {
            for (PianoEvent event = pending.poll(); event != null; event = pending.poll()) synth.accept(event, samples);
            synth.render(left, right, frames, rate, position);
        }
    }

    private volatile PianoSamples bank;
    private volatile List<Source> sources = List.of();
    private volatile boolean outputError, sampleError;
    private Thread worker;
    private long retryAt;

    private PianoAudio() {}

    void samples(PianoSamples bank) {
        this.bank = bank;
        sampleError = bank == null;
    }

    String status() {
        if (sampleError) return "piano.samples_error";
        if (bank == null) return "piano.samples_loading";
        return outputError ? "piano.output_error" : "piano.samples_ready";
    }

    synchronized void sources(List<Source> sources) {
        this.sources = List.copyOf(sources);
        if (worker != null || bank == null || sources.isEmpty() || System.nanoTime() < retryAt) return;
        worker = new Thread(this::run, "CMS Piano Audio");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try (SourceDataLine line = openLine()) {
            outputError = false;
            int rate = (int) line.getFormat().getSampleRate();
            int frames = rate / 100;
            byte[] pcm = new byte[4 * frames];
            float[] left = new float[frames], right = new float[frames];
            line.start();
            int idle = 0;
            while (idle < 35) {
                var active = sources;
                var samples = bank;
                idle = active.isEmpty() ? idle + 1 : 0;
                Arrays.fill(left, 0);
                Arrays.fill(right, 0);
                if (samples != null) {
                    for (Source source : active) source.render(samples, left, right, frames, rate);
                }
                for (int frame = 0; frame < frames; frame++) {
                    write(pcm, 4 * frame, left[frame]);
                    write(pcm, 4 * frame + 2, right[frame]);
                }
                line.write(pcm, 0, pcm.length);
            }
            line.stop();
        } catch (LineUnavailableException | RuntimeException error) {
            outputError = true;
            LogUtils.getLogger().warn("Could not open the grand piano's audio output", error);
        } finally {
            synchronized (this) {
                if (outputError) retryAt = System.nanoTime() + 5_000_000_000L;
                worker = null;
            }
        }
    }

    private static SourceDataLine openLine() throws LineUnavailableException {
        try {
            return open(48_000);
        } catch (LineUnavailableException | IllegalArgumentException unsupported) {
            return open(44_100);
        }
    }

    private static SourceDataLine open(int rate) throws LineUnavailableException {
        var format = new AudioFormat(rate, 16, 2, true, false);
        var line = AudioSystem.getSourceDataLine(format);
        try {
            line.open(format, (rate / 100) * 4 * 3);
            return line;
        } catch (LineUnavailableException | RuntimeException error) {
            line.close();
            throw error;
        }
    }

    private static void write(byte[] bytes, int at, float value) {
        int sample = (int) (32767 * Math.tanh(value));
        bytes[at] = (byte) sample;
        bytes[at + 1] = (byte) (sample >>> 8);
    }
}
