package org.haruyq.createmediasystems.partybox.client;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.haruyq.createmediasystems.partybox.PartyBoxEntity;
import org.haruyq.createmediasystems.launcher.DopplerMath;
import org.haruyq.createmediasystems.launcher.client.MonoDiscAudioStream;
import org.haruyq.createmediasystems.playback.BassAudioStream;
import org.haruyq.createmediasystems.playback.AudibleAudioStream;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

final class PartyBoxSound extends AbstractTickableSoundInstance {
    private final PartyBoxEntity box;
    private final int revision;
    private final PartyBoxClient.Meter meter;
    private volatile AudibleAudioStream audibleClock;
    double audibleSeconds(){var clock=audibleClock;return clock==null?-1:clock.seconds();}
    PartyBoxSound(PartyBoxEntity box, SoundEvent song,PartyBoxClient.Meter meter) {
        super(song,SoundSource.RECORDS,SoundInstance.createUnseededRandom()); this.box=box; revision=box.revision();
        this.meter=meter;
        attenuation=Attenuation.NONE; relative=false; volume=0; tick();
    }
    @Override public boolean canStartSilent() { return true; }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        var resolved=super.resolve(manager);
        if(resolved!=null && sound!=SoundManager.EMPTY_SOUND && sound!=SoundManager.INTENTIONALLY_EMPTY_SOUND && !sound.shouldStream())
            sound=new Sound(sound.getLocation(),sound.getVolume(),sound.getPitch(),sound.getWeight(),sound.getType(),true,sound.shouldPreload(),sound.getAttenuationDistance());
        return resolved;
    }
    @Override public void tick() {
        if(!PartyBoxClient.audible(box) || revision!=box.revision()) { stop(); return; }
        var p=box.soundPosition(); x=p.x; y=p.y; z=p.z; volume=PartyBoxClient.gain(box);
        pitch=DopplerMath.smooth(pitch,PartyBoxClient.pitchAt(box));
    }
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return buffers.getStream(sound.getPath(),false).thenApply(raw -> {
            MonoDiscAudioStream mono=new MonoDiscAudioStream(raw);
            try {
                double at=box.seconds();mono.skipSeconds(at);
                for(int i=0;i<2;i++){double ahead=box.seconds()-at;if(ahead<.02)break;mono.skipSeconds(ahead);at+=ahead;}
                var clock=new AudibleAudioStream(mono,at);audibleClock=clock;return new BassAudioStream(clock,box::bass,meter::sample);
            }
            catch(IOException error) { try { mono.close(); } catch(IOException ignored) {} throw new java.util.concurrent.CompletionException(error); }
        });
    }
}
