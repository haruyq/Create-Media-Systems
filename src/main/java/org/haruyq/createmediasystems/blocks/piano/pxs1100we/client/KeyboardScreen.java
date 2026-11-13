package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.KeysScreen;
import org.haruyq.createmediasystems.client.MidiPort;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Action;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings.Tone;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Playing a PX-S1100, on a screen laid out as the Koland keyboard's is and coloured as the PX-S1100 itself. The strip
 * has its three tabs down its left, and right of them the open tab's controls in three rows of six columns. Between
 * the strip and the keys is the keyboard's own panel, off its model: the power button, the volume knob and the touch
 * buttons under their lights, worked as on the block.
 */
final class KeyboardScreen extends KeysScreen {
    /** In white keys: how high a row of the strip is, and how far one column is from the next. */
    private static final float ROW = 2.3f, COLUMN = 7.3f;
    /** Where the tones' controls end and the tones to pick from begin, in white keys: half way across the columns. */
    private static final float TONES_END = 8 + 3 * COLUMN - 0.4f;
    /**
     * The model's panel and its lights, and the part of those textures that is the panel's face (body.json's): as wide
     * as the keys, {@link #PANEL_DEPTH} blocks from the body's back edge to the keys.
     */
    private static final ResourceLocation PANEL = CreateMediaSystems.id("textures/block/piano/pxs1100we/panel.png"),
            PANEL_LIT = CreateMediaSystems.id("textures/block/piano/pxs1100we/panel_lit.png");
    private static final int PANEL_U = 77, PANEL_ACROSS = 1893, PANEL_DOWN = 118, PANEL_WIDE = 2048, PANEL_HIGH = 128;
    private static final double PANEL_DEPTH = 1.216 / 16;
    /** The touch buttons with a light, as the block's renderer has them; the power light is beside them, not on a button. */
    private static final String[] LIGHTS = {"function", "sound_mode", "metronome", "play", "rec", "grand_piano", "elec_piano"};
    private static final KeyboardLayout.Hit POWER_LIGHT = new KeyboardLayout.Hit(18.912 / 16, 19.168 / 16, 9.136 / 16, 9.392 / 16, 0);
    private static final int[] LOWER = {GLFW_KEY_Z, GLFW_KEY_S, GLFW_KEY_X, GLFW_KEY_D, GLFW_KEY_C, GLFW_KEY_V,
            GLFW_KEY_G, GLFW_KEY_B, GLFW_KEY_H, GLFW_KEY_N, GLFW_KEY_J, GLFW_KEY_M, GLFW_KEY_COMMA,
            GLFW_KEY_L, GLFW_KEY_PERIOD, GLFW_KEY_SEMICOLON, GLFW_KEY_SLASH};
    private static final int[] UPPER = {GLFW_KEY_Q, GLFW_KEY_2, GLFW_KEY_W, GLFW_KEY_3, GLFW_KEY_E, GLFW_KEY_R,
            GLFW_KEY_5, GLFW_KEY_T, GLFW_KEY_6, GLFW_KEY_Y, GLFW_KEY_7, GLFW_KEY_U, GLFW_KEY_I,
            GLFW_KEY_9, GLFW_KEY_O, GLFW_KEY_0, GLFW_KEY_P};
    private static final int[] PEDALS = {SUSTAIN, SOSTENUTO, SOFT};
    private static final String[] FAMILIES = {"pianos", "chromatic", "organs", "guitars", "basses", "strings",
            "ensemble", "brass", "reeds", "pipes", "leads", "pads", "effects", "world", "percussive", "sfx", "kits"};
    private static String preferred;
    private static volatile int preferredChannel = -1;

    private final PxS1100Client client;
    private final PxS1100Client.Instrument instrument;
    private final MidiPort midi;
    private final Map<Integer, Integer> computer = new HashMap<>();
    private final boolean[] hardwareHeld = new boolean[128], pcPedals = new boolean[3];
    private final int[] hardwarePedals = new int[3];
    private enum Tab { PLAY, TONES, SETTINGS }

    private Tab tab;
    private EditBox search;
    private String query = "";
    private int category = -1, role, toneScroll, base = 48, mouseNote = -1;
    private boolean mouseDown, pedalLatched, closed;
    /** While the mouse turns the volume knob: the volume and the mouse's height it took hold at, and the volume last asked for. */
    private boolean turning;
    private int knobFrom, knobSent;
    private double knobFromY;

    KeyboardScreen(PxS1100Client client, PxS1100Client.Instrument instrument, boolean browser) {
        super(Component.translatable("block.create_media_systems.px_s1100"), 10.5f, 3.234f);
        // The model's white shell and grey lettering, its keys, and the gold of the maker's name for what is lit.
        text = 0xFF3C3C3F;
        faint = 0xFF8E8E8B;
        ink = 0xFF2A2418;
        lamp = 0xFFA57F2B;
        edge = 0xFF8C8C88;
        bed = 0xFF2A2A2D;
        feltColor = 0xFFCECECA;
        body = new int[]{0xFAF4F4F1, 0xFAE6E6E2};
        button = new int[]{0xFFDEDEDA, 0xFFCDCDC9};
        held = new int[]{0xFFBEBEBA, 0xFFACACA8};
        lit = new int[]{0xFFE9D29A, 0xFFCBA654};
        unlit = new int[]{0xFFE4E4E0, 0xFFDCDCD8};
        white = new int[]{0xFFD6D5D1, 0xFFECEBE6};
        whiteDown = new int[]{0xFFD9BC78, 0xFFF1DDA8};
        black = new int[]{0xFF1E1E21, 0xFF111113};
        blackTop = new int[]{0xFF3A3A3E, 0xFF242427};
        blackDown = new int[]{0xFF7A5F25, 0xFF4E3C16};
        blackTopDown = new int[]{0xFFD9B45A, 0xFFB8913F};
        this.client = client;
        this.instrument = instrument;
        tab = browser ? Tab.TONES : Tab.PLAY;
        midi = new MidiPort(preferred, this::receiveMidi, this::requestMidiReset);
        midi.scan();
    }

    synchronized void receiveMidi(javax.sound.midi.ShortMessage message) {
        int filter = preferredChannel;
        if (closed || filter >= 0 && message.getChannel() != filter) return;
        var event = new KeyboardMidi(message.getStatus(), message.getData1(), message.getData2());
        if (!event.valid() || event.metronome()) return;
        if (event.channel() != 0 || acceptHardware(event)) {
            int pedal = event.command() == CONTROL && event.channel() == 0 ? pedalIndex(event.key()) : -1;
            note(pedal < 0 ? event : cc(event.key(), effectivePedal(pedal)));
        }
    }

    PxS1100Client.Instrument instrument() { return instrument; }
    void openTones() { switchTab(Tab.TONES); }

    @Override protected void init() {
        super.init();
        search = null;
        if (hidden) return;
        for (var next : Tab.values()) {
            float top = top(next.ordinal());
            button(0.6f, top, 7.4f, top + ROW, () -> t(next.name().toLowerCase(Locale.ROOT)), () -> switchTab(next))
                    .lit(() -> tab == next);
        }
        switch (tab) {
            case PLAY -> playWidgets();
            case TONES -> toneWidgets();
            case SETTINGS -> settingsWidgets();
        }
    }

    private void switchTab(Tab next) {
        if (tab == next) return;
        tab = next;
        rebuildWidgets();
    }

    /** The top of row {@code row} of the strip's three, in white keys. */
    private static float top(int row) { return 1.75f + row * 2.95f; }

    /** A button on {@code row}, {@code span} columns wide from {@code column}. */
    private Face cell(int column, int row, int span, Supplier<Component> text, Runnable press) {
        float left = 8 + column * COLUMN;
        return button(left, top(row), left + span * COLUMN - 0.4f, top(row) + ROW, text, press);
    }

    private void slide(int column, int row, int span, String key, int min, int max, IntSupplier current, IntConsumer apply) {
        float left = 8 + column * COLUMN;
        slider(left, top(row), left + span * COLUMN - 0.4f, top(row) + ROW, value -> t(key, value), min, max, current, apply);
    }

    private void playWidgets() {
        cell(0, 0, 6, this::toneTitle, this::openTones);
        slide(0, 1, 6, "volume", 0, 127, () -> settings().volume, value -> action(Action.VOLUME, value));
        cell(0, 2, 1, () -> t(flag("Switch") ? "power_on" : "power_off"), () -> action(Action.POWER))
                .lit(() -> flag("Powered")).hint(t("power_help"));
        cell(1, 2, 1, () -> t(flag("Playing") ? "stop" : "playback"), () -> action(Action.PLAY)).lit(() -> flag("Playing"));
        cell(2, 2, 1, () -> t(flag("Recording") ? "stop" : "rec"), () -> action(Action.RECORD)).lit(() -> flag("Recording"));
        cell(3, 2, 1, () -> t("metro_toggle", flag("Metro") ? "●" : "○"), () -> action(Action.METRONOME)).lit(() -> flag("Metro"));
        cell(4, 2, 1, () -> t("pedal_toggle", instrument.keys.pedal() ? "●" : "○"), this::togglePedal)
                .lit(() -> instrument.keys.pedal()).hint(t("pedal_help"));
        cell(5, 2, 1, () -> t("zoom", zoom), () -> zoom(zoom == 4 ? 1 : zoom * 2)).hint(t("zoom_help"));
    }

    /** The search box, the left half of the tones' first row: its place in the window. */
    private int[] searchBox() { return place(8, top(0), 18.55f, top(0) + ROW); }

    private void toneWidgets() {
        int[] box = searchBox();
        int inset = keyWidth / 2;
        // The one vanilla widget: its lettering, without its border, as large as the rest and over a box drawn as the rest is.
        float scale = (float) (textScale / pixel);
        search = new EditBox(font, (int) ((box[0] + inset) / textScale), (int) ((box[1] + box[3]) / 2 / textScale) - 4,
                (int) ((box[2] - box[0] - 2 * inset) / textScale), 9, t("search")) {
            @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                g.pose().pushPose();
                g.pose().scale(scale, scale, 1);
                super.renderWidget(g, mouseX, mouseY, partialTick);
                g.pose().popPose();
            }
            @Override public boolean mouseClicked(double x, double y, int button) {
                return super.mouseClicked(x / scale, y / scale, button);
            }
        };
        search.setBordered(false);
        search.setMaxLength(64);
        search.setHint(Component.literal(font.plainSubstrByWidth(t("search").getString(), search.getWidth())));
        search.setValue(query);
        search.setResponder(value -> { query = value; toneScroll = 0; });
        addRenderableWidget(search);
        button(18.95f, top(0), TONES_END, top(0) + ROW, () -> t(switch (role) {
            case 1 -> "layer_voice";
            case 2 -> "lower_voice";
            default -> "main_voice";
        }), () -> role = (role + 1) % 3);
        button(8, top(1), 10.3f, top(1) + ROW, () -> Component.literal("‹"), () -> category(-1));
        button(10.7f, top(1), TONES_END - 2.7f, top(1) + ROW, this::categoryLabel, () -> category(1)).hint(t("category_help"));
        button(TONES_END - 2.3f, top(1), TONES_END, top(1) + ROW, () -> Component.literal("›"), () -> category(1));
        button(8, top(2), 10.3f, top(2) + ROW, () -> Component.literal("▲"), () -> page(-1));
        button(10.7f, top(2), 13, top(2) + ROW, () -> Component.literal("▼"), () -> page(1));
        button(13.4f, top(2), TONES_END, top(2) + ROW,
                () -> t("favorite", KeyboardToneHistory.get().favorite(bankName(), chosen()) ? "★" : "☆"),
                () -> KeyboardToneHistory.get().toggle(bankName(), chosen()));
    }

    private void settingsWidgets() {
        cell(0, 0, 2, this::device, this::nextInput).hint(t("device_help"));
        cell(2, 0, 1, () -> t("refresh"), midi::scan);
        cell(3, 0, 1, () -> preferredChannel < 0 ? t("all_channels") : t("channel", preferredChannel + 1), () -> {
            preferredChannel = preferredChannel == 15 ? -1 : preferredChannel + 1;
            requestMidiReset();
        });
        cell(4, 0, 1, () -> t("panic"), this::panic);
        cell(5, 0, 1, () -> t("sf2", settings().font), this::nextFont).hint(t("font_help"));
        cell(0, 1, 1, () -> t("stand_toggle", flag("Stand") ? "●" : "○"), () -> action(Action.STAND, flag("Stand") ? 0 : 1))
                .lit(() -> flag("Stand"));
        cell(1, 1, 1, () -> t("rack_toggle", flag("Rack") ? "●" : "○"), () -> action(Action.RACK, flag("Rack") ? 0 : 1))
                .lit(() -> flag("Rack"));
        cell(2, 1, 1, () -> t("layer_toggle", settings().layered ? "●" : "○"), () -> action(Action.LAYER_ON, settings().layered ? 0 : 1))
                .lit(() -> settings().layered);
        cell(3, 1, 1, () -> t("split_toggle", settings().split ? "●" : "○"), () -> action(Action.SPLIT_ON, settings().split ? 0 : 1))
                .lit(() -> settings().split);
        slide(4, 1, 1, "split_key", 22, 108, () -> settings().splitKey, n -> action(Action.SPLIT_KEY, n));
        cell(5, 1, 1, () -> t("poly", settings().polyphony),
                () -> action(Action.POLYPHONY, settings().polyphony >= 256 ? 32 : (settings().polyphony / 32 + 1) * 32));
        slide(0, 2, 1, "transpose", -36, 36, () -> settings().transpose, n -> action(Action.TRANSPOSE, n));
        slide(1, 2, 1, "touch", 0, 4, () -> settings().touch, n -> action(Action.TOUCH, n));
        slide(2, 2, 1, "reverb", 0, 127, () -> settings().reverb, n -> action(Action.REVERB, n));
        slide(3, 2, 1, "chorus", 0, 127, () -> settings().chorus, n -> action(Action.CHORUS, n));
        slide(4, 2, 1, "bpm", 20, 300, () -> settings().bpm, n -> action(Action.BPM, n));
        slide(5, 2, 1, "beats", 1, 12, () -> settings().beats, n -> action(Action.BEATS, n));
    }

    /** The MIDI input it listens to, or why it listens to none. */
    private Component device() {
        var status = midi.status();
        var name = status.inputs().stream().filter(input -> input.id().equals(status.selected())).map(MidiPort.Input::name).findFirst();
        return name.isPresent() && status.message().equals("keyboard.midi_connected") ? Component.literal(name.get())
                : Component.translatable(status.message());
    }

    private static Component t(String key, Object... args) { return Component.translatable("keyboard." + key, args); }
    private boolean flag(String name) { return instrument.view.getBoolean(name); }
    private KeyboardSettings settings() { return instrument.settings; }
    private void action(Action action) { action(action, 0); }
    private void action(Action action, int first) { client.command(instrument, action, first, 0, ""); }
    private void note(KeyboardMidi event) { client.send(instrument, List.of(event)); }

    private void nextInput() {
        var status = midi.status();
        var inputs = status.inputs();
        int next = 0;
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).id().equals(status.selected())) next = i + 1;
        if (inputs.isEmpty()) midi.scan();
        else midi.select(next == inputs.size() ? "" : inputs.get(next).id());
    }

    private void nextFont() {
        var available = KeyboardSoundbank.choices();
        int next = (available.indexOf(settings().font) + 1) % available.size();
        client.command(instrument, Action.FONT, 0, 0, available.get(next));
    }

    private KeyboardSoundbank.Bank bank() {
        var future = KeyboardSoundbank.bank(settings().font);
        return future.isCompletedExceptionally() ? null : future.getNow(null);
    }
    private String bankName() { var bank = bank(); return bank == null ? settings().font : bank.name(); }
    private Component toneTitle() {
        var bank = bank();
        return bank == null ? t("font_loading") : Component.literal(bank.title(settings().main));
    }
    private Tone chosen() { return role == 1 ? settings().layer : role == 2 ? settings().lower : settings().main; }

    private void category(int direction) {
        category = Math.floorMod(category + direction + 1, FAMILIES.length + 3) - 1;
        toneScroll = 0;
    }
    private Component categoryLabel() {
        if (category < 0) return t("all_tones");
        if (category == FAMILIES.length) return t("favorites");
        if (category > FAMILIES.length) return t("recent");
        return t("family." + FAMILIES[category]);
    }

    private List<KeyboardSoundbank.Patch> tones() {
        var bank = bank();
        if (bank == null) return List.of();
        var history = KeyboardToneHistory.get();
        var needle = query.strip().toLowerCase(Locale.ROOT);
        var matching = bank.patches().stream().filter(patch -> {
            var tone = patch.tone();
            if (category == FAMILIES.length && !history.favorite(bank.name(), tone)) return false;
            if (category > FAMILIES.length && history.recent(bank.name(), tone) < 0) return false;
            if (category >= 0 && category < FAMILIES.length && patch.category() != category) return false;
            return needle.isEmpty() || patch.name().toLowerCase(Locale.ROOT).contains(needle)
                    || (tone.bank() + ":" + tone.program()).contains(needle);
        });
        if (category > FAMILIES.length) matching = matching.sorted(Comparator.comparingInt(p -> history.recent(bank.name(), p.tone())));
        return matching.toList();
    }

    /** The tones to pick from, right of the search and what narrows them: its place in the window, and a row of it. */
    private int[] list() { return place(TONES_END + 0.4f, top(0), 51.4f, top(2) + ROW); }
    private int rowHeight() { return lineHeight() * 3 / 2; }
    private int rows() { return Math.max(1, (list()[3] - list()[1]) / rowHeight()); }
    private void page(int direction) { scrollTones(direction * rows(), tones().size()); }
    private void scrollTones(int amount, int count) { toneScroll = Math.clamp(toneScroll + amount, 0, Math.max(0, count - rows())); }
    private void pick(Tone tone) {
        KeyboardToneHistory.get().used(bankName(), tone);
        client.command(instrument, role == 1 ? Action.LAYER : role == 2 ? Action.LOWER : Action.MAIN, tone.bank(), tone.program(), "");
    }

    @Override public void tick() {
        super.tick();
        if (ticks % 40 == 0) midi.scan();
        if (!Minecraft.getInstance().isWindowActive()) releaseComputer();
    }

    private synchronized void requestMidiReset() {
        if (closed) return;
        var batch = new ArrayList<KeyboardMidi>();
        resetHardware(batch);
        client.send(instrument, batch);
    }

    private void resetHardware(List<KeyboardMidi> batch) {
        Arrays.fill(hardwareHeld, false);
        Arrays.fill(hardwarePedals, 0);
        for (int channel = 0; channel < 16; channel++) {
            batch.add(new KeyboardMidi(CONTROL | channel, SILENCE, 0));
            batch.add(new KeyboardMidi(CONTROL | channel, RESET, 0));
        }
        for (int n = 0; n < PEDALS.length; n++) batch.add(cc(PEDALS[n], effectivePedal(n)));
        computer.values().stream().distinct().forEach(n -> batch.add(KeyboardMidi.note(n, 88)));
        if (mouseNote >= 0 && !computer.containsValue(mouseNote)) batch.add(KeyboardMidi.note(mouseNote, 88));
    }

    private boolean acceptHardware(KeyboardMidi event) {
        int key = event.key();
        switch (event.command()) {
            case ON, OFF -> {
                boolean pressed = event.command() == ON && event.value() != 0;
                hardwareHeld[key] = pressed;
                return pressed || !pcHolds(key);
            }
            case CONTROL -> {
                int pedal = pedalIndex(key);
                if (pedal >= 0) hardwarePedals[pedal] = event.value();
                if (key == SILENCE || key == NOTES_OFF) Arrays.fill(hardwareHeld, false);
                if (key == RESET) Arrays.fill(hardwarePedals, 0);
            }
        }
        return true;
    }

    private static int pedalIndex(int controller) {
        for (int n = 0; n < PEDALS.length; n++) if (PEDALS[n] == controller) return n;
        return -1;
    }
    private static int pedalKey(int key) {
        return switch (key) {
            case GLFW_KEY_SPACE -> 0;
            case GLFW_KEY_LEFT_SHIFT -> 1;
            case GLFW_KEY_LEFT_CONTROL -> 2;
            default -> -1;
        };
    }
    private int effectivePedal(int i) { return pcPedals[i] || i == 0 && pedalLatched ? 127 : hardwarePedals[i]; }
    private void pedal(int i) { note(cc(PEDALS[i], effectivePedal(i))); }
    private synchronized void togglePedal() {
        pedalLatched = !pedalLatched;
        pedal(0);
    }
    private synchronized void panic() {
        computer.clear();
        mouseDown = pedalLatched = false;
        mouseNote = -1;
        Arrays.fill(pcPedals, false);
        Arrays.fill(hardwareHeld, false);
        Arrays.fill(hardwarePedals, 0);
        action(Action.PANIC);
    }

    @Override public synchronized boolean keyPressed(int key, int scan, int mods) {
        if (search != null && search.isFocused() || tab != Tab.PLAY) return super.keyPressed(key, scan, mods);
        int pedal = pedalKey(key);
        if (pedal >= 0) {
            if (!pcPedals[pedal]) {
                pcPedals[pedal] = true;
                pedal(pedal);
            }
            return true;
        }
        if (key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT) {
            base = Math.clamp(base + (key == GLFW_KEY_LEFT ? -12 : 12), 24, 72);
            return true;
        }
        int played = computerNote(key);
        if (played >= 0) {
            if (!computer.containsKey(key)) {
                boolean held = pcHolds(played) || hardwareHeld[played];
                computer.put(key, played);
                if (!held) note(KeyboardMidi.note(played, 88));
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override public synchronized boolean keyReleased(int key, int scan, int mods) {
        int pedal = pedalKey(key);
        if (pedal >= 0 && pcPedals[pedal]) {
            pcPedals[pedal] = false;
            pedal(pedal);
            return true;
        }
        var note = computer.remove(key);
        if (note != null) {
            release(note);
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }
    private int computerNote(int key) {
        for (int i = 0; i < LOWER.length; i++) if (LOWER[i] == key) return base + i;
        for (int i = 0; i < UPPER.length; i++) if (UPPER[i] == key) return base + 12 + i;
        return -1;
    }
    private boolean pcHolds(int note) { return mouseNote == note || computer.containsValue(note); }
    private void release(int note) {
        if (note >= 0 && !pcHolds(note) && !hardwareHeld[note]) note(KeyboardMidi.note(note, 0));
    }
    private synchronized void releaseComputer() {
        var held = new ArrayList<>(computer.values());
        if (mouseNote >= 0) held.add(mouseNote);
        mouseDown = false;
        mouseNote = -1;
        computer.clear();
        held.stream().distinct().forEach(this::release);
        for (int i = 0; i < PEDALS.length; i++) if (pcPedals[i]) {
            pcPedals[i] = false;
            pedal(i);
        }
    }

    @Override public synchronized boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX * pixel, y = mouseY * pixel;
        // A click anywhere in its box takes the search up; one anywhere else puts it down, and the computer's keys play again.
        if (search != null) setFocused(in(searchBox(), x, y) ? search : null);
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        String control = panelAt(x, y);
        if (control != null) {
            // As the block does when the same is clicked on it (KeyboardRuntime.panel).
            switch (control) {
                case "power_button" -> action(Action.POWER);
                case "volume_knob" -> {
                    turning = true;
                    knobFrom = knobSent = settings().volume;
                    knobFromY = y;
                }
                case "function" -> switchTab(tab == Tab.TONES ? Tab.PLAY : Tab.TONES);
                case "sound_mode" -> action(Action.SOUND_MODE);
                case "metronome" -> action(Action.METRONOME);
                case "play" -> action(Action.PLAY);
                case "rec" -> action(Action.RECORD);
                case "grand_piano" -> client.command(instrument, Action.MAIN, 0, 0, "");
                case "elec_piano" -> client.command(instrument, Action.MAIN, 0, 4, "");
            }
            return true;
        }
        if (keyAt(x, y) >= 0) {
            mouseDown = true;
            playMouse(x, y);
            return true;
        }
        int[] list = list();
        if (tab == Tab.TONES && in(list, x, y)) {
            int row = (int) (y - list[1]) / rowHeight();
            var tones = tones();
            scrollTones(0, tones.size());
            if (row < rows() && toneScroll + row < tones.size()) {
                pick(tones.get(toneScroll + row).tone());
                return true;
            }
        }
        return false;
    }
    @Override public synchronized boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (mouseDown && button == 0) {
            playMouse(x * pixel, y * pixel);
            return true;
        }
        if (turning) {
            // Upwards turns it up, as the Koland keyboard's knobs.
            volume(knobFrom + (int) Math.round((knobFromY - y * pixel) / pixel * 1.5));
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public synchronized boolean mouseReleased(double x, double y, int button) {
        turning = false;
        if (mouseDown && button == 0) {
            mouseDown = false;
            int last = mouseNote;
            mouseNote = -1;
            release(last);
            return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (hidden || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        if ("volume_knob".equals(panelAt(x * pixel, y * pixel))) {
            knobSent = settings().volume;
            volume(knobSent + (vertical > 0 ? 4 : -4));
        } else if (y * pixel >= keysY - felt) {
            firstWhite = Math.clamp(firstWhite + (vertical > 0 ? -4 : 4), 0, Layout.WHITE_KEYS - whites());
        } else if (tab == Tab.TONES) {
            scrollTones(vertical > 0 ? -3 : 3, tones().size());
        }
        return true;
    }

    private void playMouse(double x, double y) {
        int next = keyAt(x, y);
        if (next == mouseNote) return;
        int previous = mouseNote;
        boolean held = next >= 0 && (pcHolds(next) || hardwareHeld[next]);
        mouseNote = next;
        release(previous);
        if (next >= 0 && !held) note(KeyboardMidi.note(next, Math.clamp(
                20 + (int) (107 * (y - keysY) / (keysHeight * (Layout.black(next) ? Layout.BLACK_LENGTH : 1))), 20, 127)));
    }

    private void zoom(int amount) {
        int center = firstWhite + whites() / 2;
        zoom = amount;
        firstWhite = Math.clamp(center - whites() / 2, 0, Layout.WHITE_KEYS - whites());
    }
    private int whites() { return Layout.WHITE_KEYS / zoom; }

    /** Asks for the volume {@code value}, once. */
    private void volume(int value) {
        int next = Math.clamp(value, 0, 127);
        if (next != knobSent) action(Action.VOLUME, knobSent = next);
    }

    /**
     * Where the part of the panel from {@code minX} to {@code maxX} and {@code minZ} to {@code maxZ} of the model (in
     * blocks, as {@link KeyboardLayout} has it) is: its left, top, right and bottom in the window, then its left,
     * top, width and height on the panel's textures. The bass end is at the model's high x, the keys at its low z.
     */
    private int[] onPanel(double minX, double maxX, double minZ, double maxZ) {
        var keys = KeyboardLayout.PX.keys;
        double bass = keys.getFirst().hits().getFirst().maxX(), wide = bass - keys.getLast().hits().getFirst().minX();
        double back = KeyboardLayout.PX.body.maxZ;
        double left = (bass - maxX) / wide, right = (bass - minX) / wide, up = (back - maxZ) / PANEL_DEPTH, down = (back - minZ) / PANEL_DEPTH;
        int top = keysY - deck, high = deck - felt;
        return new int[]{keysX + (int) Math.round(left * stripWidth), top + (int) Math.round(up * high),
                keysX + (int) Math.round(right * stripWidth), top + (int) Math.round(down * high),
                PANEL_U + (int) Math.round(left * PANEL_ACROSS), (int) Math.round(up * PANEL_DOWN),
                (int) Math.round((right - left) * PANEL_ACROSS), (int) Math.round((down - up) * PANEL_DOWN)};
    }

    private int[] onPanel(KeyboardLayout.Hit hit) { return onPanel(hit.minX(), hit.maxX(), hit.minZ(), hit.maxZ()); }

    /** The control of the panel at ({@code x}, {@code y}) of the window, named as the layout names it; null for none. */
    private String panelAt(double x, double y) {
        if (hidden) return null;
        for (var control : KeyboardLayout.PX.panel.entrySet()) {
            if (in(onPanel(control.getValue()), x, y)) return control.getKey();
        }
        return null;
    }

    /** Whether the light of touch button {@code button} is on, as on the block (KeyboardRenderer). */
    private boolean lit(String button) {
        var main = settings().main;
        return switch (button) {
            case "function" -> tab == Tab.TONES;
            case "sound_mode" -> settings().reverb > 0 || settings().chorus > 0;
            case "metronome" -> flag("Metro");
            case "play" -> flag("Playing");
            case "rec" -> flag("Recording");
            case "grand_piano" -> main.bank() == 0 && main.program() == 0;
            default -> main.bank() == 0 && main.program() == 4;
        };
    }

    /** The panel off the model, with the lights that are on, and the two parts that are models of their own: the button and the knob. */
    private void drawPanel(GuiGraphics g) {
        picture(g, PANEL, keysX, keysY - deck, keysX + stripWidth, keysY - felt, PANEL_U, 0, PANEL_ACROSS, PANEL_DOWN, PANEL_WIDE, PANEL_HIGH);
        if (flag("Powered")) {
            if (instrument.view.getInt("Energy") >= 12000 || ticks / 6 % 2 == 0) light(g, onPanel(POWER_LIGHT));
            for (String button : LIGHTS) {
                if (lit(button)) light(g, onPanel(KeyboardLayout.PX.panel.get(button)));
            }
        }
        var layout = KeyboardLayout.PX.panel;
        int[] power = onPanel(layout.get("power_button")), knob = onPanel(layout.get("volume_knob"));
        float small = (power[2] - power[0]) / 2f, large = (knob[2] - knob[0]) / 2f;
        int px = (power[0] + power[2]) / 2, py = (power[1] + power[3]) / 2, kx = (knob[0] + knob[2]) / 2, ky = (knob[1] + knob[3]) / 2;
        disc(g, px, py, small, 0xFFC4C4C0);
        disc(g, px, py - (flag("Switch") ? 0 : 1), small - 1.5f, 0xFFF2F2EF);
        disc(g, kx, ky + 1, large, 0x40000000);
        disc(g, kx, ky, large, 0xFFCACAC6);
        disc(g, kx, ky - 1, large - 1.5f, 0xFFF2F2EF);
        // From 150 degrees one way round to 150 the other, as the model's knob turns.
        double angle = Math.toRadians((settings().volume / 127.0 - 0.5) * 300), reach = large * 0.62;
        disc(g, kx + (int) Math.round(Math.sin(angle) * reach), ky - (int) Math.round(Math.cos(angle) * reach),
                Math.max(1.5f, large / 6), 0xFF6E6E6B);
    }

    private void light(GuiGraphics g, int[] at) {
        picture(g, PANEL_LIT, at[0], at[1], at[2], at[3], at[4], at[5], at[6], at[7], PANEL_WIDE, PANEL_HIGH);
    }

    @Override protected void draw(GuiGraphics g, double x, double y) {
        int energy = instrument.view.getInt("Energy");
        var power = flag("Powered") ? t("energy", Math.max(1, (int) (energy * 100L / 2880000)))
                : t(flag("Switch") ? "needs_power" : "power_off");
        // At the right: what it records or plays; or the SoundFont, among the settings; or how the computer's keys play it.
        Component right = t("keys_help", base / 12 - 1);
        int rightColor = faint;
        if (flag("Recording") || flag("Playing")) {
            right = t(flag("Recording") ? "recording" : "playing", instrument.view.getInt("Events"));
            rightColor = flag("Recording") ? 0xFFD0353A : lamp;
        } else if (tab == Tab.SETTINGS) {
            var bank = bank();
            right = bank == null ? t("font_loading") : bank.name().equals(settings().font)
                    ? t("patch_count", bank.patches().size()) : t("font_fallback");
        }
        drawHeader(g, title.getString(), power.getString(), flag("Powered") ? lamp : faint, right.getString(), rightColor);
        if (tab == Tab.TONES) drawTones(g, x, y);
        drawPanel(g);
        if (zoom > 1) {
            // Which of the keys are in sight, under the panel's marks for all of them.
            g.fill(keysX + firstWhite * keyWidth, keysY - felt - 3, keysX + (firstWhite + whites()) * keyWidth, keysY - felt - 1, lamp);
        }
        drawKeys(g, note -> instrument.keys.pressed(note) || pcHolds(note));
    }

    private void drawTones(GuiGraphics g, double x, double y) {
        int[] box = searchBox(), list = list();
        int corner = Math.max(2, keyWidth / 5), high = rowHeight(), inset = keyWidth / 3;
        // Dark, as a display: the search's own lettering is light and has a shadow.
        box(g, box[0], box[1], box[2], box[3], corner, true, black[0], black[1]);
        var matching = tones();
        scrollTones(0, matching.size());
        if (matching.isEmpty()) {
            write(g, t(bank() == null ? "font_loading" : "no_tones").getString(), list[0] + inset, list[1] + high / 2f, textScale, faint, LEFT);
            return;
        }
        var history = KeyboardToneHistory.get();
        for (int i = 0; i < rows() && toneScroll + i < matching.size(); i++) {
            var patch = matching.get(toneScroll + i);
            int top = list[1] + i * high;
            boolean selected = patch.tone().equals(chosen());
            int[] face = selected ? lit : in(new int[]{list[0], top, list[2], top + high}, x, y) ? held : unlit;
            box(g, list[0], top, list[2], top + high - 2, corner, true, face[0], face[1]);
            String number = "%03d / %03d".formatted(patch.tone().bank(), patch.tone().program());
            String name = (history.favorite(bankName(), patch.tone()) ? "★ " : "") + patch.name();
            float middle = top + (high - 2) / 2f + textScale / 2;
            write(g, fit(name, list[2] - list[0] - 3 * inset - Math.round(font.width(number) * textScale)), list[0] + inset, middle,
                    textScale, selected ? ink : text, LEFT);
            write(g, number, list[2] - inset, middle, textScale, selected ? ink : faint, RIGHT);
        }
    }

    @Override public synchronized void removed() {
        closed = true;
        preferred = midi.preference();
        midi.close();
        action(Action.CLOSE);
        super.removed();
    }
}
