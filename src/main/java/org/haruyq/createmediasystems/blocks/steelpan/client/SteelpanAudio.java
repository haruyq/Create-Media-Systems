package org.haruyq.createmediasystems.blocks.steelpan.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardAudio;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanFeature;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets.Target;
import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.DeviceClock;
import org.haruyq.createmediasystems.playback.StreamPump;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

final class SteelpanAudio {
    private static final Map<Target, Voice> VOICES = new HashMap<>();
    private static final long RING_TIME = 3_500_000_000L;
    private static final int PROGRAM = 114; // General MIDI Steel Drums; zero-based.
    private static final int NOTES = SteelpanLayout.HIGHEST - SteelpanLayout.LOWEST + 1;
    private static SoundFont preparedBank;

    private SteelpanAudio() {}

    static void strike(Target target, int midi, int velocity, long impact) {
        var mc = Minecraft.getInstance();
        if (!enabled(mc)) return;
        var voice = VOICES.computeIfAbsent(target, Voice::new);
        voice.add(new Hit(midi, velocity, impact));
        voice.start(mc, bank(mc), System.nanoTime());
    }

    static void prepare() {
        var mc = Minecraft.getInstance();
        if (enabled(mc)) bank(mc);
    }

    private static SoundFont bank(Minecraft mc) {
        var bank = KeyboardAudio.bank(mc);
        if (bank != null && bank != preparedBank) {
            preparedBank = bank;
            bank.prepare(bank.preset(0, PROGRAM));
        }
        return bank;
    }

    static void tick() {
        if (VOICES.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (!enabled(mc)) {
            clear();
            return;
        }
        var bank = bank(mc);
        long now = System.nanoTime();
        VOICES.values().removeIf(voice -> {
            if (now >= voice.expires || voice.target.origin(mc.level, 1) == null) {
                voice.end(mc);
                return true;
            }
            voice.start(mc, bank, now);
            return false;
        });
    }

    private static boolean enabled(Minecraft mc) {
        return mc.level != null && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                && mc.options.getSoundSourceVolume(SoundSource.RECORDS) > 0;
    }

    static void clear() {
        var mc = Minecraft.getInstance();
        VOICES.values().forEach(voice -> voice.end(mc));
        VOICES.clear();
    }

    private record Hit(int midi, int velocity, long impact) {}

    private static final class Voice {
        final Target target;
        private final PriorityQueue<Hit> hits = new PriorityQueue<>(Comparator.comparingLong(Hit::impact));
        long expires;
        private Emitter emitter;

        Voice(Target target) {
            this.target = target;
        }

        void add(Hit hit) {
            synchronized (hits) {
                if (hits.size() >= 64) hits.remove();
                hits.add(hit);
            }
            expires = Math.max(expires, hit.impact + RING_TIME);
        }

        Hit poll(long until) {
            synchronized (hits) {
                var next = hits.peek();
                return next != null && next.impact <= until ? hits.remove() : null;
            }
        }

        void start(Minecraft mc, SoundFont bank, long now) {
            if (bank == null) return;
            if (emitter != null && !emitter.isStopped()
                    && (now - emitter.started < 300_000_000L || mc.getSoundManager().isActive(emitter))) return;
            end(mc);
            synchronized (hits) {
                // A sound device may take several update cycles to open a streaming channel.
                hits.removeIf(hit -> now - hit.impact > RING_TIME);
                if (hits.isEmpty()) return;
            }
            emitter = new Emitter(this, bank);
            mc.getSoundManager().play(emitter);
        }

        void end(Minecraft mc) {
            if (emitter == null) return;
            emitter.end();
            mc.getSoundManager().stop(emitter);
            emitter = null;
        }
    }

    private static final class Emitter extends AbstractTickableSoundInstance {
        final long started = System.nanoTime();
        private final Voice voice;
        private final SoundFont bank;

        Emitter(Voice voice, SoundFont bank) {
            super(SteelpanFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.voice = voice;
            this.bank = bank;
            attenuation = Attenuation.NONE;
            tick();
        }

        void end() { stop(); }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            var mc = Minecraft.getInstance();
            Vec3 at = mc.level == null ? null : voice.target.origin(mc.level, 1);
            if (at == null) {
                stop();
                return;
            }
            x = at.x;
            y = at.y;
            z = at.z;
            double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
            double remaining = Math.max(0, 1 - distance / SteelpanPackets.HEARING_RANGE);
            volume = (float) (.9 * remaining / (1 + distance / 12));
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new LiveStream(this, new Synth(bank)));
        }
    }

    private static final class LiveStream implements CmsStream {
        private static final AudioFormat FORMAT = new AudioFormat(Synth.RATE, 16, 1, true, false);
        private static final int MIN_FRAMES = 512, CAPACITY = 2048;
        private static final long NOTE_LENGTH = 1_650_000_000L;
        private final Emitter emitter;
        private final Synth synth;
        private final Runnable keepPlaying = this::restart;
        private final float[] left = new float[Synth.BLOCK], right = new float[Synth.BLOCK];
        private final long[] release = new long[NOTES];
        private final ByteBuffer pcm = ByteBuffer.allocateDirect(CAPACITY * 2).order(ByteOrder.LITTLE_ENDIAN);
        private int source = -1;
        private int frames = MIN_FRAMES;

        LiveStream(Emitter emitter, Synth synth) {
            this.emitter = emitter;
            this.synth = synth;
            send(Synth.PROGRAM, PROGRAM, 0);
            send(Synth.CONTROL, Synth.REVERB_SEND, 12);
            StreamPump.live(keepPlaying);
        }

        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public int bufferBytes(int vanillaBytes) { return frames * 2; }
        @Override public boolean needsFastRefill() { return true; }
        @Override public boolean advancesWhilePaused() { return true; }

        @Override
        public long attach(int source, long deviceNow) {
            this.source = source;
            long context = ALC10.alcGetCurrentContext();
            if (context != 0) {
                int refresh = ALC10.alcGetInteger(ALC10.alcGetContextsDevice(context), ALC10.ALC_REFRESH);
                if (refresh > 0) {
                    int target = Synth.RATE * 3 / (8 * refresh);
                    frames = Math.clamp((target + Synth.BLOCK - 1) / Synth.BLOCK * Synth.BLOCK, MIN_FRAMES, CAPACITY);
                }
            }
            return 0;
        }

        @Override
        public ByteBuffer read(int bytes) {
            long queued = (long) AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) * frames;
            long start = DeviceClock.presentationTime(source, 0, queued, Synth.RATE);
            pcm.clear();
            for (int frame = 0; frame < frames; frame += Synth.BLOCK) {
                long at = start + frame * 1_000_000_000L / Synth.RATE;
                for (int index = 0; index < release.length; index++) {
                    if (release[index] == 0 || release[index] > at) continue;
                    send(Synth.NOTE_OFF, SteelpanLayout.LOWEST + index, 0);
                    release[index] = 0;
                }
                for (Hit hit; (hit = emitter.voice.poll(at)) != null;) {
                    if (at - hit.impact > RING_TIME) continue;
                    send(Synth.NOTE_ON, hit.midi, hit.velocity);
                    release[hit.midi - SteelpanLayout.LOWEST] = at + NOTE_LENGTH;
                }
                synth.render(left, right, 0, 1);
                for (int i = 0; i < Synth.BLOCK; i++) {
                    pcm.putShort((short) Math.round(Math.clamp((left[i] + right[i]) * .7f, -1, 1) * 32767));
                }
            }
            return pcm.flip();
        }

        private void send(int message, int a, int b) {
            synth.send(Synth.message(message, 0, a, b));
        }

        private void restart() {
            if (source >= 0 && !emitter.isStopped() && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                AL10.alSourcePlay(source);
            }
        }

        @Override public void close() { StreamPump.ended(keepPlaying); }
    }
}
