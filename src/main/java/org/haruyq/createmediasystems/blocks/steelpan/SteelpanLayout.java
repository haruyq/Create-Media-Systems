package org.haruyq.createmediasystems.blocks.steelpan;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The same manufacturer's pitch layout is used by hit selection, rendering and authoring. */
public final class SteelpanLayout {
    public static final int LOWEST = 60, HIGHEST = 88;
    public record Note(int midi, String label, int ring, float[] center, float[] axes, float angle) {}
    public record Data(int version, String units, float diameter, float pan_depth, float rim_height,
                       float tilt, List<Note> notes, float mallet_length, float mallet_radius) {}
    public static final Data DATA = load();

    private SteelpanLayout() {}

    private static Data load() {
        String resource = "/assets/create_media_systems/models/block/steelpan/layout.json";
        try (var input = SteelpanLayout.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing steelpan layout");
            var data = new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Data.class);
            if (data.version() != 1 || !"metres".equals(data.units()) || data.notes().size() != 29
                    || data.notes().stream().map(Note::midi).distinct().count() != 29
                    || data.notes().stream().anyMatch(n -> !contains(n.midi()))) {
                throw new IllegalStateException("Incompatible steelpan layout");
            }
            return data;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load " + resource, failure);
        }
    }

    public static boolean contains(int midi) { return midi >= LOWEST && midi <= HIGHEST; }
    public static Note note(int midi) { return DATA.notes().get(midi - LOWEST); }

    /** Select a field only inside its actual outline, never a neighbouring gap or the skirt. */
    public static int at(float x, float z) {
        for (var note : DATA.notes()) {
            float dx = x - note.center()[0], dz = z - note.center()[2];
            float u, v;
            if (note.ring() == 0) {
                u = (float) (dx * Math.sin(note.angle()) + dz * Math.cos(note.angle())) / note.axes()[1];
                v = (float) (dx * Math.cos(note.angle()) - dz * Math.sin(note.angle()))
                        / (note.axes()[0] * (1 + .16f * u));
            } else {
                u = dx / note.axes()[0]; v = dz / note.axes()[1];
            }
            if (u * u + v * v <= 1) return note.midi();
        }
        return -1;
    }

    public static float bowl(float x, float z) {
        float radius = DATA.diameter() / 2 - .004f;
        return -.2107f * (1 - (x * x + z * z) / (radius * radius));
    }
}
