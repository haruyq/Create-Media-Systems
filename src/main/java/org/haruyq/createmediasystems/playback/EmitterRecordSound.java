package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A music emitter's jukebox record: its own song event, streamed in mono from where the emitter's clock is, so OpenAL
 * places it at the emitter and pitches it by its Doppler shift.
 */
final class EmitterRecordSound extends AbstractTickableSoundInstance {
    private final MusicEmitter emitter;
    private final int revision;
    private volatile AudibleAudioStream heard;

    EmitterRecordSound(MusicEmitter emitter, int revision, SoundEvent song) {
        super(song, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.emitter = emitter;
        this.revision = revision;
        relative = false;
        attenuation = SoundInstance.Attenuation.NONE;
        volume = 0;
        pitch = 1;
        tick();
    }

    /** Where in its track what is heard now is (s); -1 if nothing is. */
    double audibleSeconds() {
        AudibleAudioStream clock = heard;
        return clock == null ? -1 : clock.seconds();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

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
        if (!EmitterAudioManager.heard(emitter, revision)) {
            stop();
            return;
        }
        var at = emitter.position();
        x = at.x;
        y = at.y;
        z = at.z;
        volume = (float) EmitterAudioManager.gain(emitter);
        pitch = DopplerMath.smooth(pitch, EmitterAudioManager.pitch(emitter));
    }

    /** Always supply mono, including for stereo records: without it OpenAL cannot position the music. */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return soundBuffers.getStream(sound.getPath(), false).thenApply(source -> {
            MonoAudioStream mono;
            try {
                mono = new MonoAudioStream(source);
            } catch (RuntimeException error) {
                try { source.close(); } catch (IOException ignored) {}
                throw error;
            }
            try {
                double at = emitter.seconds();
                mono.skipSeconds(at);
                // Decoding a late join takes time too: catch up before the first buffer is queued.
                for (int i = 0; i < 2; i++) {
                    double ahead = emitter.seconds() - at;
                    if (ahead < .02) break;
                    mono.skipSeconds(ahead);
                    at += ahead;
                }
                AudibleAudioStream clock = new AudibleAudioStream(mono, at);
                heard = clock;
                AudioStream out = clock;
                for (UnaryOperator<AudioStream> decorator : emitter.decorators()) {
                    out = decorator.apply(out);
                }
                return out;
            } catch (IOException error) {
                try { mono.close(); } catch (IOException ignored) {}
                throw new CompletionException(error);
            }
        });
    }
}
