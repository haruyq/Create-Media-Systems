package org.haruyq.createmediasystems.items.instrument.client;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
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
import org.haruyq.createmediasystems.items.instrument.InstrumentItem;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.instrument.WearablePackets;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.lwjgl.glfw.GLFW;
import net.neoforged.neoforge.network.PacketDistributor;

import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;

final class WearableScreen extends KeysScreen {
    private static final int[] COMPUTER = {
        GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V,
        GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_M,
        GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R,
        GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_U,
        GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_P
    };
    private static String preferred, preferredLine = "";
    private static int preferredChannel, gainDb;
    private static volatile boolean monitor = true;
    private final GuitarInput guitarInput;
    private final ArrayBlockingQueue<byte[]> guitarFrames = new ArrayBlockingQueue<>(5);
    private final AtomicBoolean guitarDispatch = new AtomicBoolean();
    private List<GuitarInput.Device> devices = List.of();
    private int device = -1, channel;
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
        super(player.getItemInHand(hand).getHoverName(), kind == Kind.STRATOCASTER ? 6.65f : 4.05f, .9f, kind.first, kind.keys);
        this.player = player;
        this.view = view;
        this.session = session;
        this.kind = kind;
        this.hand = hand;
        if (kind == Kind.MELODICA) hidden = true;
        if (kind.wind()) sentAir = midiAir = 127;
        if (kind == Kind.STRATOCASTER || kind == Kind.TRUMPET) {
            lamp = 0xFFFFCE78;
            lit = new int[] {0xFFFFE2AA, 0xFFE3AA58};
            body = new int[] {0xFA383336, 0xFA241E20};
        } else if (kind == Kind.KEYTAR) {
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
        if (kind == Kind.STRATOCASTER) {
            var sound = view.audio.guitar();
            guitarInput = new GuitarInput((pcm, frame) -> {
                if (monitor || pcm.length == 0) sound.offer(pcm, true);
                if (!guitarFrames.offer(frame)) { guitarFrames.poll(); guitarFrames.offer(frame); }
                if (guitarDispatch.compareAndSet(false, true)) Minecraft.getInstance().execute(this::drainGuitar);
            });
            scanGuitars();
        } else guitarInput = null;
    }

    private void scanGuitars() {
        devices = GuitarInput.devices();
        device = devices.isEmpty() ? -1 : 0;
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).name().equals(preferredLine)) { device = i; break; }
        channel = device < 0 ? 0 : Math.clamp(preferredChannel, 0, devices.get(device).channels()-1);
    }

    private void drainGuitar() {
        guitarDispatch.set(false);
        if (Minecraft.getInstance().screen != this || view.session != session) { guitarFrames.clear(); return; }
        for (byte[] frame; (frame = guitarFrames.poll()) != null;) {
            WearableClient.showInput(view, frame);
            if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new WearablePackets.GuitarInput(session, frame));
        }
    }

    private void stopGuitar() {
        boolean started = guitarInput.status() != GuitarInput.Status.OFF;
        guitarInput.close();
        guitarFrames.clear();
        if (!started) return;
        view.audio.guitar().offer(new short[0], true);
        WearableClient.showInput(view, new byte[0]);
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new WearablePackets.GuitarInput(session, new byte[0]));
    }

    private void nextDevice() {
        stopGuitar();
        String previous = device >= 0 ? devices.get(device).name() : "";
        scanGuitars();
        if (devices.isEmpty()) return;
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).name().equals(previous)) {
            device = (i + 1) % devices.size();
            break;
        }
        preferredLine = devices.get(device).name();
        preferredChannel = channel = 0;
    }

    private void toggleGuitar() {
        if (guitarInput.status() == GuitarInput.Status.LIVE || guitarInput.status() == GuitarInput.Status.OPENING) {
            stopGuitar();
            return;
        }
        if (device < 0) scanGuitars();
        if (device >= 0) {
            guitarInput.gainDb(gainDb);
            guitarInput.start(devices.get(device), channel);
        }
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
        }
        if (kind == Kind.STRATOCASTER) {
            float width = whiteKeyCount();
            button(0, 4.16f, width*.54f, 5.31f, () -> device < 0 ? tr("input.none") : tr("input.device", devices.get(device).name()), this::nextDevice);
            button(width*.55f, 4.16f, width*.70f, 5.31f, () -> tr("input.channel", channel+1), () -> {
                if (device < 0) return;
                stopGuitar();
                preferredChannel = channel = (channel+1) % devices.get(device).channels();
            });
            button(width*.71f, 4.16f, width, 5.31f,
                    () -> tr("input."+guitarInput.status().name().toLowerCase(java.util.Locale.ROOT)), this::toggleGuitar)
                    .lit(() -> guitarInput.status() == GuitarInput.Status.LIVE);
            slider(0, 5.44f, width*.31f, 6.57f, v -> tr("input.gain",v), -18, 24, () -> gainDb, v -> {
                gainDb = v; guitarInput.gainDb(v);
            });
            button(width*.32f, 5.44f, width*.64f, 6.57f, () -> tr(monitor ? "input.monitor_on" : "input.monitor_off"), () -> {
                monitor = !monitor;
                if (!monitor) view.audio.guitar().offer(new short[0], true);
            }).lit(() -> monitor);
            button(width*.65f, 5.44f, width, 6.57f, () -> tr("amp."+view.notes.amp()), () ->
                    send(control(AMP, Math.round((view.notes.amp()+1)%3 * 127 / 2f))));
            button(0, 1.55f, width * .32f, 2.72f, () -> tr("pickup." + view.notes.pickup()),
                    () -> send(control(PICKUP, Math.round(((view.notes.pickup() + 1) % 5) * 127 / 4f))));
            slider(width * .33f, 1.55f, width * .53f, 2.72f, v -> tr("volume", v), 0, 127,
                    () -> view.notes.volume(), v -> send(control(VOLUME, v)));
            slider(width * .54f, 1.55f, width * .76f, 2.72f, v -> tr("tone_neck", v), 0, 127,
                    () -> view.notes.neckTone(), v -> send(control(TONE_NECK, v)));
            slider(width * .77f, 1.55f, width, 2.72f, v -> tr("tone_bridge", v), 0, 127,
                    () -> view.notes.bridgeTone(), v -> send(control(TONE_BRIDGE, v)));
        }
        if (kind.bendsPitch()) {
            slider(0, 2.86f, whiteKeyCount() * .43f, 4, value -> tr("bend", value - CENTER_BEND),
                    0, 16383, () -> bendValue, value -> {
                        bendDragged = true;
                        bendTo(value);
                    });
        } else if (kind.wind()) {
            slider(0, 2.86f, whiteKeyCount() * .50f, 4, value -> tr("air", value),
                    0, 127, () -> sentAir, value -> {
                        midiAir = value;
                        pedals();
                    });
        } else {
            slider(0, 1.55f, whiteKeyCount() * .76f, 2.85f, value -> tr("melodica.air", Math.round(value*100/127f)),
                    0, 127, () -> sentAir, value -> { midiAir = value; pedals(); });
        }
        float left = whiteKeyCount() * .60f;
        button(left, 2.86f, whiteKeyCount() - .1f, 4, this::midiName, this::nextMidi);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hidden || kind != Kind.MELODICA) return;
        StringBuilder notes = new StringBuilder();
        for (int note = kind.first; note <= kind.last(); note++) {
            if (view.notes.velocity(note) == 0) continue;
            if (!notes.isEmpty()) notes.append(' ');
            notes.append(NOTE_NAMES[note % 12]).append(note / 12 - 1);
        }
        Component air = sentAir == 0 ? tr("melodica.blow") : tr("melodica.air", Math.round(sentAir*100/127f));
        String readout = notes.isEmpty() ? air.getString() : air.getString() + "  ·  " + notes;
        graphics.drawString(font, readout, 6, height-14, sentAir > 0 ? 0xFFC4E6FF : 0xFFD1D3D6, true);
    }

    private static final String[] NOTE_NAMES = {"C","C♯","D","E♭","E","F","F♯","G","A♭","A","B♭","B"};

    @Override protected void draw(GuiGraphics g, double x, double y) {
        var stack = player.getItemInHand(hand);
        String status = keytar() ? tr("charge", Math.round(ChargeableItem.charge(stack) * 100) + "%").getString() : "";
        String hints = kind.model == null ? tr("keys", tr(keytar() ? "keys.damper" : "keys.breath")).getString() : "";
        String name = title.getString();
        if (kind.model != null) {
            int hide = Math.round((font.width(Component.translatable("create_media_systems.keys.hide")) + 12) * textScale);
            name = fit(name, stripWidth - hide - keyWidth);
            write(g, fit(tr("keys.notes").getString(), stripWidth - keyWidth), keysX + stripWidth * .5f,
                    keysY - (deck + felt) * .5f, textScale, faint, MIDDLE);
        }
        if (guitarInput != null && guitarInput.status() == GuitarInput.Status.LIVE) {
            int peak = guitarInput.peak();
            status = tr("input.level", peak == 0 ? "−∞" : Integer.toString((int)Math.round(20*Math.log10(peak/32768.0)))).getString();
            name = fit(name, stripWidth - Math.round((font.width(status)+75)*textScale));
        }
        drawHeader(g, name, status, view.powered(player) ? lamp : faint, hints, faint);
        drawKeys(g, note -> view.notes.kind == kind && (view.notes.velocity(note) != 0 || view.instrument != null && view.instrument.inputNote(note)));
    }

    @Override public void tick() {
        super.tick();
        if (!player.isAlive() || player.isSpectator() || view.session != session
                || !(player.getItemInHand(hand).getItem() instanceof InstrumentItem item)
                || item.kind != kind || !item.canPlay(player, hand)) {
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
        midiAir = kind.wind() ? 127 : 0;
        midiSustain = 0;
        pedals();
        if (kind.bendsPitch() && bendValue != CENTER_BEND) bendTo(CENTER_BEND);
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
                } else if (number >= kind.first && number <= kind.last()) {
                    Integer previous = midiNotes.put(key, number);
                    if (kind.model != null) send(note(number, value));
                    else if (previous == null) press(number, value);
                }
            }
            case ShortMessage.CONTROL_CHANGE -> {
                if ((kind.wind() || kind == Kind.MELODICA) && (number == BREATH || number == 11)
                        || kind == Kind.MELODICA && number == SUSTAIN) midiAir = value;
                else if (keytar() && number == SUSTAIN) midiSustain = value;
                else if (kind == Kind.STRATOCASTER && (number == VOLUME || number == PICKUP || number == TONE_NECK || number == TONE_BRIDGE)) {
                    send(control(number, value));
                } else if (number == RELEASE_KEYS || number == 120 || number == 121) clearMidi();
                pedals();
            }
            case ShortMessage.PITCH_BEND -> { if (kind.bendsPitch()) bendTo(number | value << 7); }
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
        } else if (kind == Kind.MELODICA || kind.wind()) {
            int value = kind == Kind.MELODICA && (space || mouseNote >= 0) ? 112 : midiAir;
            if (value != sentAir) {
                sentAir = value;
                send(control(BREATH, value));
            }
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE && kind.model == null) {
            space = true;
            pedals();
            return true;
        }
        int base = kind == Kind.STRATOCASTER ? 40 : keytar() ? 48 : 60;
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            int min = kind.model == null ? (keytar() ? -2 : -1) : Math.floorDiv(kind.first - base, 12);
            int max = kind.model == null ? (keytar() ? 2 : 1) : Math.max(0, Math.ceilDiv(kind.last() - base - COMPUTER.length + 1, 12));
            octaves = Mth.clamp(octaves + (key == GLFW.GLFW_KEY_LEFT ? -1 : 1), min, max);
            return true;
        }
        for (int i = 0; i < COMPUTER.length; i++) {
            if (key != COMPUTER[i]) continue;
            int note = base + 12 * octaves + i;
            if (note >= kind.first && note <= kind.last() && typed.putIfAbsent(key, note) == null) press(note, 96);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE && kind.model == null) {
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
        if (hidden && (kind.model != null || kind == Kind.MELODICA) && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            double sensitivity = minecraft.options.sensitivity().get() * .6 + .2;
            double scale = pixel * 8 * sensitivity * sensitivity * sensitivity;
            player.turn(dx * scale, dy * scale * (minecraft.options.invertYMouse().get() ? -1 : 1));
            return true;
        }
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
        if (guitarInput != null) stopGuitar();
        preferred = midi.preference();
        midi.close();
        send(control(RELEASE_KEYS, 0));
        send(WearablePackets.CLOSE);
        super.removed();
    }
}
