package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.lwjgl.glfw.GLFW;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;

final class PianoScreen extends Screen {
    private static final int[] PC_KEYS = {
        GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_E,
        GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_G,
        GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_J,
        GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_SEMICOLON
    };
    private static final int[] PEDALS = {SOFT, SOSTENUTO, DAMPER};
    private static final String[] PEDAL_NAMES = {"piano.soft", "piano.sostenuto", "piano.damper"};
    private static String preferred;
    private static int preferredChannel = -1;
    private final PianoClient client;
    private final PianoClient.Instrument instrument;
    private final MidiKeyboard midi;
    private final ArrayBlockingQueue<List<PianoEvent>> pending = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Map<Integer, Integer> pcNotes = new HashMap<>();
    private final boolean[] midiHeld = new boolean[KEYS];
    private final int[] midiPedal = new int[3], pcPedal = new int[3];
    private int left, top, panel, keyLeft, keyTop, keyWidth;
    private static final int KEY_HEIGHT = 61, BLACK_HEIGHT = 37;
    private int pcRoot = 60, mouse = -1, ticks;
    private Button deviceButton;

    PianoScreen(PianoClient client, PianoClient.Instrument instrument) {
        super(Component.translatable("block.create_media_systems.grand_piano"));
        this.client = client;
        this.instrument = instrument;
        midi = new MidiKeyboard(preferred, this::queueMidi);
        if (preferredChannel >= 0) midi.channel(preferredChannel);
    }

    PianoClient.Instrument instrument() { return instrument; }

    @Override protected void init() {
        panel = Math.min(750, width - 20);
        left = (width - panel) / 2;
        top = (height - 178) / 2;
        keyLeft = left + 10;
        keyTop = top + 75;
        keyWidth = panel - 20;
        int controls = panel - 20;
        deviceButton = addRenderableWidget(Button.builder(Component.translatable("piano.midi_none"), b -> nextInput())
                .bounds(left + 10, top + 26, controls - 63, 19).build());
        addRenderableWidget(Button.builder(Component.translatable("piano.refresh"), b -> midi.scan())
                .bounds(left + panel - 69, top + 26, 59, 19).build());
        addRenderableWidget(Button.builder(channel(), b -> {
            preferredChannel = preferredChannel == 15 ? -1 : preferredChannel + 1;
            midi.channel(preferredChannel);
            b.setMessage(channel());
        }).bounds(left + 10, top + 49, 61, 19).build());
        addRenderableWidget(Button.builder(Component.translatable("piano.lid"), b -> client.command(instrument, Action.ROOF))
                .bounds(left + 75, top + 49, 53, 19).build());
        addRenderableWidget(Button.builder(Component.translatable("piano.keyboard_lid"), b -> client.command(instrument, Action.KEYBOARD_LID))
                .bounds(left + 132, top + 49, 66, 19).build());
        addRenderableWidget(Button.builder(Component.translatable("piano.panic"), b -> midi.panic())
                .bounds(left + panel - 69, top + 49, 59, 19).build());
        midi.scan();
    }

    private Component channel() {
        return preferredChannel < 0 ? Component.translatable("piano.all_channels")
                : Component.translatable("piano.channel", preferredChannel + 1);
    }

    private void nextInput() {
        var status = midi.status();
        var inputs = status.inputs();
        if (inputs.isEmpty()) {
            midi.scan();
            return;
        }
        int next = 0;
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).id().equals(status.selected())) next = i + 1;
        midi.select(next >= inputs.size() ? "" : inputs.get(next).id());
    }

    @Override public void tick() {
        if (++ticks % 40 == 0) midi.scan();
        var status = midi.status();
        String name = status.inputs().stream().filter(input -> input.id().equals(status.selected()))
                .map(MidiKeyboard.Input::name).findFirst().orElse(null);
        deviceButton.setMessage(name == null || !status.message().equals("piano.midi_connected") ? Component.translatable(status.message())
                : Component.literal(font.plainSubstrByWidth(name, deviceButton.getWidth() - 12)));
        if (!Minecraft.getInstance().isWindowActive() && (!pcNotes.isEmpty() || mouse >= 0 || Arrays.stream(pcPedal).anyMatch(p -> p != 0))) {
            releaseComputer();
        }
    }

    private void queueMidi(List<PianoEvent> events) {
        if (!pending.offer(events)) {
            pending.clear();
            pending.offer(List.of(control(ALL_SOUND_OFF, 0), control(RESET_CONTROLLERS, 0)));
        }
        if (scheduled.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(this::drain);
        }
    }

    private void drain() {
        scheduled.set(false);
        if (Minecraft.getInstance().screen != this) {
            pending.clear();
            return;
        }
        var events = new ArrayList<PianoEvent>();
        for (var batch = pending.poll(); batch != null; batch = pending.poll()) {
            for (PianoEvent event : batch) {
                int note = event.number();
                switch (event.kind()) {
                    case ON -> {
                        midiHeld[note - FIRST_NOTE] = true;
                        events.add(event);
                    }
                    case OFF -> {
                        midiHeld[note - FIRST_NOTE] = false;
                        if (!computerHolds(note)) events.add(event);
                    }
                    case CONTROL -> {
                        int pedal = pedalIndex(note);
                        if (pedal >= 0) {
                            midiPedal[pedal] = event.value();
                            events.add(control(note, Math.max(pcPedal[pedal], midiPedal[pedal])));
                        } else {
                            if (note == ALL_SOUND_OFF) {
                                Arrays.fill(midiHeld, false);
                                pcNotes.clear();
                                mouse = -1;
                            }
                            if (note == RESET_CONTROLLERS) {
                                Arrays.fill(midiPedal, 0);
                                Arrays.fill(pcPedal, 0);
                            }
                            events.add(event);
                        }
                    }
                }
            }
        }
        client.play(instrument, events);
    }

    private boolean computerHolds(int note) { return mouse == note || pcNotes.containsValue(note); }

    private static int pedalIndex(int controller) {
        return switch (controller) {
            case SOFT -> 0;
            case SOSTENUTO -> 1;
            case DAMPER -> 2;
            default -> -1;
        };
    }

    private static int pedalKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 1;
            case GLFW.GLFW_KEY_SPACE -> 2;
            default -> -1;
        };
    }

    private void play(PianoEvent event) { client.play(instrument, List.of(event)); }

    private void releaseComputer(int note) {
        if (isNote(note) && !midiHeld[note - FIRST_NOTE] && !computerHolds(note)) play(release(note, 58));
    }

    private void releaseComputer() {
        var held = new ArrayList<>(pcNotes.values());
        if (mouse >= 0) held.add(mouse);
        mouse = -1;
        pcNotes.clear();
        held.forEach(this::releaseComputer);
        for (int i = 0; i < PEDALS.length; i++) if (pcPedal[i] != 0) pedal(i, 0);
    }

    private void pedal(int pedal, int value) {
        if (pcPedal[pedal] == value) return;
        pcPedal[pedal] = value;
        play(control(PEDALS[pedal], Math.max(value, midiPedal[pedal])));
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        int pedal = pedalKey(key);
        if (pedal >= 0) {
            pedal(pedal, 127);
            return true;
        }
        if (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_X) {
            pcRoot = Math.clamp(pcRoot + (key == GLFW.GLFW_KEY_Z ? -12 : 12), 24, 84);
            return true;
        }
        for (int i = 0; i < PC_KEYS.length; i++) {
            if (PC_KEYS[i] == key) {
                if (!pcNotes.containsKey(key)) {
                    int note = pcRoot + i;
                    pcNotes.put(key, note);
                    play(press(note, 86));
                }
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        int pedal = pedalKey(key);
        if (pedal >= 0) {
            pedal(pedal, 0);
            return true;
        }
        Integer note = pcNotes.remove(key);
        if (note != null) {
            releaseComputer(note);
            return true;
        }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        int note = noteAt(x, y);
        if (button == 0 && note >= 0) {
            mouse = note;
            play(press(note, 90));
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && mouse >= 0) {
            int note = noteAt(x, y);
            if (note >= 0 && note != mouse) {
                int previous = mouse;
                mouse = note;
                releaseComputer(previous);
                play(press(note, 84));
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && mouse >= 0) {
            int previous = mouse;
            mouse = -1;
            releaseComputer(previous);
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    private int xAt(int white) { return keyLeft + white * keyWidth / 52; }
    private int blackWidth() { return Math.max(2, keyWidth * 62 / 5200); }

    private int noteAt(double x, double y) {
        if (x < keyLeft || x >= keyLeft + keyWidth || y < keyTop || y >= keyTop + KEY_HEIGHT) return -1;
        int white = 0, found = -1;
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            if (black(note)) {
                int center = xAt(white);
                if (y < keyTop + BLACK_HEIGHT && Math.abs(x - center) <= blackWidth() / 2d) return note;
            } else {
                if (x >= xAt(white) && x < xAt(white + 1)) found = note;
                white++;
            }
        }
        return found;
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        super.renderBackground(g, mx, my, partial);
        g.fill(left - 1, top - 1, left + panel + 1, top + 179, 0xFF4D3D2D);
        g.fill(left, top, left + panel, top + 178, 0xF2181A21);
        g.fill(left, top, left + panel, top + 2, 0xFFCFA765);
        g.drawString(font, title, left + 10, top + 10, 0xFFF4E7CA, false);
        String status = PianoAudio.INSTANCE.status();
        Component detail = Component.translatable(status);
        int statusWidth = font.width(detail);
        if (statusWidth + font.width(title) + 30 < panel) {
            g.drawString(font, detail, left + panel - statusWidth - 10, top + 10, 0xFFC3AD8F, false);
        }

        int white = 0, hover = noteAt(mx, my);
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            if (black(note)) continue;
            int a = xAt(white), b = xAt(++white) - 1;
            int fill = instrument.keys.pressed(note) ? 0xFFE4B45D : note == hover ? 0xFFFFF5CE : 0xFFF3F0E5;
            g.fillGradient(a, keyTop, Math.max(a + 1, b), keyTop + KEY_HEIGHT, fill, 0xFFCAC2B1);
            if (note % 12 == 0 && keyWidth >= 330) {
                String octave = "C" + (note / 12 - 1);
                g.drawCenteredString(font, octave, (a + b) / 2, keyTop + KEY_HEIGHT - 12, 0xFF433925);
            }
        }
        white = 0;
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            if (!black(note)) {
                white++;
                continue;
            }
            int a = xAt(white) - blackWidth() / 2;
            int fill = instrument.keys.pressed(note) ? 0xFFC18A38 : note == hover ? 0xFF54545F : 0xFF26272E;
            g.fillGradient(a, keyTop, a + blackWidth(), keyTop + BLACK_HEIGHT, fill, 0xFF0A0A0D);
        }
        int bar = (panel - 24) / 3;
        for (int i = 0; i < PEDALS.length; i++) {
            int a = keyLeft + i * bar;
            int position = instrument.keys.pedal(PEDALS[i]);
            g.fill(a, top + 144, a + bar - 4, top + 146, 0xFF43434D);
            g.fill(a, top + 144, a + (bar - 4) * position / 127, top + 146, 0xFFD5A956);
            g.drawString(font, Component.translatable(PEDAL_NAMES[i]), a, top + 150, 0xFFC3AD8F, false);
        }
        String controls = Component.translatable("piano.pc_controls", pcRoot / 12 - 1).getString();
        g.drawString(font, font.plainSubstrByWidth(controls, panel - 20), keyLeft, top + 165, 0xFFADADB8, false);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void removed() {
        preferred = midi.preference();
        midi.close();
        pending.clear();
        client.command(instrument, Action.CLOSE);
        super.removed();
    }
}
