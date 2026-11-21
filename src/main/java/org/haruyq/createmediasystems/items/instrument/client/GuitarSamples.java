package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.client.KeyboardSynth;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import static org.haruyq.createmediasystems.playback.Pcm.RATE;

/** Plays the same SoundFont presets as the electronic piano, with at most one pitch on each guitar string. */
final class GuitarSamples implements AutoCloseable {
    private record Change(long at, KeyboardSettings settings, List<KeyboardMidi> events, boolean snapshot) {}

    private final CompletableFuture<KeyboardSynth> source;
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private final boolean[] sounding = new boolean[128];
    private final byte[] stereo = new byte[KeyboardSynth.FRAMES_PER_BUFFER * 4];
    private KeyboardSettings settings = new KeyboardSettings();
    private int bend = InstrumentState.CENTER_BEND;
    private long cursor;
    private boolean started, closed;

    GuitarSamples(CompletableFuture<KeyboardSynth> source) {
        this.source = source;
        source.whenComplete((synth, error) -> {
            if (error != null) LogUtils.getLogger().warn("Could not open the guitar SoundFont", error);
        });
    }

    synchronized void update(InstrumentState state, int message, long now) {
        if (closed || !started && !state.sounding()) return;
        boolean snapshot = !started || changes.size() >= 256
                || !changes.isEmpty() && now - changes.getFirst().at() > 1_000_000_000L;
        if (snapshot) {
            changes.clear();
            cursor = 0;
            Arrays.fill(sounding, false);
        }
        started = true;
        boolean newPreset = settings.main.program() != state.program();
        if (newPreset) {
            settings = new KeyboardSettings();
            settings.main = new KeyboardSettings.Tone(0, state.program());
        }
        var events = new ArrayList<KeyboardMidi>();
        if (snapshot && state.bend() != InstrumentState.CENTER_BEND || bend != state.bend()) {
            bend = state.bend();
            events.add(new KeyboardMidi(KeyboardMidi.BEND, bend & 127, bend >> 7));
        }
        for (int note = Kind.STRATOCASTER.first; note <= Kind.STRATOCASTER.last(); note++) {
            int velocity = state.voicedVelocity(note);
            boolean picked = InstrumentState.command(message) == InstrumentState.NOTE
                    && InstrumentState.first(message) == note && InstrumentState.second(message) > 0;
            if (velocity > 0 && (!sounding[note] || picked || newPreset)) events.add(KeyboardMidi.note(note, velocity));
            else if (velocity == 0 && sounding[note]) events.add(KeyboardMidi.note(note, 0));
            sounding[note] = velocity != 0;
        }
        if (snapshot || newPreset || !events.isEmpty()) changes.addLast(new Change(now, settings, events, snapshot));
    }

    synchronized void render(float[] output, int frames) {
        var synth = closed || !started || source.isCompletedExceptionally() ? null : source.getNow(null);
        if (synth == null) {
            Arrays.fill(output, 0, frames, 0);
            return;
        }
        if (cursor == 0 && !changes.isEmpty()) cursor = changes.getFirst().at();
        for (int offset = 0; offset < frames;) {
            while (!changes.isEmpty() && changes.getFirst().at() <= cursor) {
                var change = changes.removeFirst();
                synth.apply(change.settings(), change.snapshot(), change.events());
            }
            int count = Math.min(frames - offset, KeyboardSynth.FRAMES_PER_BUFFER);
            try {
                synth.read(stereo, count);
            } catch (IOException error) {
                LogUtils.getLogger().warn("Could not read the guitar SoundFont", error);
                close();
                Arrays.fill(output, offset, frames, 0);
                return;
            }
            for (int i = 0; i < count; i++) {
                int p = i * 4;
                int left = stereo[p + 1] << 8 | stereo[p] & 255;
                int right = stereo[p + 3] << 8 | stereo[p + 2] & 255;
                output[offset + i] = (left + right) / 65536f;
            }
            cursor += count * 1_000_000_000L / RATE;
            offset += count;
        }
        if (changes.isEmpty()) cursor = 0;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        changes.clear();
        source.thenAccept(KeyboardSynth::close);
    }
}
