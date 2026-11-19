package org.haruyq.createmediasystems.items.walkman.nws310.client;

import static org.haruyq.createmediasystems.items.walkman.nws310.client.LoudmanMesh.shade;

import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.blocks.radio.client.RadioAudio;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem.Tuning;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Loudman's controls, drawn as the player itself: its screen shows the frequency, whether a station is received
 * and the volume; its five-way pad tunes (left and right, 0.1 MHz a step, 1 MHz with Shift), sets the volume (up and
 * down) and turns it on and off (centre). The mouse wheel and the arrow keys work the pad too.
 */
final class LoudmanScreen extends Screen {
    private static final int WIDTH = 112, HEIGHT = 214;
    /** The pad's centre from the body's top-left, and the radii of its ring and its centre key. */
    private static final int PAD_X = WIDTH / 2, PAD_Y = 164, RING = 32, CENTER = 12;
    /** Ticks a pad direction is held before it repeats every tick. */
    private static final int REPEAT_AFTER = 8;

    private enum Key { UP, DOWN, LEFT, RIGHT, CENTER }

    private final InteractionHand hand;
    private final int color;
    /** What it is set to here; each change is sent, and what the server keeps comes back on the item. */
    private Tuning tuning;
    private int x0, y0;
    @Nullable
    private Key held;
    private int heldTicks;

    LoudmanScreen(InteractionHand hand) {
        super(Component.translatable("loudman.title"));
        this.hand = hand;
        ItemStack stack = Minecraft.getInstance().player.getItemInHand(hand);
        tuning = LoudmanItem.tuning(stack);
        color = stack.getItem() instanceof LoudmanItem loudman ? loudman.color : 0;
    }

    @Override
    protected void init() {
        x0 = (width - WIDTH) / 2;
        y0 = (height - HEIGHT) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!(minecraft.player.getItemInHand(hand).getItem() instanceof LoudmanItem)) {
            onClose();
        } else if (held != null && held.ordinal() <= Key.RIGHT.ordinal() && ++heldTicks > REPEAT_AFTER) {
            press(held);
        }
    }

    private void press(Key key) {
        int step = hasShiftDown() ? 10 : 1;
        switch (key) {
            case LEFT -> set(new Tuning(tuned(-step), tuning.volume(), tuning.on()));
            case RIGHT -> set(new Tuning(tuned(step), tuning.volume(), tuning.on()));
            case UP -> set(new Tuning(tuning.frequency(), tuning.volume() + 1, tuning.on()));
            case DOWN -> set(new Tuning(tuning.frequency(), tuning.volume() - 1, tuning.on()));
            case CENTER -> set(new Tuning(tuning.frequency(), tuning.volume(), !tuning.on()));
        }
    }

    /** The frequency {@code step} tenths of a MHz on, round the ends of the band. */
    private int tuned(int step) {
        int next = tuning.frequency() + step;
        return next > RadioFeature.MAX_FREQUENCY ? RadioFeature.MIN_FREQUENCY
                : next < RadioFeature.MIN_FREQUENCY ? RadioFeature.MAX_FREQUENCY : next;
    }

    private void set(Tuning next) {
        if (!next.equals(tuning)) {
            tuning = next;
            PacketDistributor.sendToServer(new LoudmanItem.Set(hand, next));
        }
    }

    @Nullable
    private Key keyAt(double mouseX, double mouseY) {
        double dx = mouseX - x0 - PAD_X, dy = mouseY - y0 - PAD_Y, distance = Math.hypot(dx, dy);
        if (distance <= CENTER) {
            return Key.CENTER;
        }
        if (distance <= RING) {
            return Math.abs(dx) > Math.abs(dy) ? dx < 0 ? Key.LEFT : Key.RIGHT : dy < 0 ? Key.UP : Key.DOWN;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Key key = button == 0 ? keyAt(mouseX, mouseY) : null;
        if (key == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, .25f));
        held = key;
        heldTicks = 0;
        press(key);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        held = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            press(scrollY > 0 ? Key.RIGHT : Key.LEFT);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Key key = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> Key.LEFT;
            case GLFW.GLFW_KEY_RIGHT -> Key.RIGHT;
            case GLFW.GLFW_KEY_UP -> Key.UP;
            case GLFW.GLFW_KEY_DOWN -> Key.DOWN;
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> Key.CENTER;
            default -> null;
        };
        if (key == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        press(key);
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int body = 0xFF000000 | color, cx = x0 + PAD_X, cy = y0 + PAD_Y;
        Key hover = keyAt(mouseX, mouseY);
        rounded(g, x0 - 1, y0 - 1, WIDTH + 2, HEIGHT + 2, 9, 0xFF000000 | shade(color, .5f));
        rounded(g, x0, y0, WIDTH, HEIGHT, 8, body);
        // The brushed deck under the glass.
        for (int y = 122; y < HEIGHT - 6; y += 2) {
            g.fill(x0 + 3, y0 + y, x0 + WIDTH - 3, y0 + y + 1, 0xFF000000 | shade(color, 1.07f));
        }
        rounded(g, x0 + 4, y0 + 4, WIDTH - 8, 114, 5, 0xFF0B0B0E);
        g.drawCenteredString(font, "SUNNY", cx, y0 + 11, 0xFFDADADA);

        int left = x0 + 14, top = y0 + 26, right = x0 + WIDTH - 14, bottom = y0 + 110;
        g.fill(left, top, right, bottom, tuning.on() ? 0xFF16222E : 0xFF030304);
        if (tuning.on()) {
            boolean receiving = RadioAudio.receiving();
            g.drawString(font, "FM", left + 4, top + 4, 0xFF7FD6FF, false);
            // The battery, always full: it has none to run down.
            g.fill(right - 17, top + 5, right - 5, top + 11, 0xFFB8C4D0);
            g.fill(right - 16, top + 6, right - 6, top + 10, 0xFF3BD26B);
            g.fill(right - 5, top + 7, right - 4, top + 9, 0xFFB8C4D0);
            g.pose().pushPose();
            g.pose().translate(cx, top + 20, 0);
            g.pose().scale(2.5f, 2.5f, 1);
            g.drawCenteredString(font, RadioFeature.mhz(tuning.frequency()), 0, 0, 0xFFFFFFFF);
            g.pose().popPose();
            g.drawCenteredString(font, "MHz", cx, top + 43, 0xFFB8C4D0);
            g.drawCenteredString(font, Component.translatable(receiving ? "loudman.receiving" : "loudman.no_signal"), cx, top + 56,
                    receiving ? 0xFF6FE38F : 0xFF7C8794);
            g.drawString(font, "VOL", left + 4, bottom - 12, 0xFFB8C4D0, false);
            int bar = left + 26, end = right - 20;
            g.fill(bar, bottom - 10, end, bottom - 6, 0xFF2C3A48);
            g.fill(bar, bottom - 10, bar + (end - bar) * tuning.volume() / LoudmanItem.MAX_VOLUME, bottom - 6, 0xFF7FD6FF);
            g.drawString(font, String.valueOf(tuning.volume()), end + 4, bottom - 12, 0xFFFFFFFF, false);
        }

        disc(g, cx, cy, RING + 1, 0xFF000000 | shade(color, .6f));
        disc(g, cx, cy, RING, 0xFF000000 | shade(color, .95f));
        disc(g, cx, cy, CENTER + 1, 0xFF000000 | shade(color, .6f));
        disc(g, cx, cy, CENTER, 0xFF000000 | shade(color, hover == Key.CENTER ? 1.25f : .85f));
        int mark = 0xFF000000 | shade(color, color == 0x1B1C20 ? 4 : .45f), lit = 0xFFFFFFFF;
        // The pad's marks: an arrow each way, and play and pause on its centre key.
        for (int i = 0; i < 4; i++) {
            arrow(g, cx - 23 + i, cy, i, hover == Key.LEFT ? lit : mark);
            arrow(g, cx + 23 - i, cy, i, hover == Key.RIGHT ? lit : mark);
            g.fill(cx - i, cy - 23 + i, cx + i + 1, cy - 22 + i, hover == Key.UP ? lit : mark);
            g.fill(cx - i, cy + 23 - i, cx + i + 1, cy + 24 - i, hover == Key.DOWN ? lit : mark);
            arrow(g, cx - 4 + i, cy, 3 - i, mark);
        }
        g.fill(cx + 2, cy - 3, cx + 3, cy + 4, mark);
        g.fill(cx + 4, cy - 3, cx + 5, cy + 4, mark);
        g.drawCenteredString(font, "LOUDMAN", cx, y0 + HEIGHT - 12, mark);
    }

    /** A column of an arrowhead: {@code half} pixels either side of y, at x. */
    private static void arrow(GuiGraphics g, int x, int y, int half, int argb) {
        g.fill(x, y - half, x + 1, y + half + 1, argb);
    }

    private static void disc(GuiGraphics g, int cx, int cy, int radius, int argb) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt(radius * radius - dy * dy));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }

    /** A filled rectangle with its corners rounded to {@code radius} pixels. */
    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int argb) {
        for (int row = 0; row < h; row++) {
            int in = Math.min(row, h - 1 - row);
            int cut = in >= radius ? 0 : radius - (int) Math.round(Math.sqrt(radius * radius - (radius - in) * (radius - in)));
            g.fill(x + cut, y + row, x + w - cut, y + row + 1, argb);
        }
    }
}
