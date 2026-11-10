package org.haruyq.createmediasystems.launcher.client;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.launcher.DopplerMath;
import org.haruyq.createmediasystems.playback.AudibleAudioStream;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** The actual vanilla/data-pack music disc sound, spatialized at the flying disc entity. */
final class FlyingJukeboxSound extends AbstractTickableSoundInstance {
    private final DiscProjectile disc;
    private final double initialOffsetSeconds;
    private volatile AudibleAudioStream audibleClock;
    double audibleSeconds(){var clock=audibleClock;return clock==null?-1:clock.seconds();}

    FlyingJukeboxSound(DiscProjectile disc, SoundEvent actualSong) {
        super(actualSong, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.disc = disc;
        initialOffsetSeconds = disc.elapsedMusicSeconds();
        relative = false;
        attenuation = SoundInstance.Attenuation.NONE;
        volume = 0;
        pitch = 1;
        tick();
    }
    double initialOffsetSeconds(){return initialOffsetSeconds;}

    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents resolved = super.resolve(manager);
        if (resolved != null && sound != SoundManager.EMPTY_SOUND
                && sound != SoundManager.INTENTIONALLY_EMPTY_SOUND && !sound.shouldStream()) {
            // Resource packs may turn streaming off. The static path bypasses
            // getStream entirely and leaves stereo buffers unpositioned.
            sound = new Sound(sound.getLocation(), sound.getVolume(), sound.getPitch(), sound.getWeight(),
                    sound.getType(), true, sound.shouldPreload(), sound.getAttenuationDistance());
        }
        return resolved;
    }

    @Override
    public void tick() {
        if (disc.isRemoved() || !DiscAudioClient.inRange(disc)) {
            stop();
            return;
        }
        x = disc.getX();
        y = disc.getY();
        z = disc.getZ();
        volume = DiscAudioClient.gain(disc);
        pitch = DopplerMath.smooth(pitch, DiscAudioClient.pitchAt(disc));
    }

    /** Always supply mono, including for stereo records: without it OpenAL cannot position the music. */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return soundBuffers.getStream(sound.getPath(), looping).thenApply(source -> {
            MonoDiscAudioStream mono;
            try {
                mono = new MonoDiscAudioStream(source);
            } catch (RuntimeException error) {
                try { source.close(); } catch (IOException ignored) {}
                throw error;
            }
            try {
                double at=disc.elapsedMusicSeconds();mono.skipSeconds(at);
                for(int i=0;i<2;i++){double ahead=disc.elapsedMusicSeconds()-at;if(ahead<.02)break;mono.skipSeconds(ahead);at+=ahead;}
                var clock=new AudibleAudioStream(mono,at,null);audibleClock=clock;return clock;
            } catch (IOException error) {
                try { mono.close(); } catch (IOException ignored) {}
                throw new CompletionException(error);
            }
        });
    }
}
