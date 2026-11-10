package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.DoubleSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;

/** Measures the media actually heard, rather than the decoder's buffered-ahead position. */
public final class AudibleAudioStream implements AudioStream,DeviceClock.Timed {
    private record Buffer(long at,double seconds,int frames,float pitch) {}
    private final AudioStream source;
    private final DoubleSupplier lastReadPosition;
    private final ArrayDeque<Buffer> queued=new ArrayDeque<>();
    private final float rate;
    private double nextSeconds;
    private int sourceId;
    private long startAt;
    private boolean closed;

    public AudibleAudioStream(AudioStream source,double startSeconds,DoubleSupplier lastReadPosition) {
        this.source=source;this.lastReadPosition=lastReadPosition;nextSeconds=startSeconds;rate=source.getFormat().getSampleRate();
    }
    @Override public AudioFormat getFormat(){return source.getFormat();}
    @Override public synchronized long attach(int id,long now) {
        sourceId=id;queued.clear();startAt=source instanceof DeviceClock.Timed timed?timed.attach(id,now):0;return startAt;
    }
    @Override public boolean advancesWhilePaused(){return true;}
    @Override public ByteBuffer read(int size)throws IOException {
        var pcm=source.read(size);int frames=pcm.remaining()/getFormat().getFrameSize();
        double seconds=lastReadPosition==null?nextSeconds:lastReadPosition.getAsDouble();nextSeconds+=frames/rate;
        synchronized(this) {
            if(sourceId!=0 && frames>0 && !closed) {
                int count=AL10.alGetSourcei(sourceId,AL10.AL_BUFFERS_QUEUED);long samples=0;
                var it=queued.descendingIterator();for(int i=0;i<count && it.hasNext();i++)samples+=it.next().frames;
                float pitch=AL10.alGetSourcef(sourceId,AL10.AL_PITCH);
                note(DeviceClock.presentationTime(sourceId,startAt,samples,rate),seconds,frames,pitch);
            }
        }
        return pcm;
    }
    synchronized void note(long at,double seconds,int frames,float pitch) {
        queued.addLast(new Buffer(at,seconds,frames,pitch));while(queued.size()>32)queued.removeFirst();
    }
    synchronized double position(long now) {
        if(closed)return -1;Buffer current=null;
        for(var buffer:queued)if(buffer.at<=now)current=buffer;
        if(current==null || current.seconds<0)return -1;
        double elapsed=(now-current.at)/1e9*current.pitch;
        return elapsed>current.frames/rate+.025?-1:current.seconds+Math.min(elapsed,current.frames/rate);
    }
    public double seconds(){return position(System.nanoTime());}
    @Override public void close()throws IOException {synchronized(this){closed=true;queued.clear();}source.close();}
}
