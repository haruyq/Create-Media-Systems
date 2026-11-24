package org.haruyq.createmediasystems.blocks.steelpan.client;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.midi.ShortMessage;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout.Note;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets.Target;
import org.haruyq.createmediasystems.client.KeysScreen;
import org.haruyq.createmediasystems.client.MidiPort;
import org.joml.Matrix4f;
import org.joml.Vector2f;

final class SteelpanScreen extends Screen {
    /** The key shown on each note, as {@link KeysScreen#typedStep} plays them: the upper row's where both rows have one. */
    private static final String KEYS = "ZSXDCVGBHNJMQ2W3ER5T6Y7UI9O0P";
    private static final int STEPS = 36;
    private static final Vector2f[] CIRCLE = new Vector2f[STEPS];
    private static final Vector2f[][] OUTLINES = new Vector2f[KEYS.length()][STEPS];
    private static final long GLOW = 310_000_000L;
    private static final int TEXT = 0xFFF1F5F9, MUTED = 0xFFA3B9C7, TEMPERATURE = 0xFFE6A464;
    private static String midiPreference;

    static {
        for (int i = 0; i < STEPS; i++) {
            double angle = 2 * Math.PI * i / STEPS;
            CIRCLE[i] = new Vector2f((float) Math.cos(angle), (float) Math.sin(angle));
        }
        for (var note : SteelpanLayout.DATA.notes()) {
            for (int i = 0; i < STEPS; i++) {
                OUTLINES[note.midi() - SteelpanLayout.LOWEST][i] = outline(note, CIRCLE[i]);
            }
        }
    }

    private record Hit(int midi, int velocity) {}

    private final Target target;
    private final Set<Integer> held = new HashSet<>();
    private final ArrayBlockingQueue<Hit> inputs = new ArrayBlockingQueue<>(96);
    private final AtomicBoolean midiQueued = new AtomicBoolean();
    private final long[] impacts = new long[KEYS.length()];
    private final float[] velocities = new float[KEYS.length()];
    private final MidiPort midi;
    private MidiPort.Status midiStatus;
    private Button input;
    private int left, top, right, bottom, centerX, centerY, diameter, ticks;
    private float scale;

    SteelpanScreen(Target target) {
        super(Component.translatable("block.create_media_systems.steelpan_low_c"));
        this.target = target;
        midi = new MidiPort(midiPreference, this::receiveMidi, inputs::clear);
        midi.scan();
    }

    @Override
    protected void init() {
        int w = Math.min(352, width - 16);
        int h = Math.min(372, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;
        right = left + w;
        bottom = top + h;
        diameter = Math.max(20, Math.min(w - 30, h - 102));
        scale = diameter / SteelpanLayout.DATA.diameter();
        centerX = width / 2;
        centerY = top + 44 + (h - 102) / 2;

        Component refresh = Component.translatable("keyboard.refresh");
        int refreshWidth = Math.max(58, font.width(refresh) + 14);
        input = addRenderableWidget(Button.builder(Component.empty(), button -> nextMidi())
                .bounds(left + 12, bottom - 28, w - refreshWidth - 30, 19).build());
        addRenderableWidget(Button.builder(refresh, button -> midi.scan())
                .bounds(right - 12 - refreshWidth, bottom - 28, refreshWidth, 19).build());
        updateMidi(midi.status());
    }

    private void nextMidi() {
        var status = midi.status();
        int chosen = -1;
        for (int i = 0; i < status.inputs().size(); i++) {
            if (status.inputs().get(i).id().equals(status.selected())) {
                chosen = i;
                break;
            }
        }
        int next = chosen + 1;
        midi.select(next < status.inputs().size() ? status.inputs().get(next).id() : "");
        midiPreference = midi.preference();
    }

    private void updateMidi(MidiPort.Status status) {
        midiStatus = status;
        var device = status.inputs().stream().filter(candidate -> candidate.id().equals(status.selected())).findFirst();
        Component label = status.message().equals("keyboard.midi_connected") && device.isPresent()
                ? Component.translatable("steelpan.midi_input", device.get().name())
                : Component.translatable(status.message());
        input.setMessage(Component.literal(font.plainSubstrByWidth(label.getString(), Math.max(1, input.getWidth() - 12))));
        input.setTooltip(Tooltip.create(label));
        input.active = !status.inputs().isEmpty();
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || !SteelpanPackets.canPlay(minecraft.player, target)) {
            onClose();
            return;
        }
        if (++ticks % 40 == 0) midi.scan();
        var status = midi.status();
        if (status != midiStatus) updateMidi(status);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        midi.close();
        inputs.clear();
        held.clear();
        super.removed();
    }

    void strike(SteelpanPackets.Strike strike, long impact) {
        if (!target.equals(strike.target())) return;
        int index = strike.midi() - SteelpanLayout.LOWEST;
        impacts[index] = impact;
        velocities[index] = strike.velocity() / 127f;
    }

    private void receiveMidi(ShortMessage message) {
        int midi = message.getData1();
        int velocity = message.getData2();
        if (message.getCommand() == ShortMessage.NOTE_ON && SteelpanLayout.contains(midi) && velocity > 0
                && inputs.offer(new Hit(midi, velocity))) dispatchMidi();
    }

    private void dispatchMidi() {
        if (!midiQueued.compareAndSet(false, true)) return;
        Minecraft.getInstance().execute(() -> {
            try {
                if (minecraft != null && minecraft.screen == this) {
                    for (Hit hit; (hit = inputs.poll()) != null;) play(hit.midi, hit.velocity);
                } else inputs.clear();
            } finally {
                midiQueued.set(false);
                if (!inputs.isEmpty()) dispatchMidi();
            }
        });
    }

    private void play(int midi, int velocity) {
        if (minecraft != null && minecraft.player != null) SteelpanPackets.playLocally(minecraft.player, target, midi, velocity);
        PacketDistributor.sendToServer(new SteelpanPackets.Play(target, midi, velocity));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        int at = pick(x, y);
        if (button == 0 && at >= 0) {
            var note = SteelpanLayout.note(at);
            double offset = ((y - centerY) / scale - note.center()[2]) / note.axes()[1];
            play(at, 81 + (int) Math.round(42 * Math.clamp(offset, -1, 1)));
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        int index = KeysScreen.typedStep(key);
        if (index >= 0 && !hasControlDown() && !hasAltDown()) {
            if (held.add(key)) play(SteelpanLayout.LOWEST + index, hasShiftDown() ? KeysScreen.SOFT_VELOCITY : KeysScreen.KEY_VELOCITY);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        return held.remove(key) || super.keyReleased(key, scan, modifiers);
    }

    private int pick(double x, double y) {
        return SteelpanLayout.at((float) ((x - centerX) / scale), (float) ((y - centerY) / scale));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        renderBackground(gfx, mouseX, mouseY, delta);
        gfx.fillGradient(left, top, right, bottom, 0xFF26343C, 0xFF131B23);
        gfx.renderOutline(left, top, right - left, bottom - top, 0xFF586A74);
        gfx.fill(left + 1, top + 1, right - 1, top + 2, 0xFF7696A0);
        gfx.drawCenteredString(font, title, centerX, top + 11, TEXT);
        gfx.drawCenteredString(font, Component.translatable("steelpan.range"), centerX, top + 26, MUTED);

        int hover = pick(mouseX, mouseY);
        long now = System.nanoTime();
        bowl(gfx, hover, now);
        for (var note : SteelpanLayout.DATA.notes()) {
            int index = note.midi() - SteelpanLayout.LOWEST;
            String key = String.valueOf(KEYS.charAt(index));
            int x = Math.round(centerX + scale * note.center()[0]);
            int y = Math.round(centerY + scale * note.center()[2]);
            int color = pulse(index, now) > .25f ? 0xFFFFF0D3 : TEXT;
            if (note.ring() == 0 && diameter >= 140) {
                gfx.drawCenteredString(font, note.label(), x, y - 8, color);
                gfx.drawCenteredString(font, key, x, y + 2, MUTED);
            } else {
                gfx.drawCenteredString(font, key, x, y - 4, color);
            }
        }

        Component help = hover < 0 ? Component.translatable("steelpan.controls")
                : Component.literal(SteelpanLayout.note(hover).label() + "  •  " + KEYS.charAt(hover - SteelpanLayout.LOWEST));
        gfx.drawCenteredString(font, help, centerX, bottom - 44, hover < 0 ? MUTED : 0xFFD9F2F3);
        super.render(gfx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        gfx.fillGradient(0, 0, width, height, 0x90202930, 0xC3080D14);
    }

    private void bowl(GuiGraphics gfx, int hover, long now) {
        gfx.flush();
        var matrix = gfx.pose().last().pose();
        var out = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float radius = diameter / 2f;
        fan(out, matrix, centerX, centerY + 3, radius + 2, CIRCLE, 0xFF081017, 0xFF0D141C, 0);
        fan(out, matrix, centerX, centerY, radius, CIRCLE, 0xFF647C87, 0xFF879BA5, 1);
        fan(out, matrix, centerX, centerY, radius - 3, CIRCLE, 0xFF394A54, 0xFF1B252D, 2);
        fan(out, matrix, centerX, centerY, radius - 6, CIRCLE, 0xFF354B57, 0xFF263740, 3);
        for (var note : SteelpanLayout.DATA.notes()) {
            int index = note.midi() - SteelpanLayout.LOWEST;
            float amount = pulse(index, now);
            float x = centerX + note.center()[0] * scale, y = centerY + note.center()[2] * scale;
            int rim = note.midi() == hover ? 0xFFBEE7E8 : 0xFF627F8C;
            rim = FastColor.ARGB32.lerp(amount, rim, TEMPERATURE);
            int middle = FastColor.ARGB32.lerp(amount, 0xFF425D69, 0xFF9B663D);
            int edge = FastColor.ARGB32.lerp(amount, 0xFF263A47, 0xFF61422F);
            fan(out, matrix, x, y, scale, OUTLINES[index], rim, rim, 4);
            fan(out, matrix, x, y, scale * .92f, OUTLINES[index], middle, edge, 5);
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();
        try {
            BufferUploader.drawWithShader(out.buildOrThrow());
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    private float pulse(int index, long now) {
        long age = now - impacts[index];
        if (impacts[index] == 0 || age < 0 || age >= GLOW) return 0;
        return (1 - (float) age / GLOW) * (.45f + .55f * velocities[index]);
    }

    private static void fan(BufferBuilder buffer, Matrix4f matrix, float x, float y, float scale, Vector2f[] outline,
                            int inner, int outer, float z) {
        for (int i = 0; i < outline.length; i++) {
            Vector2f first = outline[i], next = outline[(i + 1) % outline.length];
            buffer.addVertex(matrix, x, y, z).setColor(inner);
            buffer.addVertex(matrix, x + next.x * scale, y + next.y * scale, z).setColor(outer);
            buffer.addVertex(matrix, x + first.x * scale, y + first.y * scale, z).setColor(outer);
        }
    }

    private static Vector2f outline(Note note, Vector2f unit) {
        if (note.ring() != 0) return new Vector2f(unit.x * note.axes()[0], unit.y * note.axes()[1]);
        float sin = (float) Math.sin(note.angle()), cos = (float) Math.cos(note.angle());
        float u = unit.x * note.axes()[1], v = -unit.y * note.axes()[0] * (1 + .16f * unit.x);
        return new Vector2f(u * sin + v * cos, u * cos - v * sin);
    }
}
