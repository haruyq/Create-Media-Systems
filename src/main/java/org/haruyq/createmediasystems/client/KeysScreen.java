package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Playing an instrument with a piano's keys: its 88 keys across the bottom of the screen and a strip of controls
 * above them, over the world. Each instrument draws its own strip and says which keys are down; the body, the keys,
 * the strip's plain buttons and sliders ({@link Face}) and the button that puts all of it out of sight (the
 * instrument still plays from the computer's keys and a MIDI keyboard) are the same for all of them. It is coloured
 * as the Koland keyboard is unless an instrument sets its own colours, after its own model's.
 *
 * <p>It is drawn in the window's own pixels rather than the GUI's coarser ones, so its corners can be round and its
 * edges soft; everything is measured in those, the mouse brought into them as it comes in.
 */
public abstract class KeysScreen extends Screen {
    protected static final int EDGE = 0xFF050506, LAMP = 0xFFFFE7B8, TEXT = 0xFFE6E6EA, FAINT = 0xFF85858D, INK = 0xFF151517;
    /** Shaded faces, each lighter at the top than at the bottom: the body, a button idle, held and lit, one that is off. */
    protected static final int[] BODY = {0xF4242428, 0xF4151517}, BUTTON = {0xFF3D3D45, 0xFF2C2C32}, HELD = {0xFF5A5A63, 0xFF45454D},
            LIT = {0xFFFFF1D2, 0xFFEFCF94}, UNLIT = {0xFF27272B, 0xFF212125};
    /** The keys: a white one from its back to its front edge, the side and the top of a black one; each held down; a black one's shadow. */
    private static final int[] WHITE = {0xFFDAD7CF, 0xFFF8F6F0}, WHITE_DOWN = {0xFF6FA9EC, 0xFFABD2FF},
            BLACK = {0xFF1E1E21, 0xFF08080A}, BLACK_TOP = {0xFF4A4A52, 0xFF2A2A2F},
            BLACK_DOWN = {0xFF1B478C, 0xFF123469}, BLACK_TOP_DOWN = {0xFF4A8DEB, 0xFF2D6FD6}, SHADOW = {0x30000000, 0x48000000};
    private static final int FELT = 0xFF7A1220;
    protected static final int LEFT = -1, MIDDLE = 0, RIGHT = 1;

    /** This instrument's colours, the Koland keyboard's until it sets its own: the lettering, the line around the body, what shows between the keys, the felt. */
    protected int text = TEXT, faint = FAINT, ink = INK, lamp = LAMP, edge = EDGE, bed = EDGE, feltColor = FELT;
    protected int[] body = BODY, button = BUTTON, held = HELD, lit = LIT, unlit = UNLIT;
    protected int[] white = WHITE, whiteDown = WHITE_DOWN, black = BLACK, blackTop = BLACK_TOP, blackDown = BLACK_DOWN,
            blackTopDown = BLACK_TOP_DOWN;

    /** How high the strip is, and the gap between it and the keys (0 for two lines of lettering), in white keys. */
    private final float stripKeys, deckKeys;
    /** How many of the window's pixels one of the GUI's is. */
    protected double pixel = 1;
    /** The keys: their left edge and top, a white key's width and length. The strip of controls is as wide, above them. */
    protected int keysX, keysY, keyWidth, keysHeight;
    protected int stripY, stripWidth, stripHeight;
    /** The gap between the strip and the keys; the felt along the keys' back is in it. */
    protected int deck, felt;
    protected float textScale;
    /** Whether it is out of sight, all but the button that brings it back. */
    protected boolean hidden;
    /** How many times their width the keys are drawn, and the first white key in sight when not all of them fit. */
    protected int zoom = 1, firstWhite;
    protected int ticks;
    private final List<Face> faces = new ArrayList<>();
    @Nullable
    private Face grabbed;

    /**
     * {@code stripKeys} is how high the strip of controls is, and {@code deckKeys} the gap between it and the keys,
     * in white keys; with 0 the gap has room for two lines of lettering.
     */
    protected KeysScreen(Component title, float stripKeys, float deckKeys) {
        super(title);
        this.stripKeys = stripKeys;
        this.deckKeys = deckKeys;
    }

    /** Lays it out. An instrument adds its faces and widgets after this, unless it is {@link #hidden}. */
    @Override
    protected void init() {
        faces.clear();
        grabbed = null;
        pixel = minecraft.getWindow().getGuiScale();
        int wide = minecraft.getWindow().getWidth(), high = minecraft.getWindow().getHeight();
        int margin = (int) Math.round(8 * pixel);
        // Every key a whole number of pixels wide: as many as fit across the window, or as leave the world a third
        // of its height above the strip. The rest follows from that.
        keyWidth = Mth.clamp(Math.min((wide - 2 * margin) / Layout.WHITE_KEYS, (int) (high * 0.7 / (stripKeys + 8 + Math.max(1, deckKeys)))), 8, 48);
        keysHeight = keyWidth * 8;
        keysX = (wide - keyWidth * Layout.WHITE_KEYS) / 2;
        keysY = high - margin - keysHeight;
        // The lettering in half steps of the font's own pixels, which keeps it crisp.
        textScale = Math.max(1, Math.round(keyWidth / 7f) / 2f);
        felt = Math.max(2, keyWidth / 6);
        deck = (deckKeys > 0 ? Math.round(deckKeys * keyWidth) : 2 * lineHeight() + 5) + felt;
        stripWidth = keyWidth * Layout.WHITE_KEYS;
        stripHeight = Math.round(keyWidth * stripKeys);
        stripY = keysY - deck - stripHeight;
    }

    /** How high a line of the small lettering is. */
    protected int lineHeight() {
        return Math.round(8 * textScale) + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The world stays in sight behind it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        double x = mouseX * pixel, y = mouseY * pixel;
        g.pose().pushPose();
        g.pose().scale((float) (1 / pixel), (float) (1 / pixel), 1);
        // In one batch: it is a few thousand small rectangles.
        g.drawManaged(() -> {
            int[] at = toggle();
            if (hidden) {
                box(g, at[0] - 2, at[1] - 2, at[2] + 2, at[3] + 2, lineHeight() / 3 + 2, true, edge, edge);
            } else {
                int around = keyWidth / 3, corner = keyWidth / 2;
                box(g, keysX - around - 2, stripY - around - 2, keysX + stripWidth + around + 2, keysY + keysHeight + around + 2,
                        corner + 2, true, edge, edge);
                box(g, keysX - around, stripY - around, keysX + stripWidth + around, keysY + keysHeight + around, corner, true,
                        body[0], body[1]);
                faces.forEach(face -> draw(g, face));
                draw(g, x, y);
            }
            box(g, at[0], at[1], at[2], at[3], lineHeight() / 3, true, button[0], button[1]);
            write(g, toggleName(), (at[0] + at[2]) / 2f, (at[1] + at[3]) / 2f + textScale / 2, textScale, text, MIDDLE);
        });
        g.pose().popPose();
        if (hidden) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        Face face = grabbed == null ? faceAt(x, y) : null;
        if (face != null && face.hint != null) {
            g.renderTooltip(font, font.split(face.hint, 210), mouseX, mouseY);
        }
    }

    /** The instrument's own part of it, over the body and its faces; the mouse is at ({@code x}, {@code y}) of the window. */
    protected abstract void draw(GuiGraphics g, double x, double y);

    private String toggleName() {
        return Component.translatable(hidden ? "create_media_systems.keys.show" : "create_media_systems.keys.hide").getString();
    }

    /** The button that hides and shows it: at the right end of the top line; at the window's bottom corner while hidden. */
    private int[] toggle() {
        int wide = Math.round(font.width(toggleName()) * textScale) + keyWidth, high = lineHeight() + 2;
        int right = keysX + stripWidth - keyWidth / 3, top = hidden ? keysY + keysHeight - high : stripY + (keyWidth * 3 / 2 - high) / 2;
        return new int[]{right - wide, top, right, top + high};
    }

    /** Where the part of the strip from ({@code left}, {@code top}) to ({@code right}, {@code bottom}), in white keys, is in the window. */
    protected int[] place(float left, float top, float right, float bottom) {
        return new int[]{keysX + Math.round(left * keyWidth), stripY + Math.round(top * keyWidth),
                keysX + Math.round(right * keyWidth), stripY + Math.round(bottom * keyWidth)};
    }

    protected static boolean in(int[] at, double x, double y) {
        return x >= at[0] && x < at[2] && y >= at[1] && y < at[3];
    }

    /**
     * The top line: the instrument's {@code name}, how it is set after it, and as much of {@code right} as there is
     * room for at the other end, short of the button that hides it.
     */
    protected void drawHeader(GuiGraphics g, String name, String status, int statusColor, String right, int rightColor) {
        float y = stripY + keyWidth * 0.75f;
        int inset = keyWidth / 3, end = toggle()[0] - inset;
        float from = keysX + inset + (font.width(name) + 8) * textScale;
        write(g, name, keysX + inset, y, textScale, faint, LEFT);
        write(g, status, from, y, textScale, statusColor, LEFT);
        write(g, fit(right, Math.max(0, end - Math.round(from + (font.width(status) + 12) * textScale))), end, y, textScale,
                rightColor, RIGHT);
    }

    /**
     * The part of {@code texture} ({@code wide} by {@code high}) from ({@code u}, {@code v}), {@code across} by
     * {@code down}, stretched smoothly over the rectangle from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}),
     * on top of all that is drawn so far.
     */
    protected void picture(GuiGraphics g, ResourceLocation texture, int x0, int y0, int x1, int y1, int u, int v, int across,
            int down, int wide, int high) {
        g.flush();
        minecraft.getTextureManager().getTexture(texture).setFilter(true, false);
        RenderSystem.enableBlend();
        g.blit(texture, x0, y0, x1 - x0, y1 - y0, u, v, across, down, wide, high);
    }

    /**
     * A rectangle with its corners rounded by {@code radius} (the upper two only with {@code roundTop}), shaded from
     * {@code top} down to {@code bottom}. The pixels the curve only partly covers are drawn as faint as it covers them.
     */
    protected static void box(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, boolean roundTop, int top, int bottom) {
        int r = Math.max(0, Math.min(radius, Math.min(x1 - x0, y1 - y0) / 2));
        for (int row = 0; row < r; row++) {
            double up = r - row - 0.5, inset = r - Math.sqrt(r * r - up * up);
            int whole = (int) Math.ceil(inset);
            if (roundTop) {
                span(g, x0 + whole, x1 - whole, y0 + row, (float) (whole - inset), top);
            }
            span(g, x0 + whole, x1 - whole, y1 - 1 - row, (float) (whole - inset), bottom);
        }
        g.fillGradient(x0, y0 + (roundTop ? r : 0), x1, y1 - r, top, bottom);
    }

    /** One row of pixels from {@code x0} to {@code x1}, and the pixel beyond each end at {@code edge} (0..1) of its colour. */
    protected static void span(GuiGraphics g, int x0, int x1, int y, float edge, int color) {
        g.fill(x0, y, x1, y + 1, color);
        if (edge > 0.05f) {
            int faint = Math.round((color >>> 24) * edge) << 24 | color & 0xFFFFFF;
            g.fill(x0 - 1, y, x0, y + 1, faint);
            g.fill(x1, y, x1 + 1, y + 1, faint);
        }
    }

    /** A filled circle of {@code radius} around ({@code x}, {@code y}), its rim as soft as a box's corners. */
    protected static void disc(GuiGraphics g, int x, int y, float radius, int color) {
        for (int row = -(int) Math.ceil(radius); row < Math.ceil(radius); row++) {
            double up = row + 0.5;
            if (Math.abs(up) < radius) {
                double half = Math.sqrt(radius * radius - up * up);
                span(g, x - (int) half, x + (int) half, y + row, (float) (half - (int) half), color);
            }
        }
    }

    /** {@code text} at {@code scale}, its middle {@code y} high and its left end, middle or right end at {@code x}. */
    protected void write(GuiGraphics g, String text, float x, float y, float scale, int color, int align) {
        int wide = font.width(text);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, align == LEFT ? 0 : align == MIDDLE ? -wide / 2 : -wide, -4, color, false);
        g.pose().popPose();
    }

    /** As much of {@code text} as the small lettering fits into {@code wide} of the window's pixels. */
    protected String fit(String text, int wide) {
        int room = (int) (wide / textScale);
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, Math.max(0, room - font.width("…"))) + "…";
    }

    /**
     * The keys as a piano's look from above: the white ones rounded at their front corners and in the shade towards
     * their back; the black ones with their lighter top face, narrower than the key and short of its front, where it
     * slopes down, and their shadow on the white ones beside them. {@code down} says which notes' keys are held.
     */
    protected void drawKeys(GuiGraphics g, IntPredicate down) {
        int right = keysX + stripWidth, bottom = keysY + keysHeight, key = keyWidth * zoom;
        g.fill(keysX, keysY - felt, right, keysY, feltColor);
        g.fill(keysX, keysY, right, bottom, bed);
        int gap = Math.max(1, Math.round(key / 11f)), corner = Math.max(2, key / 5);
        int blackWidth = Math.max(3, Math.round(key * Layout.BLACK_WIDTH)), blackLength = Math.round(keysHeight * Layout.BLACK_LENGTH);
        int side = Math.max(1, blackWidth / 6), slope = Math.max(3, keyWidth / 2), shade = Math.max(1, key / 8);
        for (int pass = 0; pass < 2; pass++) {
            for (int note = Layout.LOWEST; note <= Layout.HIGHEST; note++) {
                if (Layout.black(note) != (pass == 1)) {
                    continue;
                }
                boolean pressed = down.test(note);
                int x = keysX + (Layout.across(note) - firstWhite) * key;
                if (pass == 0) {
                    int[] face = pressed ? whiteDown : white;
                    part(g, x, keysY, x + key - gap, bottom, corner, face);
                    continue;
                }
                // On the middle of the gap between the two white keys it lies between.
                int x0 = Math.round(x - gap / 2f - blackWidth / 2f), x1 = x0 + blackWidth, end = keysY + blackLength;
                part(g, x0 + shade, keysY, x1 + shade, end + shade, corner, SHADOW);
                part(g, x0, keysY, x1, end, Math.max(2, blackWidth / 4), pressed ? blackDown : black);
                // Held down it tips forward: more of its top shows, less of its front.
                part(g, x0 + side, keysY, x1 - side, end - (pressed ? slope / 2 : slope), Math.max(1, blackWidth / 5),
                        pressed ? blackTopDown : blackTop);
            }
        }
    }

    /** A part of a key, as much of it as is in sight. */
    private void part(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, int[] face) {
        int from = Math.max(x0, keysX), to = Math.min(x1, keysX + stripWidth);
        if (to > from) {
            box(g, from, y0, to, y1, radius, false, face[0], face[1]);
        }
    }

    /** The note of the key at ({@code x}, {@code y}) of the window; -1 off the keys. */
    protected int keyAt(double x, double y) {
        if (hidden || x < keysX || x >= keysX + stripWidth) {
            return -1;
        }
        return Layout.keyAt((float) (x - keysX) / (keyWidth * zoom) + firstWhite, (float) (y - keysY) / keysHeight);
    }

    /**
     * A button on the strip, from ({@code left}, {@code top}) to ({@code right}, {@code bottom}) in white keys from
     * the strip's upper left corner, that says {@code text} and does {@code press}.
     */
    protected Face button(float left, float top, float right, float bottom, Supplier<Component> text, Runnable press) {
        return add(new Face(left, top, right, bottom, value -> text.get(), press, null, null, 0, 1));
    }

    /**
     * A slider on the strip: filled as far as {@code value} is from {@code min} to {@code max}, dragged to
     * {@code set} it. One with nothing to set is a meter: it only shows the value, lit as far as it is filled.
     */
    protected Face slider(float left, float top, float right, float bottom, IntFunction<Component> text, int min, int max,
            IntSupplier value, @Nullable IntConsumer set) {
        return add(new Face(left, top, right, bottom, text, null, value, set, min, max));
    }

    private Face add(Face face) {
        faces.add(face);
        return face;
    }

    @Nullable
    private Face faceAt(double x, double y) {
        for (Face face : faces) {
            if (in(face.place(), x, y)) {
                return face;
            }
        }
        return null;
    }

    private void draw(GuiGraphics g, Face face) {
        int[] at = face.place();
        int corner = Math.max(2, keyWidth / 5), shown = face.shown();
        int[] look = face.value != null ? unlit : face.lit.getAsBoolean() ? lit : face == grabbed ? held : button;
        box(g, at[0], at[1], at[2], at[3], corner, true, look[0], look[1]);
        boolean bright = look == lit;
        if (face.value != null) {
            float share = (shown - face.min) / (float) (face.max - face.min);
            int[] fill = face.set == null ? lit : held;
            box(g, at[0], at[1], at[0] + Math.round((at[2] - at[0]) * share), at[3], corner, true, fill[0], fill[1]);
            bright = face.set == null && share > 0.5f;
        }
        write(g, fit(face.text.apply(shown).getString(), at[2] - at[0] - keyWidth / 2), (at[0] + at[2]) / 2f,
                (at[1] + at[3]) / 2f + textScale / 2, textScale, bright ? ink : text, MIDDLE);
    }

    /** True when the click is taken: by the button that hides it, by a face, by a widget; and anywhere while hidden. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX * pixel, y = mouseY * pixel;
        if (in(toggle(), x, y)) {
            hidden = !hidden;
            rebuildWidgets();
            return true;
        }
        if (hidden) {
            return true;
        }
        Face face = faceAt(x, y);
        if (face == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (face.value != null) {
            face.dragged = face.value.getAsInt();
        }
        grabbed = face;
        face.drag(x);
        if (face.press != null) {
            face.press.run();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (grabbed == null) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        grabbed.drag(mouseX * pixel);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        grabbed = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** A button of the strip, or a slider. */
    protected final class Face {
        private final float left, top, right, bottom;
        private final IntFunction<Component> text;
        @Nullable
        private final Runnable press;
        @Nullable
        private final IntSupplier value;
        @Nullable
        private final IntConsumer set;
        private final int min, max;
        private BooleanSupplier lit = () -> false;
        @Nullable
        private Component hint;
        /** What a slider was last dragged to, and when: the instrument takes a moment to have it. */
        private int dragged, edited = -20;

        private Face(float left, float top, float right, float bottom, IntFunction<Component> text, @Nullable Runnable press,
                @Nullable IntSupplier value, @Nullable IntConsumer set, int min, int max) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.text = text;
            this.press = press;
            this.value = value;
            this.set = set;
            this.min = min;
            this.max = max;
        }

        /** Lit for as long as {@code lit} says. */
        public Face lit(BooleanSupplier lit) {
            this.lit = lit;
            return this;
        }

        /** Explained by {@code hint} under the mouse. */
        public Face hint(Component hint) {
            this.hint = hint;
            return this;
        }

        private int[] place() {
            return KeysScreen.this.place(left, top, right, bottom);
        }

        private int shown() {
            return value == null ? 0 : set != null && (grabbed == this || ticks - edited < 3) ? dragged : value.getAsInt();
        }

        private void drag(double x) {
            if (set == null) {
                return;
            }
            int[] at = place();
            int next = min + (int) Math.round(Mth.clamp((x - at[0]) / (at[2] - at[0]), 0, 1) * (max - min));
            if (next != dragged) {
                dragged = next;
                edited = ticks;
                set.accept(next);
            }
        }
    }
}
