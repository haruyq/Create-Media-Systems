package org.haruyq.createmediasystems.blocks.stagecustom.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.client.KeyboardSynth;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomFeature;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomMidi;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.Pcm;
import org.haruyq.createmediasystems.playback.StreamPump;
import org.lwjgl.openal.AL10;

/** One positional SoundFont stream per sounding kit, shared by its drums and cymbals. */
final class StageCustomAudio {
    private static final Map<StageCustomBlock.Entity, Emitter> PLAYING = new IdentityHashMap<>();
    private static final long TAIL = 6_000_000_000L;
    private static final int OPEN_HAT_CHANNEL = 1;
    private static boolean warmed;

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
        if (warmed) return;
        warmed = true;
        KeyboardSynth.prepareBundled();
    }

    static KeyboardSettings settings() {
        var settings = new KeyboardSettings();
        settings.main = new KeyboardSettings.Tone(KeyboardSettings.DRUM_BANK, 0);
        settings.reverb = 0;
        return settings;
    }

    static void appendNote(List<KeyboardMidi> events, int note, int velocity) {
        Voice cymbal = StageCustomMidi.voice(note);
        if (cymbal == Voice.HAT_CLOSED || cymbal == Voice.HAT_PEDAL) {
            // Closing the pedal damps the open cymbal while the other pads continue ringing.
            events.add(new KeyboardMidi(KeyboardMidi.CONTROL | OPEN_HAT_CHANNEL, KeyboardMidi.SILENCE, 0));
        }
        int channel = cymbal == Voice.HAT_OPEN ? OPEN_HAT_CHANNEL : 0;
        events.add(new KeyboardMidi(KeyboardMidi.ON | channel, note, velocity));
    }

    private static void strike(StageCustomMidi.Hit hit) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(hit.root()) instanceof StageCustomBlock.Entity kit)
                || mc.options.getSoundSourceVolume(SoundSource.RECORDS) == 0
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0) return;
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
        source = new Emitter(kit, now);
        source.add(hit, now);
        PLAYING.put(kit, source);
        sounds.play(source);
    }

    private static final class Emitter extends AbstractTickableSoundInstance {
        private final StageCustomBlock.Entity kit;
        private final ArrayDeque<StageCustomMidi.Hit> hits = new ArrayDeque<>();
        private final long started;
        private long expires;
        private volatile boolean ended;

        Emitter(StageCustomBlock.Entity kit, long now) {
            super(StageCustomFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.kit = kit;
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

        synchronized List<KeyboardMidi> drain() {
            if (hits.isEmpty()) return List.of();
            var events = new ArrayList<KeyboardMidi>(hits.size());
            for (var hit : hits) appendNote(events, hit.note(), hit.velocity());
            hits.clear();
            return events;
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
            ended = true;
            stop();
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
            return KeyboardSynth.bundled(40).thenApply(synth -> {
                if (ended) {
                    synth.close();
                    throw new CancellationException();
                }
                return new LiveStream(this, synth);
            });
        }
    }

    private static final class LiveStream implements CmsStream {
        private static final AudioFormat MONO = new AudioFormat(Pcm.RATE, 16, 1, true, false);
        private static final int FRAMES = KeyboardSynth.FRAMES_PER_BUFFER * 2;
        private final Emitter emitter;
        private final KeyboardSynth synth;
        private final KeyboardSettings settings = StageCustomAudio.settings();
        private final byte[] stereo = new byte[FRAMES * 4];
        private final ByteBuffer mono = ByteBuffer.allocateDirect(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        private final Runnable refill = this::restart;
        private int source = -1;

        LiveStream(Emitter emitter, KeyboardSynth synth) {
            this.emitter = emitter;
            this.synth = synth;
            synth.apply(settings, true, List.of());
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
            var events = emitter.drain();
            if (!events.isEmpty()) synth.apply(settings, false, events);
            mono.clear();
            int frames = Math.min(bytes / 2, FRAMES);
            for (int offset = 0; offset < frames;) {
                int count = Math.min(KeyboardSynth.FRAMES_PER_BUFFER, frames - offset);
                synth.read(stereo, count);
                for (int i = 0; i < count; i++) {
                    int at = i * 4;
                    int left = stereo[at + 1] << 8 | stereo[at] & 255;
                    int right = stereo[at + 3] << 8 | stereo[at + 2] & 255;
                    mono.putShort((short) ((left + right) / 2));
                }
                offset += count;
            }
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
            synth.close();
        }
    }
}
