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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.haruyq.createmediasystems.client.KeysScreen;
import org.lwjgl.glfw.GLFW;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;

/**
 * Playing a grand piano, on a screen laid out as the Koland keyboard's is and coloured as the piano itself: black
 * lacquer, brass and gold, ivory and ebony keys under red felt, and behind them its fallboard with the maker's name.
 */
final class PianoScreen extends KeysScreen {
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
    /** The strip's one row of buttons, in white keys from its top. */
    private static final float ROW = 1.75f, ROW_END = 4.05f;
    /** The fallboard's texture, the maker's name on it (its left, top, width and height), and the lacquer around the name. */
    private static final ResourceLocation FALLBOARD = CreateMediaSystems.id("textures/block/piano/grand/grand_piano_fallboard.png");
    private static final int[] NAME = {428, 167, 169, 40};
    private static final int LACQUER = 0xFF1F2024;
    private int pcRoot = 60, mouse = -1;

    PianoScreen(PianoClient client, PianoClient.Instrument instrument) {
        super(Component.translatable("block.create_media_systems.grand_piano"), 4.6f, 2.4f);
        // The model's colours (PianoMesh).
        text = 0xFFF1EDE6;
        faint = 0xFF9A917E;
        ink = 0xFF1D1C21;
        lamp = 0xFFDDB972;
        bed = 0xFF0E0D10;
        feltColor = 0xFF701C32;
        body = new int[]{0xFA25242B, 0xFA18171C};
        button = new int[]{0xFF37363F, 0xFF27262D};
        held = new int[]{0xFF4D4B56, 0xFF3B3A42};
        lit = new int[]{0xFFE9CB8C, 0xFFBF9450};
        unlit = new int[]{0xFF151418, 0xFF0E0D10};
        white = new int[]{0xFFDED8CE, 0xFFF1EDE6};
        whiteDown = new int[]{0xFFD0A961, 0xFFEFD08E};
        black = new int[]{0xFF222126, 0xFF151419};
        blackTop = new int[]{0xFF3B3A42, 0xFF2D2C32};
        blackDown = new int[]{0xFF7A5C2B, 0xFF574018};
        blackTopDown = new int[]{0xFFDDB972, 0xFFBF9450};
        this.client = client;
        this.instrument = instrument;
        midi = new MidiKeyboard(preferred, this::queueMidi);
        if (preferredChannel >= 0) midi.channel(preferredChannel);
    }

    PianoClient.Instrument instrument() { return instrument; }

    @Override protected void init() {
        super.init();
        midi.scan();
        if (hidden) return;
        button(0.6f, ROW, 13, ROW_END, this::device, this::nextInput);
        button(13.4f, ROW, 17.4f, ROW_END, () -> Component.translatable("piano.refresh"), midi::scan);
        button(17.8f, ROW, 23, ROW_END, this::channel, () -> {
            preferredChannel = preferredChannel == 15 ? -1 : preferredChannel + 1;
            midi.channel(preferredChannel);
        });
        button(23.4f, ROW, 27.4f, ROW_END, () -> Component.translatable("piano.lid"), () -> client.command(instrument, Action.ROOF));
        button(27.8f, ROW, 32.8f, ROW_END, () -> Component.translatable("piano.keyboard_lid"),
                () -> client.command(instrument, Action.KEYBOARD_LID));
        button(33.2f, ROW, 37.2f, ROW_END, () -> Component.translatable("piano.panic"), midi::panic);
        for (int i = 0; i < PEDALS.length; i++) {
            int pedal = i;
            float left = 37.8f + i * 4.6f;
            slider(left, ROW, left + 4.2f, ROW_END, value -> Component.translatable(PEDAL_NAMES[pedal]), 0, 127,
                    () -> instrument.keys.pedal(PEDALS[pedal]), null);
        }
    }

    /** The MIDI input it listens to, or why it listens to none. */
    private Component device() {
        var status = midi.status();
        String name = status.inputs().stream().filter(input -> input.id().equals(status.selected()))
                .map(MidiKeyboard.Input::name).findFirst().orElse(null);
        return name == null || !status.message().equals("piano.midi_connected") ? Component.translatable(status.message())
                : Component.literal(name);
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
        super.tick();
        if (ticks % 40 == 0) midi.scan();
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
        if (super.mouseClicked(x, y, button)) return true;
        int note = keyAt(x * pixel, y * pixel);
        if (button == 0 && note >= 0) {
            mouse = note;
            play(press(note, 90));
            return true;
        }
        return false;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && mouse >= 0) {
            int note = keyAt(x * pixel, y * pixel);
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

    @Override protected void draw(GuiGraphics g, double x, double y) {
        drawHeader(g, title.getString(), Component.translatable(PianoAudio.INSTANCE.status()).getString(), lamp,
                Component.translatable("piano.pc_controls", pcRoot / 12 - 1).getString(), faint);
        // The fallboard behind the keys, the maker's name at its middle.
        int top = keysY - deck, bottom = keysY - felt, high = Math.round(keyWidth * 1.2f), wide = high * NAME[2] / NAME[3];
        int left = keysX + (stripWidth - wide) / 2, above = (top + bottom - high) / 2;
        g.fill(keysX, top, keysX + stripWidth, bottom, LACQUER);
        picture(g, FALLBOARD, left, above, left + wide, above + high, NAME[0], NAME[1], NAME[2], NAME[3], 1024, 256);
        drawKeys(g, instrument.keys::pressed);
    }

    @Override public void removed() {
        preferred = midi.preference();
        midi.close();
        pending.clear();
        client.command(instrument, Action.CLOSE);
        super.removed();
    }
}
