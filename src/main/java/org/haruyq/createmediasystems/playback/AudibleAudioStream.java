package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.client.sounds.AudioStream;

/** A vanilla song's stream, measuring the media actually heard rather than the decoder's buffered-ahead position. */
public final class AudibleAudioStream extends CmsStream.Wrapper {
    final AudibleTimeline heard;
    private final float rate;
    /** Where in the media its next read starts (s). */
    private double nextSeconds;

    /** {@code source}, which starts {@code startSeconds} into its media. */
    public AudibleAudioStream(AudioStream source, double startSeconds) {
        super(source);
        rate = source.getFormat().getSampleRate();
        heard = new AudibleTimeline(rate);
        nextSeconds = startSeconds;
    }

    @Override
    public long attach(int sourceId, long deviceNow) {
        long startAt = super.attach(sourceId, deviceNow);
        heard.attach(sourceId, startAt);
        return startAt;
    }

    @Override
    public boolean advancesWhilePaused() {
        return true;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        ByteBuffer pcm = source.read(size);
        int frames = pcm.remaining() / getFormat().getFrameSize();
        heard.queue(nextSeconds, frames);
        nextSeconds += frames / rate;
        return pcm;
    }

    /** Where in its media what is heard now is (s); -1 if nothing is. */
    public double seconds() {
        return heard.seconds(System.nanoTime());
    }

    @Override
    public void close() throws IOException {
        heard.close();
        super.close();
    }
}
