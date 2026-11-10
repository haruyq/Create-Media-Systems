package org.haruyq.createmediasystems.megaphone.client;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.sounds.*;
import org.haruyq.createmediasystems.launcher.DopplerMath;
import org.haruyq.createmediasystems.launcher.client.MonoDiscAudioStream;
import org.haruyq.createmediasystems.playback.MegaphoneAudioStream;
import org.haruyq.createmediasystems.playback.AudibleAudioStream;

final class MegaphoneSound extends AbstractTickableSoundInstance {
    private final MegaphoneClient.Emitter source;
    private volatile AudibleAudioStream audibleClock;
    double audibleSeconds(){var clock=audibleClock;return clock==null?-1:clock.seconds();}
    MegaphoneSound(MegaphoneClient.Emitter source,SoundEvent song){super(song,SoundSource.RECORDS,SoundInstance.createUnseededRandom());this.source=source;attenuation=Attenuation.NONE;relative=false;tick();}
    @Override public boolean canStartSilent(){return true;}
    @Override public WeighedSoundEvents resolve(SoundManager manager){
        var result=super.resolve(manager);if(result!=null && sound!=SoundManager.EMPTY_SOUND && sound!=SoundManager.INTENTIONALLY_EMPTY_SOUND && !sound.shouldStream())
            sound=new Sound(sound.getLocation(),sound.getVolume(),sound.getPitch(),sound.getWeight(),sound.getType(),true,sound.shouldPreload(),sound.getAttenuationDistance());return result;
    }
    @Override public void tick(){if(!source.audible()){stop();return;}var p=source.position();x=p.x;y=p.y;z=p.z;volume=(float)source.gain();pitch=DopplerMath.smooth(pitch,source.pitch());}
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers,Sound sound,boolean looping){
        return buffers.getStream(sound.getPath(),false).thenApply(raw->{var mono=new MonoDiscAudioStream(raw);
            try{
                double at=source.seconds();mono.skipSeconds(at);
                // Decoding a late join can take time too. Catch up before the first buffer is queued.
                for(int i=0;i<2;i++){double ahead=source.seconds()-at;if(ahead<.02)break;mono.skipSeconds(ahead);at+=ahead;}
                var clock=new AudibleAudioStream(mono,at,null);audibleClock=clock;
                return new MegaphoneAudioStream(clock,source::drive,source::volume);
            }
            catch(IOException e){try{mono.close();}catch(IOException ignored){}throw new java.util.concurrent.CompletionException(e);}});
    }
}
