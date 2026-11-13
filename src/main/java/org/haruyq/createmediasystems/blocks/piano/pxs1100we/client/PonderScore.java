package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;

record PonderScore(List<Cue> cues, int duration) {
    private static final String ASSET = "/assets/create_media_systems/piano/pxs1100we/un_owen_sabi_piano_only.mid";

    record Cue(int tick, List<KeyboardMidi> events) {}

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
        var cues = new TreeMap<Integer, List<KeyboardMidi>>();
        long last = 0, elapsed = 0, unitsPerTick = 50_000L * sequence.getResolution();
        int tempo = 500_000;
        for (var event : midi) {
            elapsed += (event.getTick() - last) * tempo;
            last = event.getTick();
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                byte[] data = meta.getData();
                if (data.length != 3) throw new InvalidMidiDataException("Invalid tempo event");
                tempo = (data[0] & 255) << 16 | (data[1] & 255) << 8 | data[2] & 255;
            } else if (event.getMessage() instanceof ShortMessage message) {
                var note = new KeyboardMidi(message.getStatus(), message.getData1(), message.getData2());
                if (note.valid() && !note.metronome()) {
                    int tick = gameTicks(elapsed, unitsPerTick);
                    cues.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(note);
                }
            }
        }
        var score = cues.entrySet().stream().map(cue -> new Cue(cue.getKey(), List.copyOf(cue.getValue()))).toList();
        return new PonderScore(score, gameTicks(elapsed, unitsPerTick));
    }

    private static int gameTicks(long elapsed, long unitsPerTick) {
        return Math.toIntExact((elapsed + unitsPerTick / 2) / unitsPerTick);
    }
}
