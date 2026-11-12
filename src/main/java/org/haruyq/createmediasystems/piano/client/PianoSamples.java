package org.haruyq.createmediasystems.piano.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.server.packs.resources.ResourceManager;
import org.haruyq.createmediasystems.CreateMediaSystems;

import static org.haruyq.createmediasystems.piano.PianoEvent.FIRST_NOTE;
import static org.haruyq.createmediasystems.piano.PianoEvent.LAST_NOTE;

record PianoSamples(Map<String, short[]> samples) {
    static final int RATE = 32_000;
    private static final int LAYERS = 5;

    static PianoSamples load(ResourceManager resources) throws IOException {
        Map<String, short[]> samples = new HashMap<>();
        var bank = resources.getResourceOrThrow(CreateMediaSystems.id("piano/grand-piano.zip"));
        try (var zip = new ZipInputStream(bank.open())) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory() || !entry.getName().endsWith(".ogg")) continue;
                try (var ogg = new JOrbisAudioStream(new ByteArrayInputStream(zip.readAllBytes()))) {
                    if (ogg.getFormat().getChannels() != 1 || ogg.getFormat().getSampleRate() != RATE) {
                        throw new IOException("Unexpected format for piano sample " + entry.getName());
                    }
                    var pcm = ogg.readAll().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    short[] data = new short[pcm.remaining()];
                    pcm.get(data);
                    samples.put(entry.getName(), trimLead(data));
                }
            }
        }
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note += 3) {
            for (int layer = 0; layer < LAYERS; layer++) {
                if (!samples.containsKey(note + "_" + layer + ".ogg")) {
                    throw new IOException("Missing grand piano recording for MIDI " + note + ", layer " + layer);
                }
            }
        }
        return new PianoSamples(Map.copyOf(samples));
    }

    static int root(int note) {
        return FIRST_NOTE + 3 * Math.round((note - FIRST_NOTE) / 3f);
    }

    short[] key(int root, int layer) { return samples.get(root + "_" + layer + ".ogg"); }
    short[] release(int note) { return samples.get("release_" + note + ".ogg"); }
    short[] resonance(int root) { return samples.get("resonance_" + root + ".ogg"); }
    short[] pedal(boolean down, int variant) { return samples.get("pedal_" + (down ? "d" : "u") + variant + ".ogg"); }

    private static short[] trimLead(short[] data) {
        int peak = 0;
        for (short sample : data) peak = Math.max(peak, Math.abs((int) sample));
        int threshold = Math.max(10, peak / 140);
        int from = 0;
        while (from < data.length && Math.abs((int) data[from]) < threshold) from++;
        from = Math.max(0, from - RATE / 500);
        return from == 0 || from == data.length ? data : Arrays.copyOfRange(data, from, data.length);
    }
}
