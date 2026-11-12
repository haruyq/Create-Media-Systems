package org.haruyq.createmediasystems.keyboard.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.haruyq.createmediasystems.client.MidiPort;
import org.haruyq.createmediasystems.keyboard.KeyboardLayout;
import org.haruyq.createmediasystems.keyboard.KeyboardMidi;
import org.haruyq.createmediasystems.keyboard.KeyboardPackets.Action;
import org.haruyq.createmediasystems.keyboard.KeyboardSettings;
import org.haruyq.createmediasystems.keyboard.KeyboardSettings.Tone;

import static org.haruyq.createmediasystems.keyboard.KeyboardMidi.*;
import static org.lwjgl.glfw.GLFW.*;

final class KeyboardScreen extends Screen {
    private static final int INK = 0xFFF1F6FC, MUTED = 0xFFADBED0, ACCENT = 0xFF7BE1D1, DARK = 0xFF101820;
    private static final int[] LOWER = {GLFW_KEY_Z, GLFW_KEY_S, GLFW_KEY_X, GLFW_KEY_D, GLFW_KEY_C, GLFW_KEY_V,
            GLFW_KEY_G, GLFW_KEY_B, GLFW_KEY_H, GLFW_KEY_N, GLFW_KEY_J, GLFW_KEY_M, GLFW_KEY_COMMA,
            GLFW_KEY_L, GLFW_KEY_PERIOD, GLFW_KEY_SEMICOLON, GLFW_KEY_SLASH};
    private static final int[] UPPER = {GLFW_KEY_Q, GLFW_KEY_2, GLFW_KEY_W, GLFW_KEY_3, GLFW_KEY_E, GLFW_KEY_R,
            GLFW_KEY_5, GLFW_KEY_T, GLFW_KEY_6, GLFW_KEY_Y, GLFW_KEY_7, GLFW_KEY_U, GLFW_KEY_I,
            GLFW_KEY_9, GLFW_KEY_O, GLFW_KEY_0, GLFW_KEY_P};
    private static final int[] PEDALS = {SUSTAIN, SOSTENUTO, SOFT};
    private static final int TOTAL_WHITE = 52;
    private static final String[] FAMILIES = {"pianos", "chromatic", "organs", "guitars", "basses", "strings",
            "ensemble", "brass", "reeds", "pipes", "leads", "pads", "effects", "world", "percussive", "sfx", "kits"};
    private static String preferred;
    private static int preferredChannel = -1;

    private final KeyboardClient client;
    private final KeyboardClient.Instrument instrument;
    private final MidiPort midi;
    private final ArrayBlockingQueue<KeyboardMidi> pending = new ArrayBlockingQueue<>(384);
    private final AtomicBoolean drainPosted = new AtomicBoolean(), resetMidi = new AtomicBoolean();
    private final Map<Integer, Integer> computer = new HashMap<>();
    private final boolean[] hardwareHeld = new boolean[128], pcPedals = new boolean[3];
    private final int[] hardwarePedals = new int[3];
    private final List<Label> labels = new ArrayList<>();
    private final List<SettingSlider> sliders = new ArrayList<>();
    private enum Tab { PLAY, TONES, SETTINGS }
    private record Label(Button button, Supplier<Component> text) {}

    private Tab tab;
    private Button device;
    private EditBox search;
    private String query = "";
    private int category = -1, role, toneScroll, ticks, base = 48, zoom = 1, firstWhite;
    private int left, top, panel, tall, keyLeft, keyTop, keyWidth, keyHeight, mouseNote = -1;
    private boolean mouseDown, pedalLatched;

    KeyboardScreen(KeyboardClient client, KeyboardClient.Instrument instrument, boolean browser) {
        super(Component.translatable("block.create_media_systems.px_s1100"));
        this.client = client;
        this.instrument = instrument;
        tab = browser ? Tab.TONES : Tab.PLAY;
        midi = new MidiPort(preferred, this::receiveMidi, this::requestMidiReset);
        midi.scan();
    }

    void receiveMidi(javax.sound.midi.ShortMessage message) {
        int filter = preferredChannel;
        if (filter >= 0 && message.getChannel() != filter) return;
        var event = new KeyboardMidi(message.getStatus(), message.getData1(), message.getData2());
        if (!event.valid() || event.metronome()) return;
        if (!pending.offer(event)) requestMidiReset();
        postDrain();
    }

    KeyboardClient.Instrument instrument() { return instrument; }
    void openTones() { switchTab(Tab.TONES); }

    @Override protected void init() {
        panel = Math.min(560, width - 16);
        tall = Math.min(238, height - 12);
        left = (width - panel) / 2;
        top = (height - tall) / 2;
        build();
    }

    private void switchTab(Tab next) {
        if (tab == next) return;
        releaseComputer();
        tab = next;
        build();
    }

    private void build() {
        clearWidgets();
        setFocused(null);
        labels.clear();
        sliders.clear();
        search = null;
        device = null;
        int content = panel - 20, each = (content - 8) / 3;
        for (var next : Tab.values()) {
            String label = next.name().toLowerCase(Locale.ROOT);
            var button = addRenderableWidget(Button.builder(t(label), b -> switchTab(next))
                    .bounds(left + 10 + next.ordinal() * (each + 4), top + 27, each, 18).build());
            button.active = next != tab;
        }
        switch (tab) {
            case PLAY -> playWidgets();
            case TONES -> toneWidgets();
            case SETTINGS -> settingsWidgets();
        }
    }

    private void playWidgets() {
        int start = left + 10, content = panel - 20, cell = (content - 20) / 6;
        label(start, 52, cell, () -> t(flag("Switch") ? "power_on" : "power_off"), () -> action(Action.POWER), "power_help");
        label(start + (cell + 4), 52, cell, () -> t(flag("Playing") ? "stop" : "playback"), () -> action(Action.PLAY), null);
        label(start + 2 * (cell + 4), 52, cell, () -> t(flag("Recording") ? "stop" : "rec"), () -> action(Action.RECORD), null);
        label(start + 3 * (cell + 4), 52, cell, () -> t("metro_toggle", flag("Metro") ? "●" : "○"), () -> action(Action.METRONOME), null);
        label(start + 4 * (cell + 4), 52, cell, () -> t("pedal_toggle", instrument.keys.pedal() ? "●" : "○"), () -> {
            pedalLatched = !pedalLatched;
            pedal(0);
        }, "pedal_help");
        label(start + 5 * (cell + 4), 52, cell, () -> t("zoom", zoom), () -> zoom(zoom == 4 ? 1 : zoom * 2), "zoom_help");
        slider(start, 75, content / 2 - 2, "volume", 0, 127, () -> settings().volume, value -> action(Action.VOLUME, value));
        label(start + content / 2 + 2, 75, content / 2 - 2, this::toneTitle, this::openTones, null);
        keyLeft = start;
        keyTop = top + 108;
        keyWidth = content;
        keyHeight = Math.max(28, tall - 151);
    }

    private void toneWidgets() {
        int start = left + 10, content = panel - 20;
        search = new EditBox(font, start, top + 53, content / 2 - 2, 18, t("search"));
        search.setMaxLength(64);
        search.setHint(t("search"));
        search.setValue(query);
        search.setResponder(value -> { query = value; toneScroll = 0; });
        addRenderableWidget(search);
        label(start + content / 2 + 2, 52, content / 2 - 2, () -> t(switch (role) {
            case 1 -> "layer_voice";
            case 2 -> "lower_voice";
            default -> "main_voice";
        }), () -> role = (role + 1) % 3, null);
        label(start, 75, 22, () -> Component.literal("‹"), () -> category(-1), null);
        label(start + 25, 75, content - 50, this::categoryLabel, () -> category(1), "category_help");
        label(start + content - 22, 75, 22, () -> Component.literal("›"), () -> category(1), null);
        label(start, tall - 27, 36, () -> Component.literal("▲"), () -> page(-1), null);
        label(start + 40, tall - 27, 36, () -> Component.literal("▼"), () -> page(1), null);
        label(start + 80, tall - 27, content - 80, () -> t("favorite", KeyboardToneHistory.get().favorite(bankName(), chosen()) ? "★" : "☆"),
                () -> KeyboardToneHistory.get().toggle(bankName(), chosen()), null);
    }

    private void settingsWidgets() {
        int start = left + 10, each = (panel - 28) / 3;
        int middle = start + each + 4, end = middle + each + 4;
        device = label(start, 52, each * 2 + 4, () -> Component.empty(), this::nextInput, "device_help");
        label(end, 52, each, () -> Component.translatable("piano.refresh"), midi::scan, null);
        label(start, 75, each, () -> preferredChannel < 0 ? Component.translatable("piano.all_channels")
                : Component.translatable("piano.channel", preferredChannel + 1), () -> {
            preferredChannel = preferredChannel == 15 ? -1 : preferredChannel + 1;
            requestMidiReset();
        }, null);
        label(middle, 75, each, () -> t("stand_toggle", flag("Stand") ? "●" : "○"),
                () -> action(Action.STAND, flag("Stand") ? 0 : 1), null);
        label(end, 75, each, () -> t("rack_toggle", flag("Rack") ? "●" : "○"),
                () -> action(Action.RACK, flag("Rack") ? 0 : 1), null);
        label(start, 98, each, () -> t("layer_toggle", settings().layered ? "●" : "○"),
                () -> action(Action.LAYER_ON, settings().layered ? 0 : 1), null);
        label(middle, 98, each, () -> t("split_toggle", settings().split ? "●" : "○"),
                () -> action(Action.SPLIT_ON, settings().split ? 0 : 1), null);
        slider(end, 98, each, "split_key", 22, 108, () -> settings().splitKey, n -> action(Action.SPLIT_KEY, n));
        slider(start, 121, each, "transpose", -36, 36, () -> settings().transpose, n -> action(Action.TRANSPOSE, n));
        slider(middle, 121, each, "touch", 0, 4, () -> settings().touch, n -> action(Action.TOUCH, n));
        label(end, 121, each, () -> t("poly", settings().polyphony),
                () -> action(Action.POLYPHONY, settings().polyphony >= 256 ? 32 : (settings().polyphony / 32 + 1) * 32), null);
        slider(start, 144, each, "reverb", 0, 127, () -> settings().reverb, n -> action(Action.REVERB, n));
        slider(middle, 144, each, "chorus", 0, 127, () -> settings().chorus, n -> action(Action.CHORUS, n));
        label(end, 144, each, () -> Component.translatable("piano.panic"), this::panic, null);
        slider(start, 167, each, "bpm", 20, 300, () -> settings().bpm, n -> action(Action.BPM, n));
        slider(middle, 167, each, "beats", 1, 12, () -> settings().beats, n -> action(Action.BEATS, n));
        label(end, 167, each, () -> t("sf2", settings().font), this::nextFont, "font_help");
    }

    private Button label(int x, int y, int w, Supplier<Component> message, Runnable press, String hint) {
        var b = addRenderableWidget(Button.builder(fit(message.get(), w - 8), ignored -> press.run())
                .bounds(x, top + y, w, 18).build());
        if (hint != null) b.setTooltip(Tooltip.create(t(hint)));
        labels.add(new Label(b, message));
        return b;
    }

    private void slider(int x, int y, int w, String key, int min, int max, IntSupplier current, IntConsumer apply) {
        sliders.add(addRenderableWidget(new SettingSlider(x, top + y, w, key, min, max, current, apply)));
    }

    private static Component t(String key, Object... args) { return Component.translatable("keyboard." + key, args); }
    private Component fit(Component text, int w) {
        return font.width(text) <= w ? text : Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, w - 7)) + "…");
    }
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

    private int rows() { return Math.max(1, (tall - 131) / 15); }
    private void page(int direction) { scrollTones(direction * rows(), tones().size()); }
    private void scrollTones(int amount, int count) { toneScroll = Math.clamp(toneScroll + amount, 0, Math.max(0, count - rows())); }
    private void pick(Tone tone) {
        KeyboardToneHistory.get().used(bankName(), tone);
        client.command(instrument, role == 1 ? Action.LAYER : role == 2 ? Action.LOWER : Action.MAIN, tone.bank(), tone.program(), "");
    }

    @Override public void tick() {
        if (++ticks % 40 == 0) midi.scan();
        for (var label : labels) label.button.setMessage(fit(label.text.get(), label.button.getWidth() - 8));
        sliders.forEach(SettingSlider::sync);
        if (device != null) {
            var status = midi.status();
            var name = status.inputs().stream().filter(input -> input.id().equals(status.selected())).map(MidiPort.Input::name).findFirst();
            device.setMessage(fit(name.isPresent() && status.message().equals("piano.midi_connected")
                    ? Component.literal(name.get()) : Component.translatable(status.message()), device.getWidth() - 8));
        }
        if (!Minecraft.getInstance().isWindowActive()) releaseComputer();
    }

    private void requestMidiReset() {
        pending.clear();
        resetMidi.set(true);
        postDrain();
    }
    private void postDrain() {
        if (drainPosted.compareAndSet(false, true)) Minecraft.getInstance().execute(this::drainMidi);
    }
    private void drainMidi() {
        drainPosted.set(false);
        if (Minecraft.getInstance().screen != this) {
            pending.clear();
            return;
        }
        var batch = new ArrayList<KeyboardMidi>();
        if (resetMidi.getAndSet(false)) {
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
        while (batch.size() < 350) {
            var event = pending.poll();
            if (event == null) break;
            if (event.channel() == 0) {
                if (event.command() == ON && event.value() != 0) hardwareHeld[event.key()] = true;
                else if (event.command() == OFF || event.command() == ON) {
                    hardwareHeld[event.key()] = false;
                    if (pcHolds(event.key())) continue;
                } else if (event.command() == CONTROL) {
                    int pedal = pedalIndex(event.key());
                    if (pedal >= 0) {
                        hardwarePedals[pedal] = event.value();
                        event = cc(event.key(), effectivePedal(pedal));
                    }
                    if (event.key() == SILENCE || event.key() == NOTES_OFF) Arrays.fill(hardwareHeld, false);
                    if (event.key() == RESET) Arrays.fill(hardwarePedals, 0);
                }
            }
            batch.add(event);
        }
        client.send(instrument, batch);
        if (!pending.isEmpty()) postDrain();
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
    private void panic() {
        computer.clear();
        mouseDown = pedalLatched = false;
        mouseNote = -1;
        Arrays.fill(pcPedals, false);
        Arrays.fill(hardwareHeld, false);
        Arrays.fill(hardwarePedals, 0);
        pending.clear();
        action(Action.PANIC);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
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

    @Override public boolean keyReleased(int key, int scan, int mods) {
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
    private void releaseComputer() {
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

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && tab == Tab.PLAY && keyAt(x, y) >= 0) {
            mouseDown = true;
            playMouse(x, y);
            return true;
        }
        if (button == 0 && tab == Tab.TONES && x >= left + 10 && x < left + panel - 10 && y >= top + 100) {
            int row = (int) (y - top - 100) / 15;
            var tones = tones();
            scrollTones(0, tones.size());
            if (row < rows() && toneScroll + row < tones.size()) {
                pick(tones.get(toneScroll + row).tone());
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (mouseDown && button == 0) {
            playMouse(x, y);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
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
        if (vertical != 0 && tab == Tab.TONES) {
            scrollTones(vertical > 0 ? -3 : 3, tones().size());
            return true;
        }
        if (vertical != 0 && tab == Tab.PLAY && y >= keyTop - 8 && y < keyTop + keyHeight + 13) {
            firstWhite = Math.clamp(firstWhite + (vertical > 0 ? -4 : 4), 0, TOTAL_WHITE - whites());
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void playMouse(double x, double y) {
        int next = keyAt(x, y);
        if (next == mouseNote) return;
        int previous = mouseNote;
        boolean held = next >= 0 && (pcHolds(next) || hardwareHeld[next]);
        mouseNote = next;
        release(previous);
        if (next >= 0 && !held) note(KeyboardMidi.note(next,
                Math.clamp(20 + (int) (107 * (y - keyTop) / (black(next) ? blackHeight() : keyHeight)), 20, 127)));
    }

    private void zoom(int amount) {
        int center = firstWhite + whites() / 2;
        zoom = amount;
        firstWhite = Math.clamp(center - whites() / 2, 0, TOTAL_WHITE - whites());
    }
    private int whites() { return TOTAL_WHITE / zoom; }
    private int xAt(int white) { return keyLeft + Math.floorDiv((white - firstWhite) * keyWidth, whites()); }
    private int blackWidth() { return Math.max(2, keyWidth * 6 / (whites() * 10)); }
    private int blackHeight() { return keyHeight * 62 / 100; }
    private static boolean black(int note) { return KeyboardLayout.PX.keys.get(note - KeyboardLayout.FIRST).black(); }

    private int keyAt(double x, double y) {
        if (x < keyLeft || x >= keyLeft + keyWidth || y < keyTop || y >= keyTop + keyHeight) return -1;
        int white = 0, result = -1;
        for (int note = KeyboardLayout.FIRST; note <= KeyboardLayout.LAST; note++) {
            if (black(note)) {
                if (y < keyTop + blackHeight() && Math.abs(x - xAt(white)) <= blackWidth() / 2.0) return note;
            } else {
                int a = xAt(white++);
                if (x >= a && x < xAt(white)) result = note;
            }
        }
        return result;
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        super.renderBackground(g, mx, my, partial);
        g.fill(left - 1, top - 1, left + panel + 1, top + tall + 1, 0xFF425463);
        g.fill(left, top, left + panel, top + tall, DARK);
        g.fill(left, top, left + panel, top + 2, flag("Powered") ? ACCENT : 0xFF526473);
        g.drawString(font, fit(title, panel / 2 + 24), left + 10, top + 10, INK, false);
        int energy = instrument.view.getInt("Energy");
        var power = flag("Powered") ? t("energy", Math.max(1, (int) (energy * 100L / 2880000)))
                : t(flag("Switch") ? "needs_power" : "power_off");
        g.drawString(font, power, left + panel - 10 - font.width(power), top + 10, flag("Powered") ? ACCENT : MUTED, false);
        if (tab == Tab.PLAY) drawKeys(g, mx, my);
        if (tab == Tab.TONES) drawTones(g, mx, my);
        if (tab == Tab.SETTINGS) {
            var bank = bank();
            Component status = bank == null ? t("font_loading") : bank.name().equals(settings().font)
                    ? t("patch_count", bank.patches().size()) : t("font_fallback");
            g.drawString(font, fit(status, panel - 20), left + 10, top + tall - 16, MUTED, false);
        }
    }

    private void drawKeys(GuiGraphics g, int mx, int my) {
        int hover = keyAt(mx, my);
        g.enableScissor(keyLeft, keyTop, keyLeft + keyWidth, keyTop + keyHeight);
        int white = 0;
        for (int n = KeyboardLayout.FIRST; n <= KeyboardLayout.LAST; n++) {
            if (black(n)) continue;
            int a = xAt(white), b = xAt(++white);
            boolean held = instrument.keys.pressed(n) || pcHolds(n);
            int tone = held ? ACCENT : hover == n ? 0xFFFFFFFF : 0xFFEBF2F5;
            g.fillGradient(a, keyTop, Math.max(a + 1, b - 1), keyTop + keyHeight, tone, held ? 0xFF4FB5AC : 0xFFB0BDC7);
            if (n % 12 == 0) {
                var octave = "C" + (n / 12 - 1);
                int labelWidth = font.width(octave);
                float scale = .72f;
                if (labelWidth * scale <= b - a - 1) {
                    var pose = g.pose();
                    pose.pushPose();
                    pose.translate((a + b - labelWidth * scale) / 2, keyTop + keyHeight - 10, 0);
                    pose.scale(scale, scale, 1);
                    g.drawString(font, octave, 0, 0, 0xFF293B48, false);
                    pose.popPose();
                }
            }
        }
        white = 0;
        for (int n = KeyboardLayout.FIRST; n <= KeyboardLayout.LAST; n++) {
            if (!black(n)) {
                white++;
                continue;
            }
            int a = xAt(white) - blackWidth() / 2;
            boolean held = instrument.keys.pressed(n) || pcHolds(n);
            g.fillGradient(a, keyTop, a + blackWidth(), keyTop + blackHeight(),
                    held ? 0xFF58BAAB : hover == n ? 0xFF667D8B : 0xFF253642, held ? 0xFF247C74 : 0xFF0A1018);
        }
        g.disableScissor();
        int track = keyTop + keyHeight + 4;
        g.fill(keyLeft, track, keyLeft + keyWidth, track + 2, 0xFF324555);
        g.fill(keyLeft + firstWhite * keyWidth / TOTAL_WHITE, track,
                keyLeft + (firstWhite + whites()) * keyWidth / TOTAL_WHITE, track + 2, ACCENT);
        g.drawString(font, fit(t("keys_help", base / 12 - 1), panel - 20), keyLeft, track + 8, MUTED, false);
        if (flag("Recording") || flag("Playing")) {
            var status = t(flag("Recording") ? "recording" : "playing", instrument.view.getInt("Events"));
            g.drawString(font, fit(status, panel - 20), keyLeft, track + 20, flag("Recording") ? 0xFFFF8588 : ACCENT, false);
        }
    }

    private void drawTones(GuiGraphics g, int mx, int my) {
        var matching = tones();
        scrollTones(0, matching.size());
        if (matching.isEmpty()) {
            g.drawString(font, t(bank() == null ? "font_loading" : "no_tones"), left + 10, top + 108, MUTED, false);
            return;
        }
        var history = KeyboardToneHistory.get();
        for (int i = 0; i < rows() && toneScroll + i < matching.size(); i++) {
            var patch = matching.get(toneScroll + i);
            int y = top + 100 + i * 15, a = left + 10, b = left + panel - 10;
            boolean selected = patch.tone().equals(chosen());
            boolean hover = mx >= a && mx < b && my >= y && my < y + 15;
            g.fill(a, y, b, y + 14, selected ? 0xFF244C50 : hover ? 0xFF344958 : 0xFF1C2834);
            String number = "%03d / %03d".formatted(patch.tone().bank(), patch.tone().program());
            int numberWidth = font.width(number);
            String name = (history.favorite(bankName(), patch.tone()) ? "★ " : "") + patch.name();
            g.drawString(font, fit(Component.literal(name), b - a - numberWidth - 16), a + 5, y + 3, selected ? ACCENT : INK, false);
            g.drawString(font, number, b - 5 - numberWidth, y + 3, MUTED, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void removed() {
        preferred = midi.preference();
        midi.close();
        pending.clear();
        action(Action.CLOSE);
        super.removed();
    }

    private final class SettingSlider extends AbstractSliderButton {
        private final String key;
        private final int min, max;
        private final IntSupplier current;
        private final IntConsumer apply;
        private int edited = -20;
        private boolean dragging;

        SettingSlider(int x, int y, int w, String key, int min, int max, IntSupplier current, IntConsumer apply) {
            super(x, y, w, 18, Component.empty(), (current.getAsInt() - min) / (double) (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.current = current;
            this.apply = apply;
            updateMessage();
        }

        private int number() { return min + (int) Math.round(value * (max - min)); }

        void sync() {
            int actual = current.getAsInt();
            if (!dragging && ticks - edited >= 3 && number() != actual) {
                value = (actual - min) / (double) (max - min);
                updateMessage();
            }
        }

        @Override protected void updateMessage() { setMessage(fit(t(key, number()), getWidth() - 8)); }
        @Override protected void applyValue() {
            edited = ticks;
            apply.accept(number());
        }
        @Override public void onClick(double x, double y) {
            dragging = true;
            super.onClick(x, y);
        }
        @Override public void onRelease(double x, double y) {
            dragging = false;
            super.onRelease(x, y);
        }
    }
}
