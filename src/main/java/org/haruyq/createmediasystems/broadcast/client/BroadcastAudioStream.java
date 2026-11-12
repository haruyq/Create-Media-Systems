package org.haruyq.createmediasystems.broadcast.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;
import org.haruyq.createmediasystems.broadcast.BroadcastProfile;
import org.haruyq.createmediasystems.playback.CmsStream;

/** Speaker bandwidth, propagation and reflections applied only to a tower's own audio. */
public final class BroadcastAudioStream extends CmsStream.Wrapper {
    public static final double METRES_PER_SECOND = 343;
    public record Path(double metres, boolean obstructed) {
        public double transmission() {
            return obstructed ? .58 : 1;
        }
    }

    private final Supplier<Path> path;
    private final boolean horn;
    private final int channels, frames;
    private final double rate, smoothing;
    private final float[] ring;
    private final Band[] highPass, lowPass, presence;
    private final double[] air;
    private int cursor;
    private double direct;

    public static AudioStream wrap(AudioStream source, BroadcastProfile profile, Supplier<Path> path) {
        AudioFormat format = source.getFormat();
        if (format.getSampleSizeInBits() != 16 || format.isBigEndian()
                || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())) return source;
        return new BroadcastAudioStream(source, profile, path);
    }

    private BroadcastAudioStream(AudioStream source, BroadcastProfile profile, Supplier<Path> path) {
        super(source);
        this.path = path;
        horn = profile == BroadcastProfile.HORN;
        rate = source.getFormat().getSampleRate();
        channels = source.getFormat().getChannels();
        smoothing = 1 - Math.exp(-1 / (.12 * rate));
        frames = (int) Math.ceil(rate * (profile.reach / METRES_PER_SECOND + .22)) + 4;
        ring = new float[frames * channels];
        air = new double[channels];
        highPass = new Band[channels];
        lowPass = new Band[channels];
        presence = new Band[channels];
        for (int ch = 0; ch < channels; ch++) {
            highPass[ch] = Band.pass(rate, profile.highPassHz, true);
            lowPass[ch] = Band.pass(rate, profile.lowPassHz, false);
            presence[ch] = Band.pass(rate, 1550, true);
        }
        direct = delay(path.get().metres());
    }

    @Override
    public ByteBuffer read(int bytes) throws IOException {
        ByteBuffer pcm = source.read(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Path current = path.get();
        double target = delay(current.metres());
        double cutoff = (horn ? 6100 : 13_000) / (1 + current.metres() / (horn ? 440 : 680));
        if (current.obstructed()) cutoff = Math.min(cutoff, horn ? 1150 : 1650);
        double airStep = 1 - Math.exp(-2 * Math.PI * Math.min(cutoff, rate * .45) / rate);
        int bounceA = (int) (rate * (horn ? .064 : .052));
        int bounceB = (int) (rate * (horn ? .155 : .117));
        double reflect = (horn ? .20 : .105) + (current.obstructed() ? .075 : 0);
        for (int at = pcm.position(); at + 2 * channels <= pcm.limit(); at += 2 * channels) {
            direct += (target - direct) * smoothing;
            for (int ch = 0; ch < channels; ch++) {
                double x = highPass[ch].apply(pcm.getShort(at + ch * 2) / 32768.0);
                if (horn) {
                    x += .53 * presence[ch].apply(x);
                    double driven = 1.8 * x + .035;
                    x = driven / (1 + Math.abs(driven)) - .034;
                }
                double colored = lowPass[ch].apply(x);
                air[ch] += airStep * (colored - air[ch]);
                ring[cursor * channels + ch] = (float) air[ch];
                double y = tap(direct, ch) + reflect * tap(direct + bounceA, ch)
                        + .57 * reflect * tap(direct + bounceB, ch);
                pcm.putShort(at + ch * 2, (short) Math.round(32767 * Math.clamp(y, -.98, .98)));
            }
            cursor = (cursor + 1) % frames;
        }
        return pcm;
    }

    private double delay(double metres) {
        return Math.clamp(rate * metres / METRES_PER_SECOND, 0, frames - rate * .21);
    }

    private double tap(double delay, int channel) {
        int whole = (int) delay;
        int frame = (cursor + frames - whole) % frames;
        float a = ring[frame * channels + channel];
        float b = ring[(frame == 0 ? frames - 1 : frame - 1) * channels + channel];
        return a + (b - a) * (delay - whole);
    }

    private static final class Band {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        private Band(double b, double a0, double a1, double a2, boolean high) {
            b0 = b / a0;
            b1 = (high ? -2 : 2) * b0;
            b2 = b0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        static Band pass(double rate, double hz, boolean high) {
            double w = 2 * Math.PI * Math.min(hz, rate * .44) / rate;
            double c = Math.cos(w), a = Math.sin(w) / Math.sqrt(2);
            return new Band((1 + (high ? c : -c)) / 2, 1 + a, -2 * c, 1 - a, high);
        }

        double apply(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
