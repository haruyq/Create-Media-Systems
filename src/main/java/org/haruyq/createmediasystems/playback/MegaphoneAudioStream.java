package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.nio.*;
import java.util.function.IntSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Per-source horn colour: band limiting, nasal resonance, fast AGC, asymmetric amplifier overload.
 * No pitch/tempo change or global sound-engine EQ. State spans decoder buffers and is private to this horn.
 */
public final class MegaphoneAudioStream extends CmsStream.Wrapper {
    private final IntSupplier mode,volume;
    private final Biquad[][] filters;
    private final double[] frame;
    private final double attack,release,smooth;
    private double envelope=.05,driveBlend=1,volumeBlend=22;
    public MegaphoneAudioStream(AudioStream source,IntSupplier mode,IntSupplier volume) {
        super(source);this.mode=mode;this.volume=volume;var f=source.getFormat();
        if(f.getSampleSizeInBits()!=16 || f.isBigEndian() || !AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding()))throw new IllegalArgumentException("Megaphone needs little-endian PCM16");
        double rate=f.getSampleRate();attack=1-Math.exp(-1/(rate*.004));release=1-Math.exp(-1/(rate*.12));smooth=1-Math.exp(-1/(rate*.025));
        filters=new Biquad[f.getChannels()][];
        frame=new double[filters.length];
        for(int c=0;c<filters.length;c++)filters[c]=new Biquad[]{Biquad.pass(rate,420,.707,true),Biquad.pass(rate,420,.707,true),
                Biquad.peak(rate,1650,1.55,7),Biquad.pass(rate,3800,.707,false),Biquad.pass(rate,3800,.707,false),Biquad.pass(rate,160,.707,true)};
    }
    @Override public ByteBuffer read(int bytes)throws IOException {
        ByteBuffer data=source.read(bytes).order(ByteOrder.LITTLE_ENDIAN);int channels=filters.length;
        int targetDrive=Math.clamp(mode.getAsInt(),0,2),targetVolume=Math.clamp(volume.getAsInt(),0,32);
        for(int at=data.position();at+2*channels<=data.limit();at+=2*channels) {
            driveBlend+=(targetDrive-driveBlend)*smooth;volumeBlend+=(targetVolume-volumeBlend)*smooth;
            double framePeak=0;
            for(int c=0;c<channels;c++) {
                double x=data.getShort(at+c*2)/32768.0;
                for(int i=0;i<5;i++)x=filters[c][i].apply(x);
                frame[c]=x;framePeak=Math.max(framePeak,Math.abs(x));
            }
            envelope+=(framePeak-envelope)*(framePeak>envelope?attack:release);
            double agc=Math.min(2.2,Math.pow(.19/Math.max(.035,envelope),.58));
            double drive=1.15+driveBlend*.75+Math.pow(volumeBlend/32,2)*1.9;
            for(int c=0;c<channels;c++) {
                double x=frame[c]*agc;
                // Unequal positive/negative rail compression supplies the gritty even harmonics of a small amplifier.
                double y=Math.tanh(drive*(x+.045))-Math.tanh(drive*.045);
                y=filters[c][5].apply(y)*.66;
                data.putShort(at+c*2,(short)Math.round(Math.clamp(y,-.95,.95)*32767));
            }
        }
        return data;
    }
    private static final class Biquad {
        final double b0,b1,b2,a1,a2;double z1,z2;
        Biquad(double b0,double b1,double b2,double a0,double a1,double a2){this.b0=b0/a0;this.b1=b1/a0;this.b2=b2/a0;this.a1=a1/a0;this.a2=a2/a0;}
        double apply(double x){double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
        static Biquad pass(double rate,double frequency,double q,boolean high) {
            double w=2*Math.PI*Math.min(frequency,rate*.4)/rate,c=Math.cos(w),a=Math.sin(w)/(2*q);
            return high?new Biquad((1+c)/2,-(1+c),(1+c)/2,1+a,-2*c,1-a):new Biquad((1-c)/2,1-c,(1-c)/2,1+a,-2*c,1-a);
        }
        static Biquad peak(double rate,double frequency,double q,double db){
            double w=2*Math.PI*Math.min(frequency,rate*.4)/rate,a=Math.sin(w)/(2*q),A=Math.pow(10,db/40),c=Math.cos(w);
            return new Biquad(1+a*A,-2*c,1-a*A,1+a/A,-2*c,1-a/A);
        }
    }
}
