package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

class AudibleAudioStreamTest {
    static final class Source implements AudioStream {
        boolean closed;
        public AudioFormat getFormat(){return new AudioFormat(44100,16,1,true,false);}
        public ByteBuffer read(int size){return ByteBuffer.allocateDirect(size);}
        public void close(){closed=true;}
    }
    @Test void queuedAudioIsNotMistakenForWhatTheListenerCurrentlyHears() throws Exception {
        var source=new Source();var audio=new AudibleAudioStream(source,90);long now=10_000_000_000L;
        audio.heard.note(now,90,44100,1);audio.heard.note(now+1_000_000_000L,91,44100,1);
        audio.heard.note(now+2_000_000_000L,92,44100,1);audio.heard.note(now+3_000_000_000L,93,44100,1);
        assertEquals(90.25,audio.heard.seconds(now+250_000_000L),1e-6,"Four seconds buffered must not report the decoder's 94 seconds");
        assertEquals(92.125,audio.heard.seconds(now+2_125_000_000L),1e-6);
        assertEquals(-1,audio.heard.seconds(now-1),"A scheduled source is not yet audible");
        audio.close();assertTrue(source.closed);assertEquals(-1,audio.heard.seconds(now));
    }
    @Test void dopplerAndAStarvedBufferAreMeasuredWithoutInventingPlayback() {
        var audio=new AudibleAudioStream(new Source(),0);long now=10_000_000_000L;
        audio.heard.note(now,12,44100,1.25f);
        assertEquals(12.5,audio.heard.seconds(now+400_000_000L),1e-6,"Use the same pitch as the actual OpenAL source");
        assertEquals(-1,audio.heard.seconds(now+900_000_000L),"An underrun must not silently advance an estimated wall clock");
        audio.heard.note(now+1_000_000_000L,-1,4410,1);
        assertEquals(-1,audio.heard.seconds(now+1_020_000_000L),"API starvation silence has no musical position");
        audio.heard.note(now+1_100_000_000L,14.8,4410,1);
        assertEquals(14.85,audio.heard.seconds(now+1_150_000_000L),1e-6,"The recovered buffer reports its actual new media timestamp");
    }
    @Test void naturalDopplerPhaseIsKeptWhileAStalledStationaryPlaybackIsRealigned() {
        var audio=new AudibleAudioStream(new Source(),10);long now=10_000_000_000L;
        audio.heard.note(now,10,88200,1.2f);double heard=audio.heard.seconds(now+1_500_000_000L),server=11.5;
        assertTrue(heard-server>.25);
        assertFalse(PlaybackSync.realign(heard,server,50,1.2),"Travelling music must retain its natural Doppler pitch instead of repeatedly seeking");
        assertTrue(PlaybackSync.realign(heard,server,50,1),"Once stationary, actual phase drift must recover to the source's own server clock");
    }
}
