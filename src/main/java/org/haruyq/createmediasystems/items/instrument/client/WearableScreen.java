package org.haruyq.createmediasystems.items.instrument.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.midi.ShortMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.haruyq.createmediasystems.client.KeysScreen;
import org.haruyq.createmediasystems.client.MidiPort;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.instrument.WearablePackets;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.lwjgl.glfw.GLFW;

import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;

final class WearableScreen extends KeysScreen {
    private static final int[] COMPUTER = {
        GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V,
        GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_M,
        GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R,
        GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_U,
        GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_P
    };
    private static String preferred;
    private final Player player;
    private final WearableClient.View view;
    private final int session;
    private final Kind kind;
    private final InteractionHand hand;
    private final MidiPort midi;
    private final Map<Integer, Integer> typed = new HashMap<>();
    private final Map<Integer, Integer> midiNotes = new HashMap<>();
    private final ArrayBlockingQueue<ShortMessage> input = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean midiDispatch = new AtomicBoolean();
    private volatile boolean resetMidi;
    private int octaves, mouseNote = -1, midiAir, midiSustain, sentAir, sentSustain, bendValue = CENTER_BEND;
    private boolean space, bendDragged;

    WearableScreen(Player player, WearableClient.View view, int session, Kind kind, InteractionHand hand) {
        super(player.getItemInHand(hand).getHoverName(), 4.05f, .9f, kind.first, kind.keys);
        this.player = player;
        this.view = view;
        this.session = session;
        this.kind = kind;
        this.hand = hand;
        if (kind == Kind.KEYTAR) {
            lamp = 0xFF79E6D5;
            lit = new int[] {0xFFADEEE3, 0xFF6AD4C2};
            body = new int[] {0xFA303539, 0xFA181C20};
        } else {
            lamp = 0xFFA0D6FF;
            lit = new int[] {0xFFC4E6FF, 0xFF86C2F1};
            body = new int[] {0xFA284764, 0xFA192F49};
        }
        midi = new MidiPort(preferred, message -> {
            if (!input.offer(message)) resetMidi = true;
            scheduleMidi();
        }, () -> {
            resetMidi = true;
            scheduleMidi();
        });
        midi.scan();
    }

    private boolean keytar() { return kind == Kind.KEYTAR; }

    @Override protected void init() {
        super.init();
        if (hidden) return;
        float each = whiteKeyCount() / 8f;
        if (keytar()) {
            for (int index = 0; index < 8; index++) {
                int tone = index;
                button(index * each, 1.55f, (index + 1) * each - .12f, 2.72f,
                        () -> tr("tone." + tone), () -> send(tone(tone))).lit(() -> view.notes.tone() == tone);
            }
            slider(0, 2.86f, whiteKeyCount() * .43f, 4, value -> tr("bend", value - CENTER_BEND),
                    0, 16383, () -> bendValue, value -> {
                        bendDragged = true;
                        bendTo(value);
                    });
        } else {
            button(0, 1.55f, whiteKeyCount() * .56f, 2.85f,
                    () -> tr(sentAir > 0 ? "breath_active" : "breath_hint"), () -> {}).lit(() -> sentAir > 0);
        }
        float left = whiteKeyCount() * .60f;
        button(left, 2.86f, whiteKeyCount() - .1f, 4, this::midiName, this::nextMidi);
    }

    @Override protected void draw(GuiGraphics g, double x, double y) {
        var stack = player.getItemInHand(hand);
        String status = keytar() ? tr("charge", Math.round(ChargeableItem.charge(stack) * 100) + "%").getString() : "";
        String hints = tr("keys", tr(keytar() ? "keys.damper" : "keys.breath")).getString();
        drawHeader(g, title.getString(), status, view.powered(player) ? lamp : faint, hints, faint);
        drawKeys(g, note -> view.notes.kind == kind && view.notes.velocity(note) != 0);
    }

    @Override public void tick() {
        super.tick();
        if (!player.isAlive() || player.isSpectator() || view.session != session || (keytar()
                ? !player.getItemInHand(hand).is(WearableInstruments.KEYTAR.get())
                : WearableInstruments.ready(player) || !player.getItemInHand(hand).is(WearableInstruments.MELODICA.get()))) {
            onClose();
            return;
        }
        if (ticks % 40 == 0) midi.scan();
    }

    private void scheduleMidi() {
        if (midiDispatch.compareAndSet(false, true)) Minecraft.getInstance().execute(this::drainMidi);
    }

    private void drainMidi() {
        midiDispatch.set(false);
        if (Minecraft.getInstance().screen != this) return;
        if (resetMidi) {
            resetMidi = false;
            clearMidi();
        }
        for (ShortMessage message; (message = input.poll()) != null;) midiMessage(message);
    }

    private void clearMidi() {
        input.clear();
        var keys = Set.copyOf(midiNotes.values());
        midiNotes.clear();
        for (int note : keys) release(note);
        midiAir = midiSustain = 0;
        pedals();
        if (keytar() && bendValue != CENTER_BEND) bendTo(CENTER_BEND);
    }

    private Component midiName() {
        var status = midi.status();
        return status.inputs().stream().filter(port -> port.id().equals(status.selected())).findFirst()
                .map(port -> tr("midi", port.name())).orElseGet(() -> Component.translatable(status.message().replace("keyboard.", "piano.")));
    }

    private void nextMidi() {
        var status = midi.status();
        var ports = status.inputs();
        int next = 0;
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).id().equals(status.selected())) {
                next = i + 1;
                break;
            }
        }
        midi.select(next == ports.size() ? "" : ports.get(next).id());
    }

    private void midiMessage(ShortMessage message) {
        int number = message.getData1(), value = message.getData2();
        switch (message.getCommand()) {
            case ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF -> {
                int key = message.getChannel() * 128 + number;
                if (message.getCommand() == ShortMessage.NOTE_OFF || value == 0) {
                    Integer note = midiNotes.remove(key);
                    if (note != null) release(note);
                } else if (number >= kind.first && number <= kind.last() && midiNotes.putIfAbsent(key, number) == null) {
                    press(number, value);
                }
            }
            case ShortMessage.CONTROL_CHANGE -> {
                if (!keytar() && (number == BREATH || number == 11 || number == SUSTAIN)) midiAir = value;
                else if (keytar() && number == SUSTAIN) midiSustain = value;
                else if (number == RELEASE_KEYS || number == 120 || number == 121) clearMidi();
                pedals();
            }
            case ShortMessage.PITCH_BEND -> { if (keytar()) bendTo(number | value << 7); }
            case ShortMessage.PROGRAM_CHANGE -> { if (keytar()) send(tone(number % 8)); }
        }
    }

    private void bendTo(int bend) {
        bendValue = bend;
        send(InstrumentState.bend(bend));
    }

    private void press(int note, int velocity) {
        if (view.notes.velocity(note) == 0) send(note(note, velocity));
    }

    private void release(int note) {
        if (mouseNote != note && !typed.containsValue(note) && !midiNotes.containsValue(note)) send(note(note, 0));
    }

    private void pedals() {
        if (keytar()) {
            int value = space ? 127 : midiSustain;
            if (value != sentSustain) {
                sentSustain = value;
                send(control(SUSTAIN, value));
            }
        } else {
            int value = space || mouseNote >= 0 ? 112 : midiAir;
            if (value != sentAir) {
                sentAir = value;
                send(control(BREATH, value));
            }
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            space = true;
            pedals();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            octaves = Mth.clamp(octaves + (key == GLFW.GLFW_KEY_LEFT ? -1 : 1), keytar() ? -2 : -1, keytar() ? 2 : 1);
            return true;
        }
        for (int i = 0; i < COMPUTER.length; i++) {
            if (key != COMPUTER[i]) continue;
            int note = (keytar() ? 48 : 60) + 12 * octaves + i;
            if (note >= kind.first && note <= kind.last() && typed.putIfAbsent(key, note) == null) press(note, 96);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            space = false;
            pedals();
            return true;
        }
        var note = typed.remove(key);
        if (note == null) return super.keyReleased(key, scan, modifiers);
        release(note);
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0) return false;
        mouseNote = keyAt(x * pixel, y * pixel);
        if (mouseNote < 0) return false;
        pedals();
        press(mouseNote, mouseVelocity(y));
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (mouseNote < 0) return super.mouseDragged(x, y, button, dx, dy);
        int next = keyAt(x * pixel, y * pixel);
        if (next >= 0 && next != mouseNote) {
            int previous = mouseNote;
            mouseNote = next;
            release(previous);
            press(next, mouseVelocity(y));
        }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (mouseNote != -1) {
            int note = mouseNote;
            mouseNote = -1;
            release(note);
            pedals();
            return true;
        }
        if (bendDragged) {
            bendDragged = false;
            bendTo(CENTER_BEND);
        }
        return super.mouseReleased(x, y, button);
    }

    private int mouseVelocity(double y) {
        return Mth.clamp(45 + (int) (82 * (y * pixel - keysY) / keysHeight), 1, 127);
    }

    private void send(int message) { WearableClient.input(view, session, message); }

    private static Component tr(String key, Object... args) { return Component.translatable("wearable." + key, args); }

    @Override public void removed() {
        preferred = midi.preference();
        midi.close();
        send(control(RELEASE_KEYS, 0));
        send(WearablePackets.CLOSE);
        super.removed();
    }
}
