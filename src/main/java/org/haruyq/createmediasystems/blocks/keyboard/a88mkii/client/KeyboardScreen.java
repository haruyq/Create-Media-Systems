package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout.Control;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.MidiKeyboardBlock;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Panel;
import org.haruyq.createmediasystems.client.KeysScreen;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Playing a keyboard: its 88 keys across the bottom of the screen and its controls in a strip above them, over the
 * world, worked with the mouse (and the computer's own keys) as the real ones are with the hands, beside whatever a
 * MIDI keyboard plugged in plays. The controls are the pod's, laid out wide instead of deep; each zone's button names
 * its sound, which the real one has no display for. Open for as long as this player sits at the keyboard; closing it
 * gets them up.
 */
final class KeyboardScreen extends KeysScreen {
    private static final String KEY = "create_media_systems.keyboard.";
    /** The computer's keys that play notes, two rows of a piano each, from C: the row from Z, and the row from Q an octave up. */
    private static final int[] LOWER_ROW = {GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_C,
            GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_M,
            GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_SLASH};
    private static final int[] UPPER_ROW = {GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_E,
            GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_U,
            GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_P};
    /** The note the Z key plays: C3. */
    private static final int ROW_BASE = 48;
    private static final String[] BUTTONS = {"<", ">", "SNAP", "", "", "", "", "", "", "", "", "PREV", "NEXT", "PROG", "CC",
            "NOTE", "SHIFT", "FUNC", "", "", "", "", "", "", "", "", "LOWER", "UPPER 1", "UPPER 2", "SPLIT", "TRANS", "S1", "S2",
            "OCT-", "OCT+", ""};
    private static final String[] KNOBS = {"CUTOFF", "RESO", "ATTACK", "RELEASE", "REVERB", "CHORUS", "PAN", "VOLUME"};
    private static final String[] STEPS = {"1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T"};
    private static final String[] ORDERS = {"UP", "DOWN", "U&D", "RND"};
    private static final String[] CURVES = {"SUPER LIGHT", "LIGHT", "MEDIUM", "MEDIUM HEAVY", "HEAVY", "SUPER HEAVY"};
    /** Where each control is on the strip: its left, top, right and bottom, as shares of the strip's width and height. */
    private static final float[][] PLACES = new float[Control.values().length][];
    /** The strip's rows of buttons, under the top line that says how it is set. */
    private static final float[] ROWS = {0.13f, 0.34f, 0.55f, 0.79f};
    private static final float ROW = 0.17f;

    static {
        put(Control.LEVER, 0.012f, ROWS[0], 0.072f, ROWS[3] + ROW);
        Control[] zones = {Control.UPPER_2, Control.UPPER_1, Control.LOWER};
        for (int row = 0; row < 3; row++) {
            put(zones[row], 0.088f, ROWS[row], 0.385f, ROWS[row] + ROW);
        }
        Control[] below = {Control.SPLIT, Control.TRANSPOSE, Control.OCTAVE_DOWN, Control.OCTAVE_UP, Control.S1, Control.S2};
        for (int i = 0; i < below.length; i++) {
            put(below[i], 0.088f + i * 0.0505f, ROWS[3], 0.088f + i * 0.0505f + 0.0445f, ROWS[3] + ROW);
        }
        for (int knob = 0; knob < Panel.KNOBS; knob++) {
            float x = 0.422f + knob % 4 * 0.062f, y = knob < 4 ? 0.3f : 0.7f;
            put(Control.KNOBS[knob], x - 0.022f, y - 0.085f, x + 0.022f, y + 0.085f);
        }
        for (int pad = 0; pad < Panel.PADS; pad++) {
            float x = 0.64f + pad % 4 * 0.056f, y = pad < 4 ? ROWS[0] : 0.45f;
            put(Control.PADS[pad], x, y, x + 0.05f, y + 0.28f);
        }
        put(Control.PROGRAM, 0.64f, ROWS[3], 0.708f, ROWS[3] + ROW);
        put(Control.CC, 0.714f, ROWS[3], 0.782f, ROWS[3] + ROW);
        put(Control.NOTE, 0.788f, ROWS[3], 0.858f, ROWS[3] + ROW);
        Control[][] right = {{Control.BANK_DOWN, Control.BANK_UP}, {Control.SHIFT, Control.FUNC}, {Control.SNAPSHOT},
                {Control.PREV, Control.NEXT}};
        for (int row = 0; row < right.length; row++) {
            put(right[row][0], 0.875f, ROWS[row], right[row].length == 1 ? 0.988f : 0.928f, ROWS[row] + ROW);
            if (right[row].length > 1) {
                put(right[row][1], 0.935f, ROWS[row], 0.988f, ROWS[row] + ROW);
            }
        }
    }

    private static void put(Control control, float left, float top, float right, float bottom) {
        PLACES[control.ordinal()] = new float[]{left, top, right, bottom};
    }

    final KeyboardClient.Playing playing;
    private final Controller controller;
    /** What the mouse holds: the note of a key (-1 for none), or a control. */
    private int mouseNote = -1;
    @Nullable
    private Control held;
    private int knobFrom;
    private double knobFromY;
    /** The notes the computer's keys held play, by key. */
    private final Map<Integer, Integer> typed = new HashMap<>();

    KeyboardScreen(KeyboardClient.Playing playing) {
        super(Component.translatable("block.create_media_systems.midi_keyboard"), 13.52f);
        this.playing = playing;
        controller = playing.controller;
    }

    @Override
    public void removed() {
        KeyboardClient.closed(playing);
    }

    @Override
    protected void draw(GuiGraphics g, double x, double y) {
        MidiKeyboardBlock.Entity keyboard = KeyboardAudio.near().get(playing.pos);
        PanelLook look = new PanelLook(controller.panel(), keyboard == null ? 0 : keyboard.padsDown, controller);
        drawHeader(g, look.panel());
        drawControls(g, look, keyboard);
        drawKeys(g, look.panel(), keyboard);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Control hovered = controlAt(mouseX * pixel, mouseY * pixel);
        if (hovered != null && held == null && mouseNote < 0) {
            g.renderTooltip(font, font.split(Component.translatable(KEY + "control." + hovered.name().toLowerCase(Locale.ROOT)), 210),
                    mouseX, mouseY);
        }
    }

    /** Where {@code control} is: its left, top, right and bottom. */
    private int[] place(Control control) {
        float[] place = PLACES[control.ordinal()];
        return new int[]{keysX + Math.round(place[0] * stripWidth), stripY + Math.round(place[1] * stripHeight),
                keysX + Math.round(place[2] * stripWidth), stripY + Math.round(place[3] * stripHeight)};
    }

    /** A filled circle of {@code radius} around ({@code x}, {@code y}), its rim as soft as a box's corners. */
    private static void disc(GuiGraphics g, int x, int y, float radius, int color) {
        for (int row = -(int) Math.ceil(radius); row < Math.ceil(radius); row++) {
            double up = row + 0.5;
            if (Math.abs(up) < radius) {
                double half = Math.sqrt(radius * radius - up * up);
                span(g, x - (int) half, x + (int) half, y + row, (float) (half - (int) half), color);
            }
        }
    }

    /** {@code color} with {@code share} (0..1) of white mixed in. */
    private static int lighter(int color, float share) {
        int r = color >> 16 & 255, green = color >> 8 & 255, b = color & 255;
        return color & 0xFF000000 | r + Math.round((255 - r) * share) << 16 | green + Math.round((255 - green) * share) << 8
                | b + Math.round((255 - b) * share);
    }

    /** The top line: what is set off its usual on the left (nothing, on a keyboard as it comes), the MIDI inputs on the right. */
    private void drawHeader(GuiGraphics g, Panel panel) {
        List<String> inputs = MidiInput.names();
        Component midi = inputs == null ? Component.translatable(KEY + "midi.looking")
                : inputs.isEmpty() ? Component.translatable(KEY + "midi.none")
                : Component.translatable(KEY + "midi", String.join(", ", inputs));
        drawHeader(g, "Koland A-88MKII", status(panel), LAMP, midi.getString(), inputs == null || inputs.isEmpty() ? FAINT
                : 0xFF000000 | PanelLook.GREEN);
    }

    private String status(Panel panel) {
        List<String> set = new ArrayList<>();
        if (controller.snapshotMode() != 0) {
            set.add((controller.snapshotMode() == Controller.WRITE ? "STORE" : "RECALL") + ": PAD, NEXT");
        }
        if (panel.octave != 0) {
            set.add("OCT " + signed(panel.octave));
        }
        if (panel.transpose != 0) {
            set.add("TRANS " + signed(panel.transpose));
        }
        if (panel.curve != 2) {
            set.add(CURVES[panel.curve]);
        }
        if (panel.split) {
            set.add("SPLIT " + noteName(panel.splitPoint));
        } else if (controller.lit(Control.SPLIT)) {
            set.add("SPLIT: KEY");
        }
        if (panel.arpeggio) {
            set.add("ARP " + ORDERS[panel.order] + " " + panel.range + " OCT " + STEPS[panel.step] + " " + panel.tempo + " BPM"
                    + (panel.gate == 0 ? " GATE 50%" : "") + (panel.hold ? " HOLD" : ""));
        }
        return String.join(" · ", set);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private void drawControls(GuiGraphics g, PanelLook look, @Nullable MidiKeyboardBlock.Entity keyboard) {
        Panel panel = look.panel();
        int corner = Math.max(2, keyWidth / 5);
        for (Control control : Control.values()) {
            int[] at = place(control);
            int x0 = at[0], y0 = at[1], x1 = at[2], y1 = at[3];
            float middleX = (x0 + x1) / 2f, middleY = (y0 + y1) / 2f + textScale / 2;
            switch (control.kind) {
                case BUTTON -> {
                    boolean lit = look.lit(control);
                    if (control == Control.LOWER || control == Control.UPPER_1 || control == Control.UPPER_2) {
                        drawZone(g, panel, control.index(), x0, y0, x1, y1, corner);
                        continue;
                    }
                    String label = BUTTONS[control.ordinal()];
                    if (lit && (control == Control.PROGRAM || control == Control.CC || control == Control.NOTE)) {
                        label += " " + (panel.bank() + 1);
                    }
                    int[] face = lit ? LIT : control == held ? HELD : BUTTON;
                    box(g, x0, y0, x1, y1, corner, true, face[0], face[1]);
                    write(g, label, middleX, middleY, textScale, lit ? INK : TEXT, MIDDLE);
                }
                case PAD -> {
                    int pad = control.index();
                    boolean bright = look.padGlows(pad) && look.padLit(pad);
                    int color = 0xFF000000 | (!look.padGlows(pad) ? 0x2B2B30 : PanelLook.dim(look.padColor(pad), bright ? 1 : 0.4f));
                    box(g, x0, y0, x1, y1, Math.max(3, keyWidth / 4), true, lighter(color, bright ? 0.22f : 0.1f), color);
                    write(g, padLabel(panel, pad), middleX, middleY, textScale, bright ? INK : TEXT, MIDDLE);
                }
                case KNOB -> {
                    int knob = control.index();
                    float value = panel.knob[knob] / 127f, radius = (x1 - x0) / 2f, ring = Math.max(2, keyWidth / 6f);
                    int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                    disc(g, cx, cy, radius, 0xFF000000 | PanelLook.dim(look.knobColor(knob), 0.35f + 0.65f * value));
                    disc(g, cx, cy, radius - ring, 0xFF2A2A2F);
                    disc(g, cx, cy - 1, radius - ring - 1, 0xFF34343A);
                    double angle = Math.toRadians((value - 0.5) * 270), reach = radius - ring - Math.max(2, keyWidth / 6f);
                    disc(g, cx + (int) Math.round(Math.sin(angle) * reach), cy - (int) Math.round(Math.cos(angle) * reach),
                            Math.max(1.5f, keyWidth / 10f), 0xFFFFFFFF);
                    write(g, control == held ? String.valueOf(panel.knob[knob]) : KNOBS[knob], cx, cy + radius + lineHeight() / 2f + 1,
                            textScale, control == held ? TEXT : FAINT, MIDDLE);
                }
                case LEVER -> {
                    box(g, x0, y0, x1, y1, keyWidth / 3, true, 0xFF0B0B0C, 0xFF111113);
                    int bend = keyboard == null ? 8192 : keyboard.bend, modulation = keyboard == null ? 0 : keyboard.modulation;
                    int grip = Math.max(4, (x1 - x0) / 4);
                    int cx = (x0 + x1) / 2 + Math.round((bend - 8192) / 8192f * ((x1 - x0) / 2f - grip - 2));
                    int cy = (y0 + y1) / 2 - Math.round(modulation / 127f * ((y1 - y0) / 2f - grip - 2));
                    box(g, cx - grip, cy - grip, cx + grip, cy + grip, grip / 2, true, HELD[0], BUTTON[1]);
                }
            }
        }
    }

    /** A zone's button, which names its sound: lit along its left edge while it sounds, framed while the pads and knobs work it. */
    private void drawZone(GuiGraphics g, Panel panel, int zone, int x0, int y0, int x1, int y1, int corner) {
        boolean sounds = panel.sounds(zone);
        if (panel.target == zone) {
            box(g, x0 - 2, y0 - 2, x1 + 2, y1 + 2, corner + 2, true, 0xFFFFFFFF, 0xFFD8D8DE);
        }
        int[] face = sounds ? BUTTON : UNLIT;
        box(g, x0, y0, x1, y1, corner, true, face[0], face[1]);
        int lamp = Math.max(2, keyWidth / 7), inset = Math.max(3, keyWidth / 6);
        box(g, x0 + inset, y0 + inset, x0 + inset + lamp, y1 - inset, lamp / 2, true, sounds ? LIT[0] : 0xFF45454C,
                sounds ? LIT[1] : 0xFF3A3A40);
        String name = KeyboardAudio.programName(panel.program[zone], panel.channel[zone] == Controller.DRUM_CHANNEL);
        String line = BUTTONS[Control.ZONES[zone].ordinal()] + String.format(Locale.ROOT, "   %03d ", panel.program[zone] + 1)
                + (name == null ? "" : name);
        int from = x0 + inset + lamp + keyWidth / 2;
        // As large as fits, in the same half steps as the rest of the lettering.
        float fits = (x1 - from - inset) / (float) Math.max(1, font.width(line));
        write(g, line, from, (y0 + y1) / 2f + textScale / 2, Math.max(1, Math.min(2 * textScale, (float) Math.floor(fits * 2) / 2)),
                sounds ? TEXT : FAINT, LEFT);
    }

    /** What pad {@code pad} is for as its panel is set: a program, a controller, a drum, the arpeggiator's range or order, a snapshot. */
    private String padLabel(Panel panel, int pad) {
        if (controller.snapshotMode() != 0) {
            return "S" + (pad + 1);
        }
        if (panel.arpeggio) {
            return pad < 4 ? (pad + 1) + " OCT" : ORDERS[pad - 4];
        }
        return switch (panel.padMode) {
            case Panel.PROGRAMS -> String.valueOf(panel.bank() * Panel.PADS + pad + 1);
            case Panel.CONTROLS -> "CC" + Panel.padController(panel.bank(), pad);
            default -> noteName(Panel.drum(panel.bank(), pad));
        };
    }

    private static String noteName(int note) {
        return new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"}[note % 12] + (note / 12 - 1);
    }

    /** The keys, what FUNC works named over them while it is lit, and the split point marked. */
    private void drawKeys(GuiGraphics g, Panel panel, @Nullable MidiKeyboardBlock.Entity keyboard) {
        long low = keyboard == null ? 0 : keyboard.keysDown.get(0), high = keyboard == null ? 0 : keyboard.keysDown.get(1);
        if (controller.lit(Control.FUNC)) {
            legend(g, "PANIC", Controller.PANIC_KEY, 1, 0);
            legend(g, "MIDI CH 1-16", Controller.FIRST_CHANNEL_KEY, 16, 1);
            legend(g, "STEP " + STEPS[panel.step], Controller.FIRST_STEP_KEY, STEPS.length, 0);
            legend(g, "GATE " + (panel.gate == 0 ? 50 : 100) + "%", Controller.FIRST_GATE_KEY, 2, 1);
        }
        drawKeys(g, note -> ((note < 64 ? low : high) >> note & 1) != 0);
        if (panel.split || controller.lit(Control.SPLIT)) {
            int gap = Math.max(1, Math.round(keyWidth / 11f));
            int x = keysX + (Layout.across(Math.clamp(panel.splitPoint, Layout.LOWEST, Layout.HIGHEST)) + 1) * keyWidth - gap;
            g.fill(x, keysY - felt, x + gap, keysY + keysHeight, 0xFF000000 | PanelLook.RED);
        }
    }

    /**
     * While FUNC is lit: {@code text} over the {@code count} keys from the {@code first} (counted from the lowest),
     * underlined; on the upper ({@code row} 0) or lower line, so neighbours don't run into each other.
     */
    private void legend(GuiGraphics g, String text, int first, int count, int row) {
        int x0 = keysX + Layout.across(Layout.LOWEST + first) * keyWidth - (Layout.black(Layout.LOWEST + first) ? keyWidth / 2 : 0);
        int last = Layout.LOWEST + first + count - 1;
        int x1 = keysX + Layout.across(last) * keyWidth + (Layout.black(last) ? keyWidth / 2 : keyWidth);
        g.fill(x0 + 2, keysY - felt - 4, x1 - 2, keysY - felt - 2, LAMP);
        write(g, text, Math.max((x0 + x1) / 2f, keysX + font.width(text) * textScale / 2),
                keysY - deck + lineHeight() * (row + 0.5f), textScale, LAMP, MIDDLE);
    }

    /** The control at ({@code x}, {@code y}) of the window; null for none, as while it is hidden. */
    @Nullable
    private Control controlAt(double x, double y) {
        for (Control control : Control.values()) {
            int[] at = place(control);
            if (!hidden && x >= at[0] && x < at[2] && y >= at[1] && y < at[3]) {
                return control;
            }
        }
        return null;
    }

    /** How hard a click at {@code y} strikes a key: harder towards its front edge. */
    private int velocityAt(double y) {
        return Mth.clamp((int) Math.round(45 + 82 * (y - keysY) / keysHeight), 1, 127);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        double x = mouseX * pixel, y = mouseY * pixel;
        int note = keyAt(x, y);
        Control control = controlAt(x, y);
        if (note >= 0) {
            mouseNote = note;
            controller.key(note, velocityAt(y));
        } else if (control != null) {
            held = control;
            switch (control.kind) {
                case BUTTON -> controller.press(control);
                case PAD -> controller.pad(control.index(), 110);
                case KNOB -> {
                    knobFrom = controller.panel().knob[control.index()];
                    knobFromY = mouseY;
                }
                case LEVER -> lever(x, y);
            }
        } else if (y >= stripY && y < stripY + keyWidth * 3 / 2 && x > keysX + stripWidth / 2) {
            // The MIDI inputs, at the right of the top line: looked for again.
            KeyboardClient.listen(playing);
        }
        return true;
    }

    private void lever(double x, double y) {
        int[] at = place(Control.LEVER);
        double across = (x - (at[0] + at[2]) / 2.0) / Math.max((at[2] - at[0]) * 0.4, keyWidth * 3);
        double away = ((at[1] + at[3]) / 2.0 - y) / ((at[3] - at[1]) * 0.4);
        controller.bend(8192 + (int) Math.round(Mth.clamp(across, -1, 1) * 8191));
        controller.control(1, (int) Math.round(Mth.clamp(away, 0, 1) * 127));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double x = mouseX * pixel, y = mouseY * pixel;
        if (mouseNote >= 0) {
            int note = keyAt(x, y);
            if (note != mouseNote && note >= 0) {
                controller.key(mouseNote, 0);
                mouseNote = note;
                controller.key(note, velocityAt(y));
            }
        } else if (held != null && held.kind == Layout.Kind.KNOB) {
            controller.knob(held.index(), Mth.clamp(knobFrom + (int) Math.round((knobFromY - mouseY) * 1.5), 0, 127));
        } else if (held == Control.LEVER) {
            lever(x, y);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mouseNote >= 0) {
            controller.key(mouseNote, 0);
            mouseNote = -1;
        }
        if (held != null) {
            switch (held.kind) {
                case BUTTON -> controller.release(held);
                case PAD -> controller.pad(held.index(), 0);
                case LEVER -> {
                    controller.bend(8192);
                    controller.control(1, 0);
                }
                default -> {
                }
            }
            held = null;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Control control = controlAt(mouseX * pixel, mouseY * pixel);
        if (control != null && control.kind == Layout.Kind.KNOB) {
            controller.knob(control.index(), Mth.clamp(controller.panel().knob[control.index()] + (int) Math.signum(scrollY) * 4, 0, 127));
        }
        return true;
    }

    /** The note the computer's key {@code key} plays; -1 for a key that plays none. */
    private static int typedNote(int key) {
        for (int i = 0; i < LOWER_ROW.length; i++) {
            if (LOWER_ROW[i] == key) {
                return ROW_BASE + i;
            }
            if (UPPER_ROW[i] == key) {
                return ROW_BASE + 12 + i;
            }
        }
        return -1;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        int note = typedNote(key);
        if (note >= 0) {
            if (typed.putIfAbsent(key, note) == null) {
                controller.key(note, 100);
            }
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> {
                if (typed.putIfAbsent(key, 0) == null) {
                    controller.control(64, 127);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> controller.press(Control.OCTAVE_DOWN);
            case GLFW.GLFW_KEY_RIGHT -> controller.press(Control.OCTAVE_UP);
            default -> {
                return super.keyPressed(key, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        Integer note = typed.remove(key);
        if (note != null && key == GLFW.GLFW_KEY_SPACE) {
            controller.control(64, 0);
        } else if (note != null) {
            controller.key(note, 0);
        }
        return true;
    }
}
