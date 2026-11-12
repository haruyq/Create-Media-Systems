package org.haruyq.createmediasystems.keyboard.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.haruyq.createmediasystems.keyboard.Controller;
import org.haruyq.createmediasystems.keyboard.Layout;
import org.haruyq.createmediasystems.keyboard.Layout.Control;
import org.haruyq.createmediasystems.keyboard.MidiKeyboardBlock;
import org.haruyq.createmediasystems.keyboard.Panel;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Playing a keyboard: its 88 keys across the bottom of the screen and its controls in a strip above them, over the
 * world, worked with the mouse (and the computer's own keys) as the real ones are with the hands, beside whatever a
 * MIDI keyboard plugged in plays. The controls are the pod's, laid out wide instead of deep; each zone's button names
 * its sound, which the real one has no display for. Open for as long as this player sits at the keyboard; closing it
 * gets them up.
 */
final class KeyboardScreen extends Screen {
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
    private static final int BODY = 0xF41A1A1D, EDGE = 0xFF050506, BUTTON = 0xFF323238, HELD = 0xFF4A4A52, LAMP = 0xFFFFE7B8,
            TEXT = 0xFFE6E6EA, FAINT = 0xFF85858D, PRESSED = 0xFF8FC2FF, PRESSED_BLACK = 0xFF2D6FD6;
    private static final int LEFT = -1, MIDDLE = 0, RIGHT = 1;

    /** Where each control is on the strip: its left, top, right and bottom, as shares of the strip's width and height. */
    private static final float[][] PLACES = new float[Control.values().length][];
    /** The strip's rows of buttons, and the top line that says how it is set. */
    private static final float[] ROWS = {0.13f, 0.34f, 0.55f, 0.79f};
    private static final float ROW = 0.17f, HEADER = 0.11f;

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
    /** The keys: their left edge and top, a white key's width and length. The strip of controls is as wide, above them. */
    private int keysX, keysY, keyWidth, keysHeight;
    private int stripY, stripWidth, stripHeight;
    /** The gap between the strip and the keys, where the functions FUNC works are named. */
    private int deck;
    private float textScale;
    /** What the mouse holds: the note of a key (-1 for none), or a control. */
    private int mouseNote = -1;
    @Nullable
    private Control held;
    private int knobFrom;
    private double knobFromY;
    /** The notes the computer's keys held play, by key. */
    private final Map<Integer, Integer> typed = new HashMap<>();

    KeyboardScreen(KeyboardClient.Playing playing) {
        super(Component.translatable("block.create_media_systems.midi_keyboard"));
        this.playing = playing;
        controller = playing.controller;
    }

    @Override
    protected void init() {
        // Every key a whole number of pixels wide, as many as fit across the screen: the rest follows from that.
        keyWidth = Mth.clamp((width - 16) / Layout.WHITE_KEYS, 4, 12);
        keysHeight = keyWidth * 8;
        keysX = (width - keyWidth * Layout.WHITE_KEYS) / 2;
        keysY = height - 8 - keysHeight;
        textScale = Math.max(0.5f, keyWidth / 14f);
        // Room for the two lines FUNC's functions are named on, and the line under them.
        deck = 2 * lineHeight() + 5;
        stripWidth = keyWidth * Layout.WHITE_KEYS;
        stripHeight = Math.round(stripWidth * 0.26f);
        stripY = keysY - deck - stripHeight;
    }

    /** How high a line of the small lettering is. */
    private int lineHeight() {
        return Math.round(8 * textScale) + 1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        KeyboardClient.closed(playing);
    }

    /** The world stays in sight behind it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MidiKeyboardBlock.Entity keyboard = KeyboardAudio.near().get(playing.pos);
        PanelLook look = new PanelLook(controller.panel(), keyboard == null ? 0 : keyboard.padsDown, controller);
        g.fill(keysX - 5, stripY - 5, keysX + stripWidth + 5, keysY + keysHeight + 5, EDGE);
        g.fill(keysX - 4, stripY - 4, keysX + stripWidth + 4, keysY + keysHeight + 4, BODY);
        drawHeader(g, look.panel());
        drawControls(g, look, keyboard);
        drawKeys(g, look.panel(), keyboard);
        Control hovered = controlAt(mouseX, mouseY);
        if (hovered != null && held == null && mouseNote < 0) {
            g.renderTooltip(font, font.split(Component.translatable(KEY + "control." + hovered.name().toLowerCase(Locale.ROOT)), 210),
                    mouseX, mouseY);
        }
    }

    /** Where {@code control} is on the screen: its left, top, right and bottom. */
    private int[] place(Control control) {
        float[] place = PLACES[control.ordinal()];
        return new int[]{keysX + Math.round(place[0] * stripWidth), stripY + Math.round(place[1] * stripHeight),
                keysX + Math.round(place[2] * stripWidth), stripY + Math.round(place[3] * stripHeight)};
    }

    /** {@code text} at {@code scale}, its middle {@code y} high and its left end, middle or right end at {@code x}. */
    private void write(GuiGraphics g, String text, float x, float y, float scale, int color, int align) {
        int wide = font.width(text);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, align == LEFT ? 0 : align == MIDDLE ? -wide / 2 : -wide, -4, color, false);
        g.pose().popPose();
    }

    /** The top line: what is set off its usual on the left (nothing, on a keyboard as it comes), the MIDI inputs on the right. */
    private void drawHeader(GuiGraphics g, Panel panel) {
        float y = stripY + stripHeight * HEADER / 2;
        write(g, "Koland A-88MKII", keysX + 4, y, textScale, FAINT, LEFT);
        write(g, status(panel), keysX + 12 + font.width("Koland A-88MKII") * textScale, y, textScale, LAMP, LEFT);
        List<String> inputs = MidiInput.names();
        Component midi = inputs == null ? Component.translatable(KEY + "midi.looking")
                : inputs.isEmpty() ? Component.translatable(KEY + "midi.none")
                : Component.translatable(KEY + "midi", String.join(", ", inputs));
        write(g, midi.getString(), keysX + stripWidth - 4, y, textScale, inputs == null || inputs.isEmpty() ? FAINT
                : 0xFF000000 | PanelLook.GREEN, RIGHT);
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
        for (Control control : Control.values()) {
            int[] at = place(control);
            int x0 = at[0], y0 = at[1], x1 = at[2], y1 = at[3];
            float middleX = (x0 + x1) / 2f, middleY = (y0 + y1) / 2f + 0.5f;
            switch (control.kind) {
                case BUTTON -> {
                    boolean lit = look.lit(control);
                    if (control == Control.LOWER || control == Control.UPPER_1 || control == Control.UPPER_2) {
                        drawZone(g, panel, control.index(), x0, y0, x1, y1);
                        continue;
                    }
                    String label = BUTTONS[control.ordinal()];
                    if (lit && (control == Control.PROGRAM || control == Control.CC || control == Control.NOTE)) {
                        label += " " + (panel.bank() + 1);
                    }
                    g.fill(x0, y0, x1, y1, lit ? LAMP : control == held ? HELD : BUTTON);
                    write(g, label, middleX, middleY, textScale, lit ? 0xFF1A1A1C : TEXT, MIDDLE);
                }
                case PAD -> {
                    int pad = control.index();
                    boolean bright = look.padGlows(pad) && look.padLit(pad);
                    g.fill(x0, y0, x1, y1, 0xFF000000 | (!look.padGlows(pad) ? 0x2B2B30
                            : PanelLook.dim(look.padColor(pad), bright ? 1 : 0.4f)));
                    write(g, padLabel(panel, pad), middleX, middleY, textScale, bright ? 0xFF101012 : TEXT, MIDDLE);
                }
                case KNOB -> {
                    int knob = control.index();
                    float value = panel.knob[knob] / 127f;
                    int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, radius = (x1 - x0) / 2;
                    disc(g, cx, cy, radius, 0xFF000000 | PanelLook.dim(look.knobColor(knob), 0.35f + 0.65f * value));
                    disc(g, cx, cy, radius - 2, 0xFF2C2C31);
                    double angle = Math.toRadians((value - 0.5) * 270);
                    int px = cx + (int) Math.round(Math.sin(angle) * (radius - 4)), py = cy - (int) Math.round(Math.cos(angle) * (radius - 4));
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
                    write(g, control == held ? String.valueOf(panel.knob[knob]) : KNOBS[knob], cx, cy + radius + 1 + 5 * textScale,
                            textScale, control == held ? TEXT : FAINT, MIDDLE);
                }
                case LEVER -> {
                    g.fill(x0, y0, x1, y1, 0xFF0A0A0B);
                    int bend = keyboard == null ? 8192 : keyboard.bend, modulation = keyboard == null ? 0 : keyboard.modulation;
                    int grip = Math.max(3, keyWidth - 2);
                    int cx = (x0 + x1) / 2 + Math.round((bend - 8192) / 8192f * ((x1 - x0) / 2f - grip));
                    int cy = (y0 + y1) / 2 - Math.round(modulation / 127f * ((y1 - y0) / 2f - grip));
                    g.fill(cx - grip, cy - grip, cx + grip, cy + grip, HELD);
                }
            }
        }
    }

    /** A zone's button, which names its sound: lit along its left edge while it sounds, framed while the pads and knobs work it. */
    private void drawZone(GuiGraphics g, Panel panel, int zone, int x0, int y0, int x1, int y1) {
        boolean sounds = panel.sounds(zone);
        g.fill(x0, y0, x1, y1, sounds ? BUTTON : 0xFF242428);
        g.fill(x0, y0, x0 + 2, y1, sounds ? LAMP : 0xFF45454C);
        if (panel.target == zone) {
            g.renderOutline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFFFFFFFF);
        }
        String name = KeyboardAudio.programName(panel.program[zone], panel.channel[zone] == Controller.DRUM_CHANNEL);
        String line = BUTTONS[Control.ZONES[zone].ordinal()] + String.format(Locale.ROOT, "   %03d ", panel.program[zone] + 1)
                + (name == null ? "" : name);
        float scale = Math.min(2 * textScale, (x1 - x0 - 10) / (float) Math.max(1, font.width(line)));
        write(g, line, x0 + 6, (y0 + y1) / 2f + 0.5f, scale, sounds ? TEXT : FAINT, LEFT);
    }

    /** A filled circle of {@code radius} around ({@code x}, {@code y}). */
    private static void disc(GuiGraphics g, int x, int y, int radius, int color) {
        for (int row = -radius; row < radius; row++) {
            int half = (int) Math.round(Math.sqrt(radius * radius - (row + 0.5) * (row + 0.5)));
            g.fill(x - half, y + row, x + half, y + row + 1, color);
        }
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

    private void drawKeys(GuiGraphics g, Panel panel, @Nullable MidiKeyboardBlock.Entity keyboard) {
        long low = keyboard == null ? 0 : keyboard.keysDown.get(0), high = keyboard == null ? 0 : keyboard.keysDown.get(1);
        int right = keysX + stripWidth;
        if (controller.lit(Control.FUNC)) {
            legend(g, "PANIC", Controller.PANIC_KEY, 1, 0);
            legend(g, "MIDI CH 1-16", Controller.FIRST_CHANNEL_KEY, 16, 1);
            legend(g, "STEP " + STEPS[panel.step], Controller.FIRST_STEP_KEY, STEPS.length, 0);
            legend(g, "GATE " + (panel.gate == 0 ? 50 : 100) + "%", Controller.FIRST_GATE_KEY, 2, 1);
        }
        g.fill(keysX, keysY - 2, right, keysY, 0xFF000000 | PanelLook.FELT);
        g.fill(keysX, keysY, right, keysY + keysHeight, EDGE);
        // A black key is an even number of pixels wide, so it sits evenly on the line between two white ones.
        int blackWidth = Math.max(2, Math.round(keyWidth * 0.6f) & ~1), blackLength = Math.round(keysHeight * Layout.BLACK_LENGTH);
        for (int black = 0; black < 2; black++) {
            for (int note = Layout.LOWEST; note <= Layout.HIGHEST; note++) {
                if (Layout.black(note) != (black == 1)) {
                    continue;
                }
                boolean down = ((note < 64 ? low : high) >> note & 1) != 0;
                int x = keysX + Layout.across(note) * keyWidth;
                if (black == 0) {
                    g.fill(x, keysY, x + keyWidth - 1, keysY + keysHeight, down ? PRESSED : 0xFF000000 | PanelLook.WHITE_KEY);
                } else {
                    g.fill(x - blackWidth / 2, keysY, x + blackWidth / 2, keysY + blackLength, down ? PRESSED_BLACK
                            : 0xFF000000 | PanelLook.BLACK_KEY);
                }
            }
        }
        if (panel.split || controller.lit(Control.SPLIT)) {
            int x = keysX + (Layout.across(Math.clamp(panel.splitPoint, Layout.LOWEST, Layout.HIGHEST)) + 1) * keyWidth - 1;
            g.fill(x, keysY - 2, x + 1, keysY + keysHeight, 0xFF000000 | PanelLook.RED);
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
        g.fill(x0 + 1, keysY - 3, x1 - 1, keysY - 2, LAMP);
        write(g, text, Math.max((x0 + x1) / 2f, keysX + font.width(text) * textScale / 2),
                keysY - deck + lineHeight() * (row + 0.5f), textScale, LAMP, MIDDLE);
    }

    @Nullable
    private Control controlAt(double x, double y) {
        for (Control control : Control.values()) {
            int[] at = place(control);
            if (x >= at[0] && x < at[2] && y >= at[1] && y < at[3]) {
                return control;
            }
        }
        return null;
    }

    /** The note of the key at ({@code x}, {@code y}); -1 off the keys. */
    private int keyAt(double x, double y) {
        return Layout.keyAt((float) (x - keysX) / keyWidth, (float) (y - keysY) / keysHeight);
    }

    /** How hard a click at {@code y} strikes a key: harder towards its front edge. */
    private int velocityAt(double y) {
        return Mth.clamp((int) Math.round(45 + 82 * (y - keysY) / keysHeight), 1, 127);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
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
                    knobFromY = y;
                }
                case LEVER -> lever(x, y);
            }
        } else if (y >= stripY && y < stripY + stripHeight * HEADER && x > keysX + stripWidth / 2) {
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
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        if (mouseNote >= 0) {
            int note = keyAt(x, y);
            if (note != mouseNote && note >= 0) {
                controller.key(mouseNote, 0);
                mouseNote = note;
                controller.key(note, velocityAt(y));
            }
        } else if (held != null && held.kind == Layout.Kind.KNOB) {
            controller.knob(held.index(), Mth.clamp(knobFrom + (int) Math.round((knobFromY - y) * 1.5), 0, 127));
        } else if (held == Control.LEVER) {
            lever(x, y);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
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
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        Control control = controlAt(x, y);
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
