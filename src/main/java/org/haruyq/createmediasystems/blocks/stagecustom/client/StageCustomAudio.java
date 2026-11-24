package org.haruyq.createmediasystems.blocks.stagecustom.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardAudio;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomFeature;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomMidi;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.StreamPump;
import org.lwjgl.openal.AL10;

/** One positional SoundFont stream per sounding kit, shared by its drums and cymbals. */
final class StageCustomAudio {
    private static final Map<StageCustomBlock.Entity, Emitter> PLAYING = new IdentityHashMap<>();
    private static final long TAIL = 6_000_000_000L;
    /** Both sides mixed, and brought down to the level the kit had on the electronic piano's synth. */
    private static final float LEVEL = .5f * .62f;
    private static SoundFont preparedBank;

    private StageCustomAudio() {}

    static void register() {
        StageCustomMidi.playback(StageCustomAudio::strike);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> PLAYING.values().removeIf(Emitter::isStopped));
        WorldEvents.onLevelChanged(() -> {
            var sounds = Minecraft.getInstance().getSoundManager();
            PLAYING.values().forEach(emitter -> {
                emitter.end();
                sounds.stop(emitter);
            });
            PLAYING.clear();
        });
    }

    static void prepare() {
        bank(Minecraft.getInstance());
    }

    private static SoundFont bank(Minecraft mc) {
        var bank = KeyboardAudio.bank(mc);
        if (bank != null && bank != preparedBank) {
            preparedBank = bank;
            bank.prepare(bank.preset(SoundFont.DRUMS, 0));
        }
        return bank;
    }

    /** A kit's synth: the standard kit, dry. A closed or pedalled hi-hat cuts the open one off by its exclusive class. */
    static Synth synth(SoundFont bank) {
        var synth = new Synth(bank, 40, 16);
        synth.send(Synth.message(Synth.CONTROL, Synth.DRUM_CHANNEL, Synth.REVERB_SEND, 0));
        return synth;
    }

    static void hit(Synth synth, int note, int velocity) {
        synth.send(Synth.message(Synth.NOTE_ON, Synth.DRUM_CHANNEL, note, velocity));
    }

    /** Renders {@code frames} (whole blocks) of {@code synth} into {@code mono}, a 16-bit sample each. */
    static void render(Synth synth, float[] left, float[] right, int frames, ByteBuffer mono) {
        synth.render(left, right, 0, frames / Synth.BLOCK);
        for (int i = 0; i < frames; i++) {
            mono.putShort((short) Math.round(Math.clamp((left[i] + right[i]) * LEVEL, -1, 1) * 32767));
        }
    }

    private static void strike(StageCustomMidi.Hit hit) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(hit.root()) instanceof StageCustomBlock.Entity kit)
                || mc.options.getSoundSourceVolume(SoundSource.RECORDS) == 0
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0) return;
        var bank = bank(mc);
        if (bank == null) return;
        var sounds = mc.getSoundManager();
        var source = PLAYING.get(kit);
        long now = System.nanoTime();
        if (source != null && !source.isStopped()
                && (sounds.isActive(source) || now - source.started < 1_000_000_000L)) {
            source.add(hit, now);
            return;
        }
        if (source != null) {
            source.end();
            sounds.stop(source);
        }
        source = new Emitter(kit, bank, now);
        source.add(hit, now);
        PLAYING.put(kit, source);
        sounds.play(source);
    }

    private static final class Emitter extends AbstractTickableSoundInstance {
        private final StageCustomBlock.Entity kit;
        private final SoundFont bank;
        private final ArrayDeque<StageCustomMidi.Hit> hits = new ArrayDeque<>();
        private final long started;
        private long expires;

        Emitter(StageCustomBlock.Entity kit, SoundFont bank, long now) {
            super(StageCustomFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.kit = kit;
            this.bank = bank;
            started = now;
            expires = now + TAIL;
            volume = .95f;
            tick();
        }

        synchronized void add(StageCustomMidi.Hit hit, long now) {
            if (hits.size() >= 64) hits.removeFirst();
            hits.addLast(hit);
            expires = now + TAIL;
        }

        synchronized void drain(Synth synth) {
            for (var hit : hits) hit(synth, hit.note(), hit.velocity());
            hits.clear();
        }

        @Override
        public void tick() {
            var mc = Minecraft.getInstance();
            if (mc.level == null || kit.getLevel() != mc.level || kit.isRemoved() || System.nanoTime() >= expires) {
                end();
                return;
            }
            var at = SableCompat.toRenderWorld(mc.level, kit.getBlockPos(), StageCustomMidi.origin(kit));
            x = at.x;
            y = at.y;
            z = at.z;
        }

        void end() {
            stop();
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new LiveStream(this, synth(bank)));
        }
    }

    private static final class LiveStream implements CmsStream {
        private static final AudioFormat MONO = new AudioFormat(Synth.RATE, 16, 1, true, false);
        private static final int FRAMES = 15 * Synth.BLOCK;
        private final Emitter emitter;
        private final Synth synth;
        private final float[] left = new float[FRAMES], right = new float[FRAMES];
        private final ByteBuffer mono = ByteBuffer.allocateDirect(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        private final Runnable refill = this::restart;
        private int source = -1;

        LiveStream(Emitter emitter, Synth synth) {
            this.emitter = emitter;
            this.synth = synth;
            StreamPump.live(refill);
        }

        @Override public AudioFormat getFormat() { return MONO; }
        @Override public int bufferBytes(int vanillaBytes) { return FRAMES * 2; }
        @Override public boolean needsFastRefill() { return true; }

        @Override
        public long attach(int source, long deviceNow) {
            this.source = source;
            return 0;
        }

        @Override
        public ByteBuffer read(int bytes) throws IOException {
            emitter.drain(synth);
            mono.clear();
            render(synth, left, right, Math.min(bytes / 2, FRAMES) / Synth.BLOCK * Synth.BLOCK, mono);
            return mono.flip();
        }

        private void restart() {
            if (source >= 0 && !emitter.isStopped() && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                AL10.alSourcePlay(source);
            }
        }

        @Override
        public void close() {
            StreamPump.ended(refill);
        }
    }
}
