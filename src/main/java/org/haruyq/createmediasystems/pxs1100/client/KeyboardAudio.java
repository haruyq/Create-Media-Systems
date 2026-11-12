package org.haruyq.createmediasystems.pxs1100.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.pxs1100.KeyboardBlockEntity;
import org.haruyq.createmediasystems.pxs1100.PxS1100Feature;
import org.haruyq.createmediasystems.pxs1100.KeyboardMidi;
import org.haruyq.createmediasystems.pxs1100.KeyboardPackets.Frame;
import org.haruyq.createmediasystems.pxs1100.KeyboardSettings;
import org.haruyq.createmediasystems.playback.CmsStream;

final class KeyboardAudio implements AutoCloseable {
    private static final int FRAMES = KeyboardSynth.RATE / 50, BYTES = FRAMES * 2, SLOTS = 16;
    private static final AudioFormat MONO = new AudioFormat(KeyboardSynth.RATE, 16, 1, true, false);

    private record Change(KeyboardSettings settings, boolean reset, List<KeyboardMidi> events) {}

    private static final class StereoStart {
        final long cursor;
        long deviceTime;

        StereoStart(long cursor) { this.cursor = cursor; }

        long attach(long now) {
            if (now == 0) return 0;
            if (deviceTime == 0) deviceTime = now + 32_000_000;
            return Math.max(deviceTime, now);
        }
    }

    private final String font;
    private final int polyphony;
    private final byte[][] stereo = new byte[SLOTS][BYTES * 2];
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private volatile KeyboardSynth synth;
    private volatile boolean closed;
    private Speaker left, right;
    private Frame frame;
    private int retry, volume;
    private long rendered;

    KeyboardAudio(KeyboardSettings settings, Frame frame) {
        font = settings.font;
        polyphony = settings.polyphony;
        this.frame = frame;
        KeyboardSoundbank.synth(font, polyphony).whenComplete((loaded, error) -> {
            if (error != null) {
                LogUtils.getLogger().warn("Could not start keyboard audio", error);
                return;
            }
            synchronized (this) {
                if (closed) loaded.close();
                else synth = loaded;
            }
        });
    }

    boolean matches(KeyboardSettings settings) { return font.equals(settings.font) && polyphony == settings.polyphony; }

    synchronized void update(KeyboardSettings settings, boolean reset, List<KeyboardMidi> events, Supplier<List<KeyboardMidi>> snapshot) {
        volume = settings.volume;
        if (reset || changes.size() >= 64) {
            changes.clear();
            changes.add(new Change(settings, true, snapshot.get()));
            var clicks = events.stream().filter(KeyboardMidi::metronome).toList();
            if (!clicks.isEmpty()) changes.add(new Change(settings, false, clicks));
        } else {
            if (events.isEmpty() && !changes.isEmpty() && changes.getLast().events.isEmpty() && !changes.getLast().reset) changes.removeLast();
            changes.add(new Change(settings, false, events));
        }
    }

    void tick(Minecraft mc, Frame frame) {
        this.frame = frame;
        if (closed || synth == null || retry-- > 0) return;
        var sounds = mc.getSoundManager();
        if (left != null && right != null && sounds.isActive(left) && sounds.isActive(right)) return;
        retry = 19;
        if (mc.options.getSoundSourceVolume(SoundSource.RECORDS) == 0 || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0) return;
        stop();
        var start = new StereoStart(cursor());
        left = new Speaker(0, start);
        right = new Speaker(1, start);
        sounds.play(left);
        sounds.play(right);
    }

    private synchronized long cursor() { return rendered; }

    private synchronized void read(int channel, Mono pcm) throws IOException {
        var out = pcm.samples;
        out.clear();
        if (closed || pcm.closed) {
            for (int i = 0; i < FRAMES; i++) out.putShort((short) 0);
        } else {
            long index = Math.max(pcm.cursor, rendered - SLOTS + 1);
            if (index >= rendered) {
                while (!changes.isEmpty()) {
                    var next = changes.removeFirst();
                    synth.apply(next.settings, next.reset, next.events);
                }
                synth.read(stereo[(int) (rendered % SLOTS)]);
                rendered++;
            }
            byte[] chunk = stereo[(int) (index % SLOTS)];
            for (int i = channel * 2; i < chunk.length; i += 4) out.put(chunk[i]).put(chunk[i + 1]);
            pcm.cursor = index + 1;
        }
        out.flip();
    }

    private void stop() {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (left != null) sounds.stop(left);
        if (right != null) sounds.stop(right);
        left = right = null;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            changes.clear();
            if (synth != null) synth.close();
        }
        stop();
    }

    private final class Speaker extends AbstractTickableSoundInstance {
        private final int channel;
        private final StereoStart start;

        Speaker(int channel, StereoStart start) {
            super(PxS1100Feature.SYNTH.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.channel = channel;
            this.start = start;
            attenuation = Attenuation.NONE;
            tick();
        }

        @Override public boolean canStartSilent() { return true; }

        @Override public WeighedSoundEvents resolve(SoundManager manager) {
            var resolved = super.resolve(manager);
            if (resolved != null && sound != SoundManager.EMPTY_SOUND && sound != SoundManager.INTENTIONALLY_EMPTY_SOUND
                    && !sound.shouldStream()) {
                sound = new Sound(sound.getLocation(), sound.getVolume(), sound.getPitch(), sound.getWeight(), sound.getType(),
                        true, false, sound.getAttenuationDistance());
            }
            return resolved;
        }

        @Override public void tick() {
            if (closed) {
                stop();
                return;
            }
            var at = channel == 0 ? frame.left() : frame.right();
            x = at.x;
            y = at.y;
            z = at.z;
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double distance = at.distanceTo(ear);
            double edge = Math.clamp((KeyboardBlockEntity.AUDIBLE_RANGE - distance) / 8, 0, 1);
            volume = (float) (KeyboardAudio.this.volume / 127.0 * .78 * edge / (1 + distance * distance / 144));
        }

        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new Mono(channel, start));
        }
    }

    private final class Mono implements CmsStream {
        private final int channel;
        private final StereoStart start;
        private final ByteBuffer samples = ByteBuffer.allocateDirect(BYTES);
        private long cursor;
        private boolean closed;

        Mono(int channel, StereoStart start) {
            this.channel = channel;
            this.start = start;
            this.cursor = start.cursor;
        }
        @Override public AudioFormat getFormat() { return MONO; }
        @Override public long attach(int source, long now) { return start.attach(now); }
        @Override public int bufferBytes(int vanillaBytes) { return BYTES; }
        @Override public ByteBuffer read(int size) throws IOException {
            KeyboardAudio.this.read(channel, this);
            return samples;
        }
        @Override public void close() { closed = true; }
    }
}
