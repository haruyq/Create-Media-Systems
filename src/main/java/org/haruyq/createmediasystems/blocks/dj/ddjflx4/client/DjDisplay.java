package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjState;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjState.Track;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.client.DjClient.View;
import org.haruyq.createmediasystems.client.QuadOutput;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * A DDJ-FLX4's two decks laid out as rekordbox 7 shows them in its two-deck view: each deck's waveform running past
 * the line it plays at, one above the other; under them each deck's track, times and tempo, its whole track with how
 * far in it is, its hot cues and its platter; and the mixer between the decks. Drawn flat, {@link #WIDTH} by
 * {@link #HEIGHT}, x to the right and y down, on a Monitor in the world and on the controller's own screen alike, as
 * finely as it is seen: its lettering, its rings and its waveforms are made for the pixels a unit of it covers.
 */
public final class DjDisplay {
    public static final float WIDTH = 480, HEIGHT = 256;
    private static final int BACK = 0x0A0A0B, PANEL = 0x171719, LINE = 0x2B2B2F, TEXT = 0xECECEC, FAINT = 0x8C8C93,
            BLUE = 0x2E80FF, RED = 0xEC3A30, GREEN = 0x3AD472, AMBER = 0xF3B434;
    /** The hot cues' colours, A to H. */
    private static final int[] CUES = {0x30D26E, 0x1FB7E8, 0x3C6CF0, 0xF2A019, 0xE8442F, 0xC53FE0, 0x2ED9C3, 0xB5D83A};
    /** How far apart (units) what is drawn over what lies, so the nearer wins from any side. */
    private static final float LAYER = .3f;
    private static final int LEFT = 0, MIDDLE = 1, RIGHT = 2;
    /** How wide (units) a column of a running waveform is: 16 seconds of a track across the display, as rekordbox shows. */
    private static final float ZOOM = WIDTH / (16 * Track.COLUMNS);
    /**
     * Its lettering: Barlow Condensed SemiBold as pictures of its letters (tools/ddj_flx4/gen_letters.py), an em of a
     * letter this many pixels in each. The game's own font is not smoothed when it is drawn at another size than its
     * picture's; these are, each line from the smallest picture that is not smaller than it is seen. What these
     * pictures have no letter for comes from {@link DjGlyphs} (Japanese), and what that has not in the game's own font.
     */
    private static final int[] EMS = {64, 32, 16, 8};
    /** A letter's cell in a picture and its pen in its cell, in ems, 16 cells to a row in 12 rows; how tall a capital is. */
    private static final float CELL_WIDE = 1.25f, CELL_TALL = 1.5f, PEN_X = .2f, BASELINE = 1.15f, CAPITAL = .7f;
    /** How large {@link DjGlyphs}' letters are beside these: a Japanese letter fills more of its em than a capital does. */
    private static final float OTHERS = .9f;
    /** How far each cell's letter moves the pen, in thousandths of an em, as gen_letters.py prints it. */
    private static final short[] ADVANCES = {
            200, 269, 306, 606, 434, 772, 581, 152, 279, 279, 361, 439, 199, 325, 211, 386,
            450, 274, 425, 426, 459, 428, 429, 392, 434, 423, 252, 218, 439, 439, 439, 413,
            759, 456, 462, 457, 472, 435, 415, 462, 477, 224, 443, 477, 415, 538, 507, 467,
            457, 455, 461, 434, 452, 478, 471, 665, 460, 456, 406, 330, 386, 330, 413, 405,
            206, 434, 437, 422, 437, 426, 291, 431, 439, 215, 211, 433, 199, 665, 439, 432,
            441, 441, 313, 398, 282, 438, 415, 592, 417, 403, 368, 319, 171, 319, 480, 0,
            200, 269, 422, 512, 547, 528, 171, 419, 346, 807, 286, 463, 439, 325, 435, 391,
            359, 439, 258, 275, 206, 443, 498, 215, 140, 153, 290, 463, 697, 682, 771, 413,
            456, 456, 456, 456, 456, 456, 735, 457, 435, 435, 435, 435, 224, 224, 224, 224,
            507, 507, 467, 467, 467, 467, 467, 389, 597, 478, 478, 478, 478, 456, 457, 450,
            433, 433, 433, 433, 433, 433, 674, 422, 426, 426, 426, 426, 182, 182, 182, 182,
            443, 439, 432, 432, 432, 432, 432, 439, 462, 439, 439, 439, 439, 403, 433, 403,
    };
    /** The pictures as they are drawn from, by picture and then by whether in the world, where lettering is kept off the screen behind it. */
    private static final RenderType[][] LETTERS = new RenderType[EMS.length][2];

    static {
        for (int level = 0; level < EMS.length; level++) {
            for (int world = 0; world < 2; world++) {
                LETTERS[level][world] = RenderType.create("create_media_systems_dj_letters_" + EMS[level] + "_" + world,
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 1536, false, false,
                        RenderType.CompositeState.builder()
                                .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
                                .setTextureState(new RenderStateShard.TextureStateShard(
                                        CreateMediaSystems.id("textures/dj/display_letters_" + EMS[level] + ".png"), true, false))
                                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                                .setLightmapState(RenderStateShard.LIGHTMAP)
                                .setCullState(RenderStateShard.NO_CULL)
                                .setLayeringState(world == 1 ? RenderStateShard.POLYGON_OFFSET_LAYERING : RenderStateShard.NO_LAYERING)
                                .createCompositeState(false));
            }
        }
    }

    private DjDisplay() {
    }

    /**
     * Draws it at the pose's origin, seen {@code pixels} to a unit; in the {@code world} its lettering is kept off the
     * screen behind it.
     */
    public static void draw(View view, PoseStack pose, MultiBufferSource buffers, boolean world, float pixels) {
        Canvas canvas = new Canvas(pose.last().pose(), buffers, world, pixels);
        canvas.fill(0, 0, WIDTH, HEIGHT, 0, BACK);
        for (int deck = 0; deck < 2; deck++) {
            wave(canvas, view, deck, 0, 2 + deck * 46, WIDTH, 44);
            deck(canvas, view, deck, deck == 0 ? 2 : 262, 96, 216, 158);
        }
        // The line both decks play at.
        canvas.upright(WIDTH / 2, 2, 92, 1, 4, 0xFFFFFF);
        mixer(canvas, view.state(), 220, 96, 40, 158);
    }

    /** A deck's waveform running past the moment it plays, which is in the middle. */
    private static void wave(Canvas canvas, View view, int deck, float x, float y, float width, float height) {
        canvas.fill(x, y, x + width, y + height, 1, 0x000000);
        canvas.text(x + 3, y + 2, 6, Integer.toString(deck + 1), FAINT, LEFT, 10);
        Track track = view.tracks()[deck];
        if (track == null) {
            if (view.loaded(deck)) {
                canvas.text(x + width / 2, y + height / 2 - 3, 6, "LOADING", FAINT, MIDDLE, 80);
            }
            return;
        }
        double middle = view.seconds()[deck] * Track.COLUMNS, half = width / 2 / ZOOM;
        float axis = y + height / 2, reach = height / 2 - 3;
        // As many columns to a bar as it takes for a bar to be a pixel wide; bars begin at the same columns whatever plays.
        int stride = Math.max(1, (int) Math.ceil(1 / (ZOOM * canvas.pixels)));
        int first = (int) Math.floor(middle - half);
        first -= Math.floorMod(first, stride);
        for (int column = Math.max(0, first); column <= middle + half && column < track.wave().length; column += stride) {
            int tall = 0, tallest = column;
            for (int in = column; in < column + stride; in++) {
                if (track.height(in) > tall) {
                    tall = track.height(in);
                    tallest = in;
                }
            }
            float left = Math.clamp(x + width / 2 + (float) (column - middle) * ZOOM, x, x + width);
            float right = Math.clamp(x + width / 2 + (float) (column + stride - middle) * ZOOM, x, x + width);
            if (tall != 0 && right > left) {
                canvas.fill(left, axis - reach * tall / 31, right, axis + reach * tall / 31, 2, track.color(tallest));
            }
        }
        double from = (middle - half) * 1000 / Track.COLUMNS, to = (middle + half) * 1000 / Track.COLUMNS;
        int[] beats = track.beats();
        for (int beat = firstFrom(beats, from); beat < beats.length && Track.millis(beats[beat]) <= to; beat++) {
            float at = x + width / 2 + (float) (Track.millis(beats[beat]) * Track.COLUMNS / 1000.0 - middle) * ZOOM;
            boolean bar = Track.inBar(beats[beat]) == 0;
            float mark = bar ? 4 : 2;
            canvas.upright(at, y, y + mark, .6f, 3, bar ? RED : 0xD8D8D8);
            canvas.upright(at, y + height - mark, y + height, .6f, 3, bar ? RED : 0xD8D8D8);
        }
        for (int cue = 0; cue < track.cues().length; cue++) {
            double millis = track.cues()[cue];
            if (millis >= from && millis <= to) {
                float at = x + width / 2 + (float) (millis * Track.COLUMNS / 1000 - middle) * ZOOM;
                canvas.upright(at, y, y + height, .8f, 3, CUES[cue]);
                canvas.fill(at, y, at + 7, y + 7, 3, CUES[cue]);
                canvas.text(at + 3.5f, y + 1, 5, String.valueOf((char) ('A' + cue)), 0x000000, MIDDLE, 6);
            }
        }
    }

    /** The first of {@code beats} at or after {@code millis}. */
    private static int firstFrom(int[] beats, double millis) {
        int low = 0, high = beats.length;
        while (low < high) {
            int middle = low + high >>> 1;
            if (Track.millis(beats[middle]) < millis) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static void deck(Canvas canvas, View view, int deck, float x, float y, float width, float height) {
        DjState state = view.state();
        Track track = view.tracks()[deck];
        double seconds = view.seconds()[deck];
        canvas.fill(x, y, x + width, y + height, 1, PANEL);
        canvas.fill(x + 3, y + 4, x + 14, y + 20, 2, LINE);
        canvas.text(x + 8.5f, y + 8, 8, Integer.toString(deck + 1), TEXT, MIDDLE, 10);
        if (track != null) {
            canvas.text(x + 18, y + 4, 8, track.title(), TEXT, LEFT, width - 100);
            canvas.text(x + 18, y + 14, 6, track.artist(), FAINT, LEFT, width - 130);
            canvas.figures(x + width - 62, y + 14, 6, bpm(track.bpm100() / 100f), FAINT, RIGHT);
            canvas.text(x + width - 62, y + 4, 6, track.key(), GREEN, RIGHT, 24);
            canvas.figures(x + width - 4, y + 4, 7, "-" + clock(Math.max(0, track.seconds() - seconds)), TEXT, RIGHT);
            canvas.figures(x + width - 4, y + 14, 6, clock(seconds), FAINT, RIGHT);
            whole(canvas, track, seconds, x + 3, y + 26, width - 6, 20);
        } else {
            canvas.text(x + 18, y + 8, 7, view.loaded(deck) ? "LOADING" : "NOT LOADED", FAINT, LEFT, width - 30);
            canvas.fill(x + 3, y + 26, x + width - 3, y + 46, 2, 0x000000);
        }

        // The hot cues, in two columns of four.
        for (int cue = 0; cue < 8; cue++) {
            float left = x + 3 + cue / 4 * 50, top = y + 54 + cue % 4 * 15;
            int millis = track == null ? -1 : track.cues()[cue];
            canvas.fill(left, top, left + 48, top + 13, 2, millis >= 0 ? Rgb.mix(CUES[cue], 0, .65f) : 0x202023);
            canvas.fill(left, top, left + 2, top + 13, 3, millis >= 0 ? CUES[cue] : LINE);
            canvas.text(left + 5, top + 3.5f, 6, String.valueOf((char) ('A' + cue)), millis >= 0 ? TEXT : FAINT, LEFT, 8);
            if (millis >= 0) {
                canvas.figures(left + 46, top + 3.5f, 6, clock(millis / 1000.0), TEXT, RIGHT);
            }
        }
        canvas.text(x + 3, y + height - 40, 6, state.padMode[deck].name().replace('_', ' '), BLUE, LEFT, 100);

        boolean playing = view.playing(deck), loaded = view.loaded(deck);
        float buttons = x + 122;
        canvas.ring(buttons, y + 74, 11, 9, 0, 1, 2, loaded ? AMBER : LINE);
        canvas.text(buttons, y + 71.5f, 5, "CUE", loaded ? AMBER : FAINT, MIDDLE, 20);
        canvas.ring(buttons, y + 104, 11, playing ? 0 : 9, 0, 1, 2, playing ? GREEN : loaded ? Rgb.mix(GREEN, 0, .5f) : LINE);
        canvas.text(buttons, y + 101.5f, 5, "PLAY", playing ? 0x000000 : FAINT, MIDDLE, 20);
        badge(canvas, buttons - 13, y + 124, 26, "SYNC", state.lamp(Control.SYNC, deck));

        // The platter: its mark goes round as the record would, with its tempo in the middle.
        float jogX = x + width - 42, jogY = y + 96, turn = (float) (seconds * DjClient.TURNS_PER_SECOND % 1);
        canvas.ring(jogX, jogY, 38, 0, 0, 1, 2, 0x000000);
        canvas.ring(jogX, jogY, 38, 35.5f, 0, 1, 3, loaded ? 0xD8D8D8 : LINE);
        canvas.ring(jogX, jogY, 35.5f, 33, 0, 1, 3, loaded ? BLUE : LINE);
        canvas.ring(jogX, jogY, 39, 32, turn - .012f, turn + .012f, 4, RED);
        if (loaded && state.bpm[deck] > 0) {
            canvas.figures(jogX, jogY - 8, 10, bpm(state.bpm[deck]), TEXT, MIDDLE);
            float recorded = track == null ? 0 : track.bpm100() / 100f;
            if (recorded > 0) {
                canvas.figures(jogX, jogY + 6, 6, String.format(Locale.ROOT, "%+.1f%%", (state.bpm[deck] / recorded - 1) * 100), FAINT,
                        MIDDLE);
            }
        }
        canvas.text(jogX, y + height - 14, 6, "DECK " + (deck + 1), FAINT, MIDDLE, 40);
    }

    /** A whole track in {@code width} units: what has played is dimmed, and a line stands where it is. */
    private static void whole(Canvas canvas, Track track, double seconds, float x, float y, float width, float height) {
        canvas.fill(x, y, x + width, y + height, 2, 0x000000);
        int columns = track.wave().length;
        if (columns == 0) {
            return;
        }
        float at = (float) Math.clamp(seconds * Track.COLUMNS / columns, 0, 1) * width;
        // A bar to a pixel, and no finer than four to a unit.
        int bars = (int) (width * Math.clamp(canvas.pixels, 1, 4));
        for (int bar = 0; bar < bars; bar++) {
            int from = (int) ((long) bar * columns / bars), to = Math.max(from + 1, (int) ((long) (bar + 1) * columns / bars));
            int tall = 0, tallest = from;
            // ponytail: every tenth column of a long track is looked at, which can miss a lone peak.
            for (int column = from; column < to; column += Math.max(1, (to - from) / 10)) {
                if (track.height(column) > tall) {
                    tall = track.height(column);
                    tallest = column;
                }
            }
            if (tall != 0) {
                float left = x + bar * width / bars, top = height * (1 - tall / 31f);
                int color = track.color(tallest);
                canvas.fill(left, y + top, x + (bar + 1) * width / bars, y + height, 3, left - x < at ? Rgb.mix(color, 0, .6f) : color);
            }
        }
        for (int cue = 0; cue < track.cues().length; cue++) {
            if (track.cues()[cue] >= 0) {
                float mark = x + Math.min(width, track.cues()[cue] * Track.COLUMNS / 1000f / columns * width);
                canvas.upright(mark, y, y + 4, 1, 4, CUES[cue]);
            }
        }
        canvas.upright(x + at, y - 1, y + height + 1, 1, 4, 0xFFFFFF);
    }

    /** The mixer: each channel's knobs and fader where the controller has them, and the crossfader under them. */
    private static void mixer(Canvas canvas, DjState state, float x, float y, float width, float height) {
        canvas.fill(x, y, x + width, y + height, 1, PANEL);
        Control[] knobs = {Control.TRIM, Control.HI, Control.MID, Control.LOW};
        String[] names = {"TRIM", "HIGH", "MID", "LOW"};
        for (int channel = 0; channel < 2; channel++) {
            float middle = x + 10 + channel * 20;
            for (int knob = 0; knob < knobs.length; knob++) {
                float top = y + 4 + knob * 22;
                // An arc as far round as the knob is turned, from seven o'clock.
                float place = state.place(knobs[knob], channel) * Control.KNOB_SWEEP / 360;
                float start = .5f + (1 - Control.KNOB_SWEEP / 360) / 2;
                canvas.ring(middle, top + 7, 6, 4.5f, 0, 1, 2, LINE);
                canvas.ring(middle, top + 7, 6, 4.5f, start, start + place, 3, TEXT);
                canvas.text(middle, top + 15, 5, names[knob], FAINT, MIDDLE, 20);
            }
            badge(canvas, middle - 8, y + 94, 16, "CUE", state.lamp(Control.CHANNEL_CUE, channel));
            float top = y + 108, bottom = y + 142, cap = bottom - state.place(Control.CHANNEL_FADER, channel) * (bottom - top);
            canvas.upright(middle, top, bottom, 1, 2, LINE);
            canvas.upright(middle, cap, bottom, 1, 3, BLUE);
            canvas.fill(middle - 4, cap - 1.5f, middle + 4, cap + 1.5f, 4, TEXT);
        }
        float left = x + 5, right = x + width - 5, cap = left + state.place(Control.CROSSFADER, 0) * (right - left);
        canvas.fill(left, y + 150, right, y + 151, 2, LINE);
        canvas.fill(cap - 1.5f, y + 146.5f, cap + 1.5f, y + 154.5f, 4, TEXT);
    }

    private static void badge(Canvas canvas, float x, float y, float width, String name, boolean on) {
        canvas.fill(x, y, x + width, y + 9, 2, on ? BLUE : 0x232326);
        canvas.text(x + width / 2, y + 2, 5, name, on ? 0xFFFFFF : FAINT, MIDDLE, width - 2);
    }

    private static String bpm(float bpm) {
        return String.format(Locale.ROOT, "%.2f", bpm);
    }

    /** Minutes, seconds and tenths, as rekordbox writes a time. */
    private static String clock(double seconds) {
        int tenths = (int) (seconds * 10);
        return String.format(Locale.ROOT, "%02d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    /** What it is drawn with: plain rectangles and rings, and lettering, each on its layer. */
    private static final class Canvas {
        private final Matrix4f at;
        private final MultiBufferSource buffers;
        private final VertexConsumer flat;
        private final boolean world;
        /** How many pixels a unit is seen as. */
        final float pixels;

        Canvas(Matrix4f at, MultiBufferSource buffers, boolean world, float pixels) {
            this.at = at;
            this.buffers = buffers;
            this.world = world;
            this.pixels = Math.max(pixels, .01f);
            flat = new QuadOutput(buffers, QuadOutput.FLAT);
        }

        void fill(float x0, float y0, float x1, float y1, int layer, int color) {
            float z = layer * LAYER;
            vertex(x0, y0, z, color);
            vertex(x0, y1, z, color);
            vertex(x1, y1, z, color);
            vertex(x1, y0, z, color);
        }

        /** An upright line about x, {@code wide} units wide and a pixel at the least, so that it is not lost between pixels. */
        void upright(float x, float y0, float y1, float wide, int layer, int color) {
            float half = Math.max(wide, 1 / pixels) / 2;
            fill(x - half, y0, x + half, y1, layer, color);
        }

        /**
         * A ring about (x, y) from {@code from} to {@code to} of a turn, clockwise from twelve o'clock; a disk if
         * {@code inner} is 0. It has a side to every three pixels of its rim.
         */
        void ring(float x, float y, float outer, float inner, float from, float to, int layer, int color) {
            float z = layer * LAYER;
            int around = Math.clamp(Math.round(2 * (float) Math.PI * outer * pixels / 3), 16, 160);
            int segments = Math.max(1, Math.round((to - from) * around));
            for (int i = 0; i < segments; i++) {
                double a = (from + (to - from) * i / segments) * 2 * Math.PI, b = (from + (to - from) * (i + 1) / segments) * 2 * Math.PI;
                float sa = (float) Math.sin(a), ca = -(float) Math.cos(a), sb = (float) Math.sin(b), cb = -(float) Math.cos(b);
                vertex(x + outer * sa, y + outer * ca, z, color);
                vertex(x + inner * sa, y + inner * ca, z, color);
                vertex(x + inner * sb, y + inner * cb, z, color);
                vertex(x + outer * sb, y + outer * cb, z, color);
            }
        }

        private void vertex(float x, float y, float z, int color) {
            flat.addVertex(at, x, y, z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255);
        }

        /** {@code line} with its capitals' top at {@code y}, {@code size} tall, cut to {@code width}; {@code x} is its left, middle or right. */
        void text(float x, float y, float size, String line, int color, int align, float width) {
            letters(x, y, size, line, color, align, width, false);
        }

        /**
         * A time or a tempo: as {@link #text}, uncut, each of its figures as wide as a nought, so that it does not
         * shift as its figures change. The typeface's own figures are of different widths.
         */
        void figures(float x, float y, float size, String line, int color, int align) {
            letters(x, y, size, line, color, align, Float.MAX_VALUE, true);
        }

        private void letters(float x, float y, float size, String line, int color, int align, float width, boolean even) {
            Font font = Minecraft.getInstance().font;
            // As large as the game's own letters at this size, whose capitals are 7 of the 8 that a size is.
            float em = size * 7 / 8 / CAPITAL, wide = 0;
            int end = 0;
            while (end < line.length()) {
                int letter = line.codePointAt(end);
                float step = step(font, letter, size, em, even);
                if (wide + step > width) {
                    break;
                }
                wide += step;
                end += Character.charCount(letter);
            }
            int level = EMS.length - 1;
            while (level > 0 && EMS[level] < em * pixels) {
                level--;
            }
            VertexConsumer out = new QuadOutput(buffers, LETTERS[level][world ? 1 : 0]);
            VertexConsumer others = new QuadOutput(buffers, DjGlyphs.TYPES[world ? 1 : 0]);
            float pen = x - (align == LEFT ? 0 : align == MIDDLE ? wide / 2 : wide), z = 5 * LAYER;
            // ponytail: each letter by its own width, no pair set closer or wider as the typeface would have some.
            for (int i = 0; i < end;) {
                int letter = line.codePointAt(i), cell = cell(letter);
                float step = step(font, letter, size, em, even);
                if (cell >= 0) {
                    // In the middle of its step, which is not its own width only for a figure among even ones.
                    float x0 = pen + (step - ADVANCES[cell] * em / 1000) / 2 - PEN_X * em, y0 = y + (CAPITAL - BASELINE) * em;
                    float x1 = x0 + CELL_WIDE * em, y1 = y0 + CELL_TALL * em, u = cell % 16 / 16f, v = cell / 16 / 12f;
                    letter(out, x0, y0, z, u, v, color);
                    letter(out, x0, y1, z, u, v + 1 / 12f, color);
                    letter(out, x1, y1, z, u + 1 / 16f, v + 1 / 12f, color);
                    letter(out, x1, y0, z, u + 1 / 16f, v, color);
                } else if (DjGlyphs.get().glyph(letter) instanceof DjGlyphs.Glyph glyph) {
                    if (glyph.wide() > 0) {
                        float other = em * OTHERS, x0 = pen + glyph.left() * other, y0 = y + CAPITAL * em - glyph.top() * other;
                        float x1 = x0 + glyph.wide() * other, y1 = y0 + glyph.tall() * other;
                        letter(others, x0, y0, z, glyph.u0(), glyph.v0(), color);
                        letter(others, x0, y1, z, glyph.u0(), glyph.v1(), color);
                        letter(others, x1, y1, z, glyph.u1(), glyph.v1(), color);
                        letter(others, x1, y0, z, glyph.u1(), glyph.v0(), color);
                    }
                } else {
                    Matrix4f placed = new Matrix4f(at).translate(pen, y, z).scale(size / 8, size / 8, 1);
                    font.drawInBatch(Character.toString(letter), 0, 0, color | 0xFF000000, false, placed, buffers,
                            world ? Font.DisplayMode.POLYGON_OFFSET : Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
                }
                pen += step;
                i += Character.charCount(letter);
            }
        }

        private void letter(VertexConsumer out, float x, float y, float z, float u, float v, int color) {
            out.addVertex(at, x, y, z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255).setUv(u, v)
                    .setLight(LightTexture.FULL_BRIGHT);
        }

        /** The cell of the pictures {@code letter} is in, or -1 if they have it not. */
        private static int cell(int letter) {
            return letter >= 0x20 && letter < 0x7F ? letter - 0x20 : letter >= 0xA0 && letter <= 0xFF ? letter - 0x40 : -1;
        }

        /** How far (units) {@code letter} moves the pen. */
        private static float step(Font font, int letter, float size, float em, boolean even) {
            int cell = cell(even && letter >= '0' && letter <= '9' ? '0' : letter);
            if (cell >= 0) {
                return ADVANCES[cell] * em / 1000;
            }
            DjGlyphs.Glyph glyph = DjGlyphs.get().glyph(letter);
            return glyph != null ? glyph.advance() * em * OTHERS : font.width(Character.toString(letter)) * size / 8;
        }
    }
}
