package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;

public final class KeyboardLayout {
    public static final int FIRST = 21, LAST = 108;
    public static final Vec3 PEDAL = new Vec3(.94, 0, .24);
    static final AABB PEDAL_BOX = new AABB(.893, 0, .201, .987, .038, .289);
    public static final KeyboardLayout PX = new KeyboardLayout();
    public final AABB body;
    public final AABB rack;
    public final List<AABB> stand;
    public final List<Key> keys;
    public final List<Vec3> speakers;
    public final Vec3 power, knob;
    public final Vec3 dcIn, usbMidi, lineLeft, lineRight;
    public final float tilt, tabletop, powerTravel;
    public final float fullScale;
    public final Map<String, Hit> panel;

    public record Key(int note, boolean black, String model, Vec3 pivot, List<Hit> hits) {}

    public record Hit(double minX, double maxX, double minZ, double maxZ, double y) {
        public boolean contains(Vec3 at) {
            return at.x >= minX && at.x <= maxX && at.z >= minZ && at.z <= maxZ && Math.abs(at.y - y) < .035;
        }
    }

    private KeyboardLayout() {
        var layout = read("piano/pxs1100we/layout.json");
        body = box(layout.getAsJsonObject("body_aabb"));
        rack = modelBoxes("music_stand").stream().reduce(AABB::minmax).orElseThrow();
        tabletop = layout.get("tabletop_dy_px").getAsFloat() / 16;
        tilt = layout.getAsJsonObject("key").get("press_deg").getAsFloat();
        stand = modelBoxes("stand");
        var keyboard = new ArrayList<Key>();
        layout.getAsJsonArray("keys").forEach(element -> {
            var k = element.getAsJsonObject();
            var hits = new ArrayList<Hit>();
            k.getAsJsonArray("hit").forEach(h -> hits.add(hit(h.getAsJsonObject())));
            keyboard.add(new Key(k.get("midi").getAsInt(), k.get("black").getAsBoolean(),
                    k.get("model").getAsString(), vec(k.getAsJsonArray("pivot")), List.copyOf(hits)));
        });
        keys = List.copyOf(keyboard);
        // Match the pitch of all 52 white keys to the Koland keybed, excluding the case.
        double span = keys.getFirst().hits().getFirst().maxX() - keys.getLast().hits().getFirst().minX();
        fullScale = (float) ((Layout.KEYS_RIGHT - Layout.KEYS_LEFT) / (16 * span));
        var audio = new ArrayList<Vec3>();
        layout.getAsJsonArray("speakers").forEach(point -> audio.add(vec(point.getAsJsonArray())));
        speakers = List.copyOf(audio);
        var jacks = layout.getAsJsonObject("jacks");
        dcIn = vec(jacks.getAsJsonArray("dc_in"));
        usbMidi = vec(jacks.getAsJsonArray("usb_b"));
        lineLeft = vec(jacks.getAsJsonArray("line_out_l"));
        lineRight = vec(jacks.getAsJsonArray("line_out_r"));
        var controls = layout.getAsJsonObject("controls");
        var p = controls.getAsJsonObject("power_button");
        var n = controls.getAsJsonObject("volume_knob");
        power = vec(p.getAsJsonArray("pivot"));
        knob = vec(n.getAsJsonArray("pivot"));
        powerTravel = p.get("press_dy").getAsFloat() / 16;
        var buttons = new LinkedHashMap<String, Hit>();
        buttons.put("power_button", hit(p.getAsJsonObject("hit")));
        buttons.put("volume_knob", hit(n.getAsJsonObject("hit")));
        layout.getAsJsonObject("touch_buttons").entrySet().forEach(entry -> {
            if (!entry.getKey().equals("power")) buttons.put(entry.getKey(), hit(entry.getValue().getAsJsonObject().getAsJsonObject("hit")));
        });
        panel = java.util.Collections.unmodifiableMap(buttons);
        if (keys.size() != 88 || speakers.size() != 2 || stand.isEmpty()) throw new IllegalStateException("Incomplete PX-S1100 model");
    }

    private static JsonObject read(String path) {
        String asset = "/assets/create_media_systems/" + path;
        try (var input = KeyboardLayout.class.getResourceAsStream(asset)) {
            if (input == null) throw new IOException("Missing " + asset);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(asset, e);
        }
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble() / 16, a.get(1).getAsDouble() / 16, a.get(2).getAsDouble() / 16);
    }

    private static AABB box(JsonObject j) {
        return new AABB(vec(j.getAsJsonArray("from")), vec(j.getAsJsonArray("to")));
    }

    private static List<AABB> modelBoxes(String name) {
        var boxes = new ArrayList<AABB>();
        read("models/block/piano/pxs1100we/" + name + ".json").getAsJsonArray("elements").forEach(element -> {
            var b = box(element.getAsJsonObject());
            if (b.getXsize() > .0001 && b.getYsize() > .0001 && b.getZsize() > .0001) boxes.add(b);
        });
        return List.copyOf(boxes);
    }

    private static Hit hit(JsonObject j) {
        var x = j.getAsJsonArray("x");
        var z = j.getAsJsonArray("z");
        return new Hit(x.get(0).getAsDouble() / 16, x.get(1).getAsDouble() / 16, z.get(0).getAsDouble() / 16,
                z.get(1).getAsDouble() / 16, j.get("y").getAsDouble() / 16);
    }
}
