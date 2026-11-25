package org.haruyq.createmediasystems.blocks.stagecustom.client;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.midi.ShortMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomMidi;
import org.haruyq.createmediasystems.client.InstrumentScreen;
import org.haruyq.createmediasystems.client.KeysScreen;
import org.haruyq.createmediasystems.client.MidiPort;

final class StageCustomMidiScreen extends InstrumentScreen {
    private static final Drum[] DRUMS = Drum.values();
    private static final Voice[] CYMBALS = Voice.values();
    private static final String SHORTCUTS = "ZXCVB123456";
    private final StageCustomBlock.Entity kit;
    private final boolean complete;
    private final Set<Integer> heldKeys = new HashSet<>();
    private final ArrayBlockingQueue<StageCustomMidi.Hit> pending = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final long[] impacts = new long[DRUMS.length + CYMBALS.length];
    private final MidiPort midi;
    private MidiPort.Status displayed;
    private Button input;
    private int left, top, panelWidth, ticks;

    StageCustomMidiScreen(StageCustomBlock.Entity kit) {
        super(kit.getBlockState().getBlock().getName());
        this.kit = kit;
        complete = StageCustomBlock.isComplete(kit.getBlockState());
        midi = new MidiPort(this::receiveMidi, pending::clear);
        midi.scan();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(354, width - 16);
        left = (width - panelWidth) / 2;
        top = height - panelHeight() - 15;
        if (addToggle(left + panelWidth - 8, top + 5)) return;
        for (Drum drum : DRUMS) {
            addPad(drum.ordinal(), drum.id(), StageCustomMidi.note(drum));
        }
        if (complete) {
            for (Voice voice : CYMBALS) addPad(DRUMS.length + voice.ordinal(), voice.id(), StageCustomMidi.note(voice));
        }
        int inputTop = top + panelHeight() - 28;
        Component refresh = Component.translatable("keyboard.refresh");
        int refreshWidth = Math.max(54, font.width(refresh) + 12);
        input = addRenderableWidget(Button.builder(Component.empty(), button -> midi.next())
                .bounds(left + 8, inputTop, panelWidth - refreshWidth - 20, 20).build());
        addRenderableWidget(Button.builder(refresh, button -> midi.scan())
                .bounds(left + panelWidth - refreshWidth - 8, inputTop, refreshWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("keyboard.refresh_help"))).build());
        showStatus(midi.status());
    }

    private void addPad(int index, String id, int note) {
        int x = padX(index), y = padY(index);
        Component label = Component.translatable("stage_custom.pad." + id);
        var pad = Button.builder(label, button -> play(new StageCustomMidi.Hit(kit.getBlockPos(), note, 110)))
                .bounds(x, y, padWidth(index) - 3, 20).build();
        pad.setTooltip(Tooltip.create(Component.translatable("stage_custom.pad_hint", shortcut(index), note)));
        addRenderableWidget(pad);
    }

    private int panelHeight() { return complete ? 127 : 90; }
    private int padWidth(int i) { return (panelWidth - 16) / (i < DRUMS.length ? DRUMS.length : CYMBALS.length); }
    private int padX(int i) { return left + 8 + (i < DRUMS.length ? i : i - DRUMS.length) * padWidth(i); }
    private int padY(int i) { return top + (i < DRUMS.length ? 25 : 62); }
    private static String shortcut(int i) { return SHORTCUTS.substring(i, i + 1); }

    private int padIndex(int note) {
        Drum drum = StageCustomMidi.drum(note);
        if (drum != null) return drum.ordinal();
        Voice voice = StageCustomMidi.voice(note);
        return complete && voice != null ? DRUMS.length + voice.ordinal() : -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (hidden) {
            super.render(graphics, mouseX, mouseY, partial);
            return;
        }
        graphics.fill(left, top, left + panelWidth, top + panelHeight(), 0xEA111820);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFF6BA7E8);
        drawHeader(graphics, left + 8, top + 9, Component.empty(), 0);
        super.render(graphics, mouseX, mouseY, partial);
        long now = System.nanoTime();
        for (int i = 0; i < (complete ? impacts.length : DRUMS.length); i++) {
            int x = padX(i), y = padY(i), step = padWidth(i);
            if (now - impacts[i] < 160_000_000L) graphics.fill(x, y + 20, x + step - 3, y + 22, 0xFFA0D2FF);
            graphics.drawCenteredString(font, shortcut(i), x + step / 2 - 1, y + 24, 0xFFB6CEE5);
        }
    }

    private void showStatus(MidiPort.Status status) {
        displayed = status;
        Component label = MidiPort.label(status);
        input.setMessage(Component.literal(font.plainSubstrByWidth(label.getString(), input.getWidth() - 12)));
        input.setTooltip(Tooltip.create(label.copy().append("\n").append(Component.translatable("keyboard.device_help"))));
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.level != kit.getLevel()
                || StageCustomMidi.reachable(minecraft.player, kit.getBlockPos()) != kit) {
            onClose();
            return;
        }
        // A key let go of in another window never comes back here.
        if (!minecraft.isWindowActive()) heldKeys.clear();
        if (++ticks % 40 == 0) midi.scan();
        var status = midi.status();
        if (!status.equals(displayed)) showStatus(status);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        int index = SHORTCUTS.indexOf(key);
        if (index < 0 || (!complete && index >= DRUMS.length) || hasControlDown() || hasAltDown()) {
            return super.keyPressed(key, scan, modifiers);
        }
        if (heldKeys.add(key)) {
            int note = index < DRUMS.length ? StageCustomMidi.note(DRUMS[index])
                    : StageCustomMidi.note(CYMBALS[index - DRUMS.length]);
            play(new StageCustomMidi.Hit(kit.getBlockPos(), note, hasShiftDown() ? KeysScreen.SOFT_VELOCITY : KeysScreen.KEY_VELOCITY));
        }
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        return heldKeys.remove(key) || super.keyReleased(key, scan, modifiers);
    }

    @Override
    public void removed() {
        midi.close();
        pending.clear();
        heldKeys.clear();
        super.removed();
    }

    private void receiveMidi(ShortMessage message) {
        if (message.getCommand() != ShortMessage.NOTE_ON || message.getData2() == 0
                || padIndex(message.getData1()) < 0) return;
        if (pending.offer(new StageCustomMidi.Hit(kit.getBlockPos(), message.getData1(), message.getData2()))) dispatch();
    }

    private void dispatch() {
        if (!scheduled.compareAndSet(false, true)) return;
        Minecraft.getInstance().execute(() -> {
            try {
                if (minecraft.screen == this) {
                    for (StageCustomMidi.Hit hit; (hit = pending.poll()) != null;) play(hit);
                } else pending.clear();
            } finally {
                scheduled.set(false);
                if (!pending.isEmpty()) dispatch();
            }
        });
    }

    private void play(StageCustomMidi.Hit hit) {
        int index = padIndex(hit.note());
        if (index < 0 || minecraft.player == null
                || StageCustomMidi.reachable(minecraft.player, kit.getBlockPos()) != kit) return;
        impacts[index] = System.nanoTime();
        StageCustomMidi.strike(minecraft.player, hit);
        PacketDistributor.sendToServer(hit);
    }
}
