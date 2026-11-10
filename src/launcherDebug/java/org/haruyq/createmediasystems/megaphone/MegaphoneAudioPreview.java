package org.haruyq.createmediasystems.megaphone;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import javax.sound.sampled.*;
import net.minecraft.client.sounds.AudioStream;
import org.haruyq.createmediasystems.playback.MegaphoneAudioStream;

/** Offline preview using the exact shipped DSP, not an approximation in an external audio editor. */
public final class MegaphoneAudioPreview {
    public static void main(String[] args)throws Exception {
        var input=AudioSystem.getAudioInputStream(Path.of(args[0]).toFile());var f=input.getFormat();
        if(f.getSampleSizeInBits()!=16 || f.isBigEndian() || f.getChannels()!=1)throw new IllegalArgumentException("Supply mono PCM16 WAV");
        for(int mode=0;mode<3;mode++) {
            try(var in=AudioSystem.getAudioInputStream(Path.of(args[0]).toFile())) {
                var raw=new AudioStream(){public AudioFormat getFormat(){return f;}
                    public ByteBuffer read(int bytes)throws IOException{return ByteBuffer.wrap(in.readNBytes(bytes));}
                    public void close()throws IOException{in.close();}};
                var output=new ByteArrayOutputStream();int selected=mode;
                try(var horn=new MegaphoneAudioStream(raw,()->selected,()->selected==0?12:32)) {
                    while(true){var chunk=horn.read(8192);if(!chunk.hasRemaining())break;var bytes=new byte[chunk.remaining()];chunk.get(bytes);output.write(bytes);}
                }
                byte[] bytes=output.toByteArray();var audio=new AudioInputStream(new ByteArrayInputStream(bytes),f,bytes.length/f.getFrameSize());
                AudioSystem.write(audio,AudioFileFormat.Type.WAVE,Path.of(args[1],"SBL-Megaphone-"+mode+".wav").toFile());
            }
        }
        input.close();System.out.println("Rendered all 3 horn modes through MegaphoneAudioStream");
    }
}
