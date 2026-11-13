package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.client.MidiDecoder;

record PonderScore(List<Cue> cues, int duration) {
    private static final String ASSET = "/assets/create_media_systems/piano/pxs1100we/un_owen_sabi_piano_only.mid";
    static final int FRAMES_PER_TICK = KeyboardSynth.RATE / 20;

    record Cue(int frame, List<PianoEvent> events) {}

    Cursor cursor() { return new Cursor(cues); }

    static final class Cursor {
        private final List<Cue> cues;
        private int next;

        Cursor(List<Cue> cues) { this.cues = cues; }

        int nextFrame() { return next < cues.size() ? cues.get(next).frame() : Integer.MAX_VALUE; }

        void advance(int frame, Consumer<Cue> apply) {
            while (next < cues.size() && cues.get(next).frame() <= frame) apply.accept(cues.get(next++));
        }
    }

    static PonderScore load() {
        try (var input = PonderScore.class.getResourceAsStream(ASSET)) {
            if (input == null) throw new IOException("Missing " + ASSET);
            return timeline(MidiSystem.getSequence(new BufferedInputStream(input)));
        } catch (IOException | InvalidMidiDataException e) {
            LogUtils.getLogger().warn("Could not load the PX-S1100WE Ponder performance", e);
            return new PonderScore(List.of(), 0);
        }
    }

    private static PonderScore timeline(Sequence sequence) throws InvalidMidiDataException {
        if (sequence.getDivisionType() != Sequence.PPQ) throw new InvalidMidiDataException("Expected PPQ time");
        var midi = new ArrayList<MidiEvent>();
        for (var track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) midi.add(track.get(i));
        }
        midi.sort(Comparator.comparingLong(MidiEvent::getTick));
        var cues = new TreeMap<Integer, List<PianoEvent>>();
        var decoder = new MidiDecoder();
        long last = 0, elapsed = 0, unitsPerSecond = 1_000_000L * sequence.getResolution();
        int tempo = 500_000;
        for (var event : midi) {
            elapsed += (event.getTick() - last) * tempo;
            last = event.getTick();
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                byte[] data = meta.getData();
                if (data.length != 3) throw new InvalidMidiDataException("Invalid tempo event");
                tempo = (data[0] & 255) << 16 | (data[1] & 255) << 8 | data[2] & 255;
            } else if (event.getMessage() instanceof ShortMessage message) {
                var events = decoder.decode(message);
                if (!events.isEmpty()) {
                    int frame = frame(elapsed, unitsPerSecond);
                    cues.computeIfAbsent(frame, ignored -> new ArrayList<>()).addAll(events);
                }
            }
        }
        var score = cues.entrySet().stream().map(cue -> new Cue(cue.getKey(), List.copyOf(cue.getValue()))).toList();
        return new PonderScore(score, (int) Math.ceil(frame(elapsed, unitsPerSecond) / (double) FRAMES_PER_TICK));
    }

    private static int frame(long elapsed, long unitsPerSecond) {
        return Math.toIntExact(Math.round(elapsed * (double) KeyboardSynth.RATE / unitsPerSecond));
    }
}
