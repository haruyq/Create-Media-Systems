package org.haruyq.createmediasystems.playback;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;
class BassAudioStreamTest {
    private static class Tone implements AudioStream,DeviceClock.Timed {
        final double frequency;Tone(double hz){frequency=hz;}
        public AudioFormat getFormat(){return new AudioFormat(48000,16,1,true,false);}
        public ByteBuffer read(int bytes){var b=ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<bytes/2;i++)b.putShort((short)(Math.sin(i*frequency*2*Math.PI/48000)*7000));return b.flip();}
        public void close(){}
        public long attach(int source,long now){return 1234;}
        public boolean advancesWhilePaused(){return true;}
    }
    private static double rms(ByteBuffer b){double sum=0;int n=0;for(int i=4000;i+1<b.limit();i+=2){double x=b.getShort(i);sum+=x*x;n++;}return Math.sqrt(sum/n);}
    @Test void boostsActualBassInsteadOfOnlyRaisingTheWholeTrackVolume()throws Exception{
        var low=new BassAudioStream(new Tone(70),()->2);var high=new BassAudioStream(new Tone(4000),()->2);
        assertTrue(rms(low.read(48000))/rms(new Tone(70).read(48000))>1.7);
        assertTrue(rms(high.read(48000))/rms(new Tone(4000).read(48000))<1.1);
    }
    @Test void offIsTransparentAndNativeDeviceClockIsPreserved()throws Exception{
        var filtered=new BassAudioStream(new Tone(70),()->0);assertEquals(new Tone(70).read(4000),filtered.read(4000));
        assertEquals(1234,filtered.attach(1,1));assertTrue(filtered.advancesWhilePaused());
    }
}
