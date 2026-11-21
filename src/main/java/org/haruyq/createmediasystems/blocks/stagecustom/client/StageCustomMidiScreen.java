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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomFeature;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomMidi;
import org.haruyq.createmediasystems.client.MidiPort;

final class StageCustomMidiScreen extends Screen {
    private static final Drum[] DRUMS = Drum.values();
    private static final String SHORTCUTS = "ZXCVB";
    private static String preferredInput;
    private final StageCustomBlock.Entity kit;
    private final Set<Integer> heldKeys = new HashSet<>();
    private final ArrayBlockingQueue<StageCustomMidi.Hit> pending = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final long[] impacts = new long[DRUMS.length];
    private final MidiPort midi;
    private MidiPort.Status displayed;
    private Button input;
    private int left, top, panelWidth, ticks;

    StageCustomMidiScreen(StageCustomBlock.Entity kit) {
        super(Component.translatable("block.create_media_systems." + StageCustomFeature.ID));
        this.kit = kit;
        midi = new MidiPort(preferredInput, this::receiveMidi, pending::clear);
        midi.scan();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(354, width - 16);
        left = (width - panelWidth) / 2;
        top = height - 105;
        int padWidth = (panelWidth - 16) / DRUMS.length;
        for (Drum drum : DRUMS) {
            int index = drum.ordinal();
            Component name = Component.translatable("stage_custom.pad." + drum.id());
            int note = StageCustomMidi.note(drum);
            var pad = Button.builder(name, button -> play(new StageCustomMidi.Hit(kit.getBlockPos(), note, 110)))
                    .bounds(left + 8 + index * padWidth, top + 25, padWidth - 3, 20).build();
            pad.setTooltip(Tooltip.create(Component.translatable("stage_custom.pad_hint", SHORTCUTS.substring(index, index + 1), note)));
            addRenderableWidget(pad);
        }
        Component refresh = Component.translatable("keyboard.refresh");
        int refreshWidth = Math.max(54, font.width(refresh) + 12);
        input = addRenderableWidget(Button.builder(Component.empty(), button -> nextInput())
                .bounds(left + 8, top + 62, panelWidth - refreshWidth - 20, 20).build());
        addRenderableWidget(Button.builder(refresh, button -> midi.scan())
                .bounds(left + panelWidth - refreshWidth - 8, top + 62, refreshWidth, 20).build());
        showStatus(midi.status());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(left, top, left + panelWidth, top + 90, 0xEA111820);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFF6BA7E8);
        graphics.drawString(font, title, left + 8, top + 9, 0xFFF1F6FC, false);
        super.render(graphics, mouseX, mouseY, partial);
        long now = System.nanoTime();
        int step = (panelWidth - 16) / DRUMS.length;
        for (int i = 0; i < DRUMS.length; i++) {
            int x = left + 8 + i * step;
            if (now - impacts[i] < 160_000_000L) graphics.fill(x, top + 45, x + step - 3, top + 47, 0xFFA0D2FF);
            graphics.drawCenteredString(font, SHORTCUTS.substring(i, i + 1), x + step / 2 - 1, top + 49, 0xFFB6CEE5);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {}

    private void nextInput() {
        var status = midi.status();
        int next = 0;
        for (int i = 0; i < status.inputs().size(); i++) {
            if (status.inputs().get(i).id().equals(status.selected())) {
                next = i + 1;
                break;
            }
        }
        preferredInput = next < status.inputs().size() ? status.inputs().get(next).id() : "";
        midi.select(preferredInput);
    }

    private void showStatus(MidiPort.Status status) {
        displayed = status;
        var selected = status.inputs().stream().filter(device -> device.id().equals(status.selected())).findFirst();
        Component label = "keyboard.midi_connected".equals(status.message()) && selected.isPresent()
                ? Component.translatable("stage_custom.midi_input", selected.get().name())
                : Component.translatable(status.message());
        input.setMessage(Component.literal(font.plainSubstrByWidth(label.getString(), input.getWidth() - 12)));
        input.setTooltip(Tooltip.create(label));
        input.active = !status.inputs().isEmpty();
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.level != kit.getLevel()
                || StageCustomMidi.reachable(minecraft.player, kit.getBlockPos()) != kit) {
            onClose();
            return;
        }
        if (++ticks % 40 == 0) midi.scan();
        var status = midi.status();
        if (!status.equals(displayed)) showStatus(status);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        int index = SHORTCUTS.indexOf(key);
        if (index < 0 || hasControlDown() || hasAltDown()) return super.keyPressed(key, scan, modifiers);
        if (heldKeys.add(key)) {
            play(new StageCustomMidi.Hit(kit.getBlockPos(), StageCustomMidi.note(DRUMS[index]), hasShiftDown() ? 59 : 110));
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
                || StageCustomMidi.drum(message.getData1()) == null) return;
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
        Drum drum = StageCustomMidi.drum(hit.note());
        if (drum == null || minecraft.player == null
                || StageCustomMidi.reachable(minecraft.player, kit.getBlockPos()) != kit) return;
        impacts[drum.ordinal()] = System.nanoTime();
        PacketDistributor.sendToServer(hit);
    }
}
