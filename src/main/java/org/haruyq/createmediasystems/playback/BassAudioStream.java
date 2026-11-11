package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Real low-frequency PCM boost, applied per speaker after decoding; never affects another CMS source. */
public final class BassAudioStream extends CmsStream.Wrapper {
    private final IntSupplier mode;
    private final double alpha;
    private final double[] low;
    private double blend;
    private final java.util.function.DoubleConsumer meter;
    public BassAudioStream(AudioStream source, IntSupplier mode, java.util.function.DoubleConsumer meter) {
        super(source); this.mode=mode;
        this.meter=meter;
        AudioFormat f=source.getFormat();
        if(f.getSampleSizeInBits()!=16 || f.isBigEndian() || !AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding()))
            throw new IllegalArgumentException("PartyBox needs little-endian PCM16");
        low=new double[f.getChannels()]; alpha=1-Math.exp(-2*Math.PI*140/f.getSampleRate());
    }
    @Override public ByteBuffer read(int bytes) throws IOException {
        ByteBuffer data=source.read(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int start=data.position(), channels=low.length;
        double square=0;int samples=0;int targetMode=Math.clamp(mode.getAsInt(),0,2);
        for(int pos=start;pos+channels*2<=data.limit();pos+=channels*2) {
            blend+=(targetMode-blend)*.0006;
            for(int ch=0;ch<channels;ch++) {
                int at=pos+ch*2; double sample=data.getShort(at)/32768.0;
                low[ch]+=alpha*(sample-low[ch]);
                square+=low[ch]*low[ch];samples++;
                if(blend<.00001)continue;
                double boosted=sample+low[ch]*blend*.8;
                // Soft limiting protects the boosted signal instead of hard-clipping bass transients.
                double limited=Math.abs(boosted)<=.85 ? boosted : Math.copySign(.85+.15*Math.tanh((Math.abs(boosted)-.85)/.15),boosted);
                data.putShort(at,(short)Math.round(Math.clamp(limited,-.99997,.99997)*32767));
            }
        }
        if(samples>0)meter.accept(Math.min(1,Math.sqrt(square/samples)*6));
        return data;
    }
}
