package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

class MegaphoneAudioStreamTest {
    static class Tone implements AudioStream,DeviceClock.Timed {
        final double frequency,amplitude;int sample;boolean closed;
        Tone(double hz,double amplitude){frequency=hz;this.amplitude=amplitude;}
        public AudioFormat getFormat(){return new AudioFormat(48000,16,1,true,false);}
        public ByteBuffer read(int bytes){var b=ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<bytes/2;i++)b.putShort((short)(Math.sin(sample++*frequency*2*Math.PI/48000)*amplitude*32767));return b.flip();}
        public void close(){closed=true;}
        public long attach(int source,long now){return 9321;}public boolean advancesWhilePaused(){return true;}
    }
    static double rms(ByteBuffer b){double sum=0;int n=0;for(int i=b.limit()/2;i+1<b.limit();i+=2){double x=b.getShort(i)/32768.0;sum+=x*x;n++;}return Math.sqrt(sum/n);}
    static double harmonic(ByteBuffer b,double hz){double re=0,im=0;int from=b.limit()/4/2*2,n=(b.limit()-from)/2;
        for(int i=from;i+1<b.limit();i+=2){double t=i/2.0/48000,x=b.getShort(i)/32768.0;re+=x*Math.cos(2*Math.PI*hz*t);im+=x*Math.sin(2*Math.PI*hz*t);}return Math.hypot(re,im)/n;}
    @Test void realPcmHasNarrowHornBandwidthAndNasalMidrange()throws Exception {
        double mid=rms(new MegaphoneAudioStream(new Tone(1650,.035),()->0,()->8).read(96000));
        double bass=rms(new MegaphoneAudioStream(new Tone(100,.035),()->0,()->8).read(96000));
        double high=rms(new MegaphoneAudioStream(new Tone(10000,.035),()->0,()->8).read(96000));
        assertTrue(mid>15*bass,"Bass must actually be removed from the waveform");assertTrue(mid>8*high,"The horn must lose its hi-fi treble");
    }
    @Test void higherOutputAddsAmplifierHarmonicsRatherThanOnlyRaisingVolume()throws Exception {
        var quiet=new MegaphoneAudioStream(new Tone(900,.25),()->0,()->6).read(96000);
        var loud=new MegaphoneAudioStream(new Tone(900,.25),()->2,()->32).read(96000);
        double a=harmonic(quiet,2700)/harmonic(quiet,900),b=harmonic(loud,2700)/harmonic(loud,900);
        assertTrue(b>a*1.5,"Overload must add measurable third harmonics: "+a+" -> "+b);
    }
    @Test void filterAndAgcRemainContinuousAcrossAudioBufferBoundaries()throws Exception {
        var whole=new MegaphoneAudioStream(new Tone(1200,.2),()->1,()->22).read(32768);
        var filter=new MegaphoneAudioStream(new Tone(1200,.2),()->1,()->22);var split=ByteBuffer.allocate(32768);
        for(int i=0;i<64;i++)split.put(filter.read(512));split.flip();assertEquals(whole,split);
    }
    @Test void digitalSilenceDoesNotCreateHissOrDcAndTimingAndClosureReachTheDecoder()throws Exception {
        var tone=new Tone(0,0);var filter=new MegaphoneAudioStream(tone,()->2,()->32);var b=filter.read(8192);
        while(b.remaining()>=2)assertEquals(0,b.getShort());assertEquals(9321,filter.attach(4,5));assertTrue(filter.advancesWhilePaused());filter.close();assertTrue(tone.closed);
    }
}
