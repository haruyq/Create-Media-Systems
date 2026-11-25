package org.haruyq.createmediasystems.items.instrument.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardFeature;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardAudio;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.StreamPump;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;

final class WearableAudio {
    @Nullable private Synth synth;
    @Nullable private Emitter emitter;
    @Nullable private InstrumentVoices voices;

    @Nullable private GuitarSound guitar;
    GuitarSound guitar() {
        if (guitar == null) guitar = new GuitarSound();
        return guitar;
    }

    private InstrumentVoices voices() {
        if (voices == null) voices = new InstrumentVoices();
        return voices;
    }

    void tick(Player player, InstrumentState notes, boolean powered) {
        var mc = Minecraft.getInstance();
        if (notes.kind == Kind.STRATOCASTER) guitar().pedals(player);
        if (!powered || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0
                || mc.options.getSoundSourceVolume(SoundSource.RECORDS) == 0) {
            stopEmitter();
            return;
        }
        if (emitter != null && !emitter.isStopped()
                && (mc.getSoundManager().isActive(emitter) || System.nanoTime() - emitter.started < 1_000_000_000L)) return;
        if (emitter != null) stopEmitter();
        if (notes.kind == Kind.STRATOCASTER) {
            guitar().update(notes, 0, System.nanoTime());
            emitter = new Emitter(player, null, null, guitar);
            mc.getSoundManager().play(emitter);
            return;
        }
        var font = KeyboardAudio.bank(mc);
        if (font == null || player != mc.player && notes.kind.model == null && !notes.sounding()) return;
        synth = new Synth(font);
        send(Synth.PROGRAM, notes.program(), 0);
        if (notes.kind == Kind.MELODICA) {
            send(Synth.CONTROL, Synth.EXPRESSION, notes.breath());
            send(Synth.CONTROL, Synth.REVERB_SEND, 0);
        }
        if (notes.kind.model != null) {
            voices().update(notes, 0, System.nanoTime());
        } else {
            for (int message : notes.snapshot()) message(notes, message, notes.breath());
        }
        emitter = new Emitter(player, synth, notes.kind.model == null ? null : voices, null);
        mc.getSoundManager().play(emitter);
    }

    void message(InstrumentState notes, int message, int beforeBreath) {
        if (notes.kind == Kind.STRATOCASTER) {
            guitar().update(notes, message, System.nanoTime());
            return;
        }
        if (notes.kind.model != null) {
            voices().update(notes, message, System.nanoTime());
            return;
        }
        if (synth == null) return;
        int number = first(message), value = second(message);
        switch (command(message)) {
            case NOTE -> {
                if (notes.kind == Kind.KEYTAR || notes.breath() > 0) send(Synth.NOTE_ON, number, value);
            }
            case PROGRAM -> send(Synth.PROGRAM, notes.program(), 0);
            case BEND -> send(Synth.BEND, number, value);
            case CONTROL -> {
                if (number == RELEASE_KEYS) {
                    send(Synth.CONTROL, Synth.RESET_CONTROLLERS, 0);
                    send(Synth.CONTROL, Synth.ALL_SOUND_OFF, 0);
                    send(Synth.BEND, 0, 64);
                } else if (number == SUSTAIN) send(Synth.CONTROL, Synth.SUSTAIN_PEDAL, value);
                else if (number == BREATH) breath(notes, beforeBreath);
            }
        }
    }

    private void breath(InstrumentState notes, int before) {
        send(Synth.CONTROL, Synth.EXPRESSION, notes.breath());
        if (notes.breath() == 0) send(Synth.CONTROL, Synth.ALL_SOUND_OFF, 0);
        else if (before == 0) {
            for (int note = notes.kind.first; note <= notes.kind.last(); note++) {
                int velocity = notes.velocity(note);
                if (velocity > 0) send(Synth.NOTE_ON, note, velocity);
            }
        }
    }

    private void send(int command, int first, int second) {
        if (synth != null) synth.send(Synth.message(command, 0, first, second));
    }

    void stop() {
        stopEmitter();
        guitar = null;
    }

    private void stopEmitter() {
        if (emitter != null) {
            emitter.end();
            Minecraft.getInstance().getSoundManager().stop(emitter);
            emitter = null;
        }
        synth = null;
        voices = null;
    }

    private static final class Emitter extends AbstractTickableSoundInstance {
        private final Player player;
        @Nullable private final Synth synth;
        final long started = System.nanoTime();

        @Nullable final InstrumentVoices voices;

        @Nullable final GuitarSound guitar;

        Emitter(Player player, @Nullable Synth synth, @Nullable InstrumentVoices voices, @Nullable GuitarSound guitar) {
            super(KeyboardFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.player = player;
            this.synth = synth;
            this.voices = voices;
            this.guitar = guitar;
            volume = .75f;
            tick();
        }

        void end() { stop(); }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            if (!player.isAlive() || player.isRemoved() || player.level() != Minecraft.getInstance().level) {
                stop();
                return;
            }
            var at = SableCompat.eye(player, 1).add(0, -.45, 0);
            x = at.x;
            y = at.y;
            z = at.z;
        }

        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new LiveStream(synth, this));
        }
    }

    private static final class LiveStream implements CmsStream {
        private static final AudioFormat MONO = new AudioFormat(Synth.RATE, 16, 1, true, false);
        private static final int CAPACITY = 2048;
        @Nullable private final Synth synth;
        private final Emitter emitter;
        private final float[] left = new float[CAPACITY], right = new float[CAPACITY];
        private final ByteBuffer pcm = ByteBuffer.allocateDirect(CAPACITY * 2).order(ByteOrder.LITTLE_ENDIAN);
        private final Runnable refill = this::restart;
        private int source = -1, frames = 256;

        LiveStream(@Nullable Synth synth, Emitter emitter) {
            this.synth = synth;
            this.emitter = emitter;
            StreamPump.live(refill);
        }

        @Override public AudioFormat getFormat() { return MONO; }
        @Override public int bufferBytes(int vanillaBytes) { return frames * 2; }
        @Override public boolean needsFastRefill() { return true; }
        @Override public boolean advancesWhilePaused() { return true; }

        @Override public long attach(int source, long deviceNow) {
            this.source = source;
            long context = ALC10.alcGetCurrentContext();
            if (context != 0) {
                int hz = ALC10.alcGetInteger(ALC10.alcGetContextsDevice(context), ALC10.ALC_REFRESH);
                if (hz > 0) frames = Math.clamp((Synth.RATE * 3 / (8 * hz) + Synth.BLOCK - 1) / Synth.BLOCK
                        * Synth.BLOCK, 256, CAPACITY);
            }
            return 0;
        }

        @Override public ByteBuffer read(int bytes) {
            if (emitter.guitar != null) {
                emitter.guitar.render(left, frames);
                pcm.clear();
                for (int i = 0; i < frames; i++) pcm.putShort((short)Math.round(Math.clamp(left[i],-1,1)*32767));
                return pcm.flip();
            }
            if (emitter.voices == null) {
                synth.render(left, right, 0, frames / Synth.BLOCK);
            } else {
                for (int start = 0; start < frames; start += Synth.BLOCK) {
                    emitter.voices.nextBlock(synth);
                    synth.render(left, right, start, 1);
                }
            }
            pcm.clear();
            for (int frame = 0; frame < frames; frame++) {
                pcm.putShort((short) Math.round(Math.clamp((left[frame] + right[frame]) * .7f, -1, 1) * 32767));
            }
            return pcm.flip();
        }

        private void restart() {
            if (source >= 0 && !emitter.isStopped() && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                AL10.alSourcePlay(source);
            }
        }

        @Override public void close() { StreamPump.ended(refill); }
    }
}
