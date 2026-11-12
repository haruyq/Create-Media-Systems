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

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Playing a keyboard: its pod of controls and its 88 keys laid out flat over the world, worked with the mouse (and
 * the computer's own keys) as the real ones are with the hands, beside whatever a MIDI keyboard plugged in plays. It
 * shows what the real one has no display for: each zone's sound, the octave, what the pads are set to, the MIDI
 * inputs heard. Open for as long as this player sits at the keyboard; closing it gets them up.
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
            "NOTE", "SHIFT", "FUNC", "", "", "", "", "", "", "", "", "LOWER", "UPPER1", "UPPER2", "SPLIT", "TRANS", "S1", "S2",
            "OCT-", "OCT+", ""};
    private static final String[] KNOBS = {"CUTOFF", "RESO", "ATTACK", "RELEASE", "REVERB", "CHORUS", "PAN", "VOLUME"};
    private static final String[] STEPS = {"1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T"};
    private static final int PANEL = 0xF0202024, EDGE = 0xFF050506, TEXT = 0xFFE6E6EA, FAINT = 0xFF8C8C94, PRESSED = 0xFF8FC2FF,
            PRESSED_BLACK = 0xFF2D6FD6;

    final KeyboardClient.Playing playing;
    private final Controller controller;
    private int podX, podY, podWidth, podHeight, keysX, keysY, keyWidth, keysHeight;
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
        podHeight = Math.min(height - 16, 236);
        podWidth = Math.round(podHeight * 0.62f);
        podX = 8;
        podY = height - podHeight - 8;
        int space = width - podX - podWidth - 14;
        keyWidth = Math.max(3, space / Layout.WHITE_KEYS);
        keysX = podX + podWidth + 6 + (space - keyWidth * Layout.WHITE_KEYS) / 2;
        keysHeight = Mth.clamp(keyWidth * 9, 44, 110);
        keysY = height - keysHeight - 8;
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
        Panel panel = look.panel();
        drawPod(g, look, keyboard);
        drawKeys(g, panel, keyboard);
        drawReadout(g, panel);
        Control hovered = controlAt(mouseX, mouseY);
        if (hovered != null && held == null && mouseNote < 0) {
            g.renderTooltip(font, font.split(Component.translatable(KEY + "control." + hovered.name().toLowerCase(Locale.ROOT)), 210),
                    mouseX, mouseY);
        }
    }

    private int podX(float across) {
        return podX + Math.round(across * podWidth);
    }

    private int podY(float down) {
        return podY + Math.round(down * podHeight);
    }

    /** {@code text} at half size, its middle at ({@code x}, {@code y}). */
    private void small(GuiGraphics g, String text, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.5f, 0.5f, 1);
        g.drawString(font, text, -font.width(text) / 2, -4, color, false);
        g.pose().popPose();
    }

    private void drawPod(GuiGraphics g, PanelLook look, @Nullable MidiKeyboardBlock.Entity keyboard) {
        Panel panel = look.panel();
        g.fill(podX - 1, podY - 1, podX + podWidth + 1, podY + podHeight + 1, EDGE);
        g.fill(podX, podY, podX + podWidth, podY + podHeight, PANEL);
        small(g, "BANK " + (panel.bank() + 1), podX(0.57f), podY(0.028f), FAINT);
        for (int light = 0; light < 8; light++) {
            int x = podX(0.4f + light * 0.045f), color = look.bankLight(light);
            g.fill(x, podY(0.05f), x + 3, podY(0.05f) + 3, color < 0 ? 0xFF2B2B30 : 0xFF000000 | color);
        }
        for (Control control : Control.values()) {
            int x0 = podX(control.left), y0 = podY(control.top), x1 = podX(control.right), y1 = podY(control.bottom);
            switch (control.kind) {
                case BUTTON -> {
                    boolean lit = look.lit(control);
                    g.fill(x0, y0, x1, y1, lit ? 0xFFFFE7B8 : control == held ? 0xFF55555E : 0xFF3A3A40);
                    small(g, BUTTONS[control.ordinal()], (x0 + x1) / 2f, (y0 + y1) / 2f + 0.5f, lit ? 0xFF1A1A1C : TEXT);
                    if (control.ordinal() >= Control.LOWER.ordinal() && control.ordinal() <= Control.UPPER_2.ordinal()
                            && panel.target == control.index()) {
                        g.renderOutline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFFFFFFFF);
                    }
                }
                case PAD -> {
                    int pad = control.index();
                    g.fill(x0, y0, x1, y1, 0xFF18181A);
                    g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFF000000 | (!look.padGlows(pad) ? 0x2B2B30
                            : PanelLook.dim(look.padColor(pad), look.padLit(pad) ? 1 : 0.4f)));
                    small(g, padLabel(panel, pad), (x0 + x1) / 2f, (y0 + y1) / 2f + 0.5f, 0xFF101012);
                }
                case KNOB -> {
                    int knob = control.index();
                    float value = panel.knob[knob] / 127f;
                    int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, radius = (x1 - x0) / 2;
                    g.fill(cx - radius, cy - radius, cx + radius, cy + radius,
                            0xFF000000 | PanelLook.dim(look.knobColor(knob), 0.35f + 0.65f * value));
                    g.fill(cx - radius + 2, cy - radius + 2, cx + radius - 2, cy + radius - 2, 0xFF2C2C31);
                    double angle = Math.toRadians((value - 0.5) * 270);
                    int px = cx + (int) Math.round(Math.sin(angle) * (radius - 4)), py = cy - (int) Math.round(Math.cos(angle) * (radius - 4));
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
                    small(g, control == held ? KNOBS[knob] + " " + panel.knob[knob] : KNOBS[knob], cx, cy + radius + 3.5f, FAINT);
                }
                case LEVER -> {
                    g.fill(x0, y0, x1, y1, 0xFF0A0A0B);
                    int bend = keyboard == null ? 8192 : keyboard.bend, modulation = keyboard == null ? 0 : keyboard.modulation;
                    int cx = (x0 + x1) / 2 + Math.round((bend - 8192) / 8192f * (x1 - x0) * 0.4f);
                    int cy = (y0 + y1) / 2 - Math.round(modulation / 127f * (y1 - y0) * 0.4f);
                    g.fill(cx - 4, cy - 5, cx + 4, cy + 5, 0xFF4A4A52);
                    small(g, "BEND / MOD", (x0 + x1) / 2f, y1 + 3, FAINT);
                }
            }
        }
        for (int curve = 0; curve < Panel.CURVES; curve++) {
            int x = podX(0.64f + curve * 0.052f);
            g.fill(x, podY(0.79f), x + 3, podY(0.79f) + 3, panel.curve == curve ? 0xFF000000 | PanelLook.GREEN : 0xFF2B2B30);
        }
    }

    /** What pad {@code pad} is for as its panel is set: a program, a controller, a drum, the arpeggiator's range or order, a snapshot. */
    private String padLabel(Panel panel, int pad) {
        if (controller.snapshotMode() != 0) {
            return "S" + (pad + 1);
        }
        if (panel.arpeggio) {
            return pad < 4 ? (pad + 1) + " OCT" : new String[]{"UP", "DOWN", "U&D", "RND"}[pad - 4];
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

    private int keyX(float across) {
        return keysX + Math.round(across * keyWidth);
    }

    private void drawKeys(GuiGraphics g, Panel panel, @Nullable MidiKeyboardBlock.Entity keyboard) {
        long low = keyboard == null ? 0 : keyboard.keysDown.get(0), high = keyboard == null ? 0 : keyboard.keysDown.get(1);
        int right = keysX + keyWidth * Layout.WHITE_KEYS;
        g.fill(keysX - 2, keysY - 16, right + 2, keysY + keysHeight + 2, EDGE);
        // The functions printed above the keys, worked with FUNC: bright while it is lit.
        int legend = controller.lit(Control.FUNC) ? 0xFFFFE7B8 : FAINT;
        legend(g, "PANIC", Controller.PANIC_KEY, 1, 0, legend);
        legend(g, "MIDI CH 1-16", Controller.FIRST_CHANNEL_KEY, 16, 1, legend);
        legend(g, "STEP " + STEPS[panel.step], Controller.FIRST_STEP_KEY, STEPS.length, 0, legend);
        legend(g, "GATE " + (panel.gate == 0 ? 50 : 100) + "%", Controller.FIRST_GATE_KEY, 2, 1, legend);
        g.fill(keysX, keysY - 2, right, keysY, 0xFF000000 | PanelLook.FELT);
        for (int black = 0; black < 2; black++) {
            for (int note = Layout.LOWEST; note <= Layout.HIGHEST; note++) {
                if (Layout.black(note) != (black == 1)) {
                    continue;
                }
                boolean down = ((note < 64 ? low : high) >> note & 1) != 0;
                int x0 = keyX(Layout.left(note)), x1 = keyX(Layout.left(note) + Layout.width(note));
                if (black == 0) {
                    g.fill(x0, keysY, x1 - 1, keysY + keysHeight, down ? PRESSED : 0xFF000000 | PanelLook.WHITE_KEY);
                    if (note % 12 == 0 && keyWidth >= 6) {
                        small(g, noteName(note), (x0 + x1) / 2f, keysY + keysHeight - 4, 0xFF707078);
                    }
                } else {
                    g.fill(x0, keysY, x1, keysY + Math.round(keysHeight * Layout.BLACK_LENGTH), down ? PRESSED_BLACK
                            : 0xFF000000 | PanelLook.BLACK_KEY);
                }
            }
        }
        if (panel.split || controller.lit(Control.SPLIT)) {
            int x = keyX(Layout.across(Math.clamp(panel.splitPoint, Layout.LOWEST, Layout.HIGHEST)) + 1) - 1;
            g.fill(x, keysY - 2, x + 1, keysY + keysHeight, 0xFFFF4034);
        }
    }

    /**
     * {@code text} over the {@code count} keys from the {@code first} (counted from the lowest), underlined; on the
     * upper ({@code row} 0) or lower line, so neighbours don't run into each other.
     */
    private void legend(GuiGraphics g, String text, int first, int count, int row, int color) {
        int x0 = keyX(Layout.left(Layout.LOWEST + first)), last = Layout.LOWEST + first + count - 1;
        int x1 = keyX(Layout.left(last) + Layout.width(last));
        g.fill(x0 + 1, keysY - 4, x1 - 1, keysY - 3, color);
        small(g, text, Math.max((x0 + x1) / 2f, keysX + font.width(text) / 4f), keysY - 11.5f + row * 5, color);
    }

    /** One line of the readout and its colour. */
    private record Line(Component text, int color) {
    }

    private void drawReadout(GuiGraphics g, Panel panel) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Component.literal("Koland A-88MKII").withStyle(ChatFormatting.BOLD), TEXT));
        for (int zone = Panel.UPPER_2; zone >= Panel.LOWER; zone--) {
            boolean drums = panel.channel[zone] == Controller.DRUM_CHANNEL;
            String name = KeyboardAudio.programName(panel.program[zone], drums);
            lines.add(new Line(Component.literal(panel.target == zone ? "> " : "  ")
                    .append(Component.translatable(KEY + "zone." + zone))
                    .append(String.format(Locale.ROOT, "  CH %d  %03d ", panel.channel[zone] + 1, panel.program[zone] + 1))
                    .append(name == null ? "" : name), panel.sounds(zone) ? panel.target == zone ? 0xFFFFE7B8 : TEXT : FAINT));
        }
        lines.add(new Line(Component.translatable(KEY + "status", signed(panel.octave), signed(panel.transpose),
                Component.translatable(KEY + "velocity." + panel.curve), noteName(panel.splitPoint),
                Component.translatable(KEY + (panel.split ? "on" : "off"))), TEXT));
        lines.add(new Line(panel.arpeggio
                ? Component.translatable(KEY + "arpeggio", Component.translatable(KEY + "order." + panel.order), panel.range,
                        STEPS[panel.step], panel.gate == 0 ? 50 : 100, panel.tempo, Component.translatable(KEY + (panel.hold ? "on" : "off")))
                : Component.translatable(KEY + "pads", Component.translatable(KEY + "pads." + panel.padMode), panel.bank() + 1), TEXT));
        List<String> inputs = MidiInput.names();
        lines.add(new Line(inputs == null ? Component.translatable(KEY + "midi.looking")
                : inputs.isEmpty() ? Component.translatable(KEY + "midi.none")
                : Component.translatable(KEY + "midi", String.join(", ", inputs)), inputs == null || inputs.isEmpty() ? FAINT : 0xFF3DE06A));
        lines.add(new Line(Component.translatable(KEY + "help"), FAINT));

        // As large as fits between the pod and the right edge, and above the keys.
        int left = podX + podWidth + 6, right = width - 8, bottom = keysY - 19;
        int widest = lines.stream().mapToInt(line -> font.width(line.text)).max().orElse(1);
        float scale = Math.min(1, Math.min((right - left - 10) / (float) widest, (bottom - 8) / (lines.size() * 10f + 8)));
        int top = bottom - Math.round((lines.size() * 10 + 8) * scale);
        g.fill(left, top, right, bottom, 0xC0101012);
        g.pose().pushPose();
        g.pose().translate(left + 5, top + 4 * scale, 0);
        g.pose().scale(scale, scale, 1);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i).text, 0, i * 10, lines.get(i).color, false);
        }
        g.pose().popPose();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    @Nullable
    private Control controlAt(double x, double y) {
        return Control.at((float) (x - podX) / podWidth, (float) (y - podY) / podHeight);
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
        } else if (y < keysY - 19 && x > podX + podWidth) {
            KeyboardClient.listen(playing);
        }
        return true;
    }

    private void lever(double x, double y) {
        Control lever = Control.LEVER;
        double across = (x - podX(lever.centerX())) / ((podX(lever.right) - podX(lever.left)) * 0.4);
        double away = (podY(lever.centerY()) - y) / ((podY(lever.bottom) - podY(lever.top)) * 0.4);
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
