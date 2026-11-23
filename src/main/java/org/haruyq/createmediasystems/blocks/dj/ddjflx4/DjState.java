package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.PadMode;

/**
 * How a DDJ-FLX4 stands: where each of its controls is, which of its lamps are lit, and what each deck of the
 * rekordbox it works plays. Its host's client tells it (see DdjFlx4Block.Entity); everyone near sees it. Sent and
 * saved as {@link #bytes}.
 */
public final class DjState {
    /** What the host has going, as bits of {@link #flags}: the controller itself, rekordbox, its sound. */
    public static final int HARDWARE = 1, REKORDBOX = 2, SOUND = 4;
    /** The most a state is as bytes. */
    public static final int MAX_BYTES = 512;

    /** A deck's track as rekordbox analysed it; sent apart from the state, in parts (see DdjFlx4Block.TrackPart). */
    public record Track(int id, String title, String artist, String key, int bpm100, int seconds, int[] beats, short[] wave,
            int[] cues) {
        /** Columns of {@link #wave} in a second. */
        public static final int COLUMNS = 75;
        /** The longest track that is sent whole: the rest of a longer one's waveform is left off. */
        private static final int MAX_COLUMNS = 90 * 60 * COLUMNS / 4, MAX_BEATS = 20_000;

        public byte[] bytes() {
            byte[][] texts = {utf(title), utf(artist), utf(key)};
            int columns = Math.min(wave.length, MAX_COLUMNS), count = Math.min(beats.length, MAX_BEATS);
            ByteBuffer out = ByteBuffer.allocate(64 + texts[0].length + texts[1].length + texts[2].length + count * 4 + columns * 2);
            out.putInt(id).putInt(bpm100).putInt(seconds);
            for (byte[] text : texts) {
                out.putShort((short) text.length).put(text);
            }
            out.putInt(count);
            for (int i = 0; i < count; i++) {
                out.putInt(beats[i]);
            }
            out.putInt(columns);
            for (int i = 0; i < columns; i++) {
                out.putShort(wave[i]);
            }
            for (int i = 0; i < 8; i++) {
                out.putInt(i < cues.length ? cues[i] : -1);
            }
            return java.util.Arrays.copyOf(out.array(), out.position());
        }

        /** The track {@code bytes} are; null if they are none. */
        public static Track of(byte[] bytes) {
            try {
                ByteBuffer in = ByteBuffer.wrap(bytes);
                int id = in.getInt(), bpm100 = in.getInt(), seconds = in.getInt();
                String[] texts = new String[3];
                for (int i = 0; i < 3; i++) {
                    byte[] text = new byte[in.getShort() & 0xFFFF];
                    in.get(text);
                    texts[i] = new String(text, StandardCharsets.UTF_8);
                }
                int[] beats = new int[Math.clamp(in.getInt(), 0, MAX_BEATS)];
                for (int i = 0; i < beats.length; i++) {
                    beats[i] = in.getInt();
                }
                short[] wave = new short[Math.clamp(in.getInt(), 0, MAX_COLUMNS)];
                for (int i = 0; i < wave.length; i++) {
                    wave[i] = in.getShort();
                }
                int[] cues = new int[8];
                for (int i = 0; i < 8; i++) {
                    cues[i] = in.getInt();
                }
                return new Track(id, texts[0], texts[1], texts[2], bpm100, seconds, beats, wave, cues);
            } catch (BufferUnderflowException truncated) {
                return null;
            }
        }

        private static byte[] utf(String text) {
            byte[] all = text.getBytes(StandardCharsets.UTF_8);
            return all.length <= 200 ? all : new String(all, 0, 200, StandardCharsets.UTF_8).replace("�", "")
                    .getBytes(StandardCharsets.UTF_8);
        }

        /** The beat a bar begins with has 0 here. */
        public static int inBar(int beat) {
            return beat >>> 30;
        }

        public static int millis(int beat) {
            return beat & 0x3FFFFFFF;
        }

        /** How tall (0 to 31) and what colour (0xRRGGBB) column {@code column} of the waveform is. */
        public int height(int column) {
            return column < 0 || column >= wave.length ? 0 : wave[column] >> 2 & 31;
        }

        public int color(int column) {
            int entry = column < 0 || column >= wave.length ? 0 : wave[column];
            return (entry >> 13 & 7) * 255 / 7 << 16 | (entry >> 10 & 7) * 255 / 7 << 8 | (entry >> 7 & 7) * 255 / 7;
        }
    }

    /** Each control's value, by its slot: 0 to Control#FULL for a knob or fader, 1 while a button is down, a switch's place. */
    public final short[] controls = new short[Control.SLOTS];
    /** The buttons that stay lit once pressed (see {@link #latch}), as bits. */
    public int lamps;
    public final PadMode[] padMode = {PadMode.HOT_CUE, PadMode.HOT_CUE};
    /** The track each deck has loaded (0: none), as its Track#id. */
    public final int[] track = new int[2];
    /** Where each deck was (seconds) when this was told, how fast it runs (1: as recorded, 0: it stands) and its tempo now. */
    public final float[] position = new float[2], rate = new float[2], bpm = new float[2];
    public int flags;
    /** On a client: when (System#nanoTime) it was told this. Not sent. */
    public long toldAt;

    public DjState() {
        for (Control control : Control.values()) {
            for (int deck = 0; deck < control.count(); deck++) {
                controls[control.slot(deck)] = (short) control.rest();
            }
        }
    }

    /** The state {@code bytes} are; what they leave out is as on a new one. */
    public DjState(byte[] bytes) {
        this();
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            for (int slot = 0; slot < controls.length; slot++) {
                controls[slot] = (short) Math.clamp(in.getShort(), 0, Control.FULL);
            }
            lamps = in.getInt();
            for (int deck = 0; deck < 2; deck++) {
                padMode[deck] = PadMode.values()[in.get() & 7];
                track[deck] = in.getInt();
                position[deck] = finite(in.getFloat());
                rate[deck] = Math.clamp(finite(in.getFloat()), -4, 4);
                bpm[deck] = finite(in.getFloat());
            }
            flags = in.get() & 255;
        } catch (BufferUnderflowException older) {
            // as far as they went
        }
    }

    /**
     * How fast a deck is told to run, from how fast it was just seen to move, in track seconds a second: as fast as
     * its tempo says ({@code nominal}) while it plays about that fast, as it moves while it is scratched or braked,
     * and as {@code before} over a jump. Read from a real DDJ-FLX4 at 20 reads a second, a playing deck was seen up
     * to 0.25 off its tempo, a scratch between -2.3 and 1.7, a spin between 4 and 10 for up to 2 seconds, and a
     * load, a cue or a beat jump beyond 20.
     */
    public static float rate(boolean loaded, double speed, float nominal, float before) {
        if (!loaded || Math.abs(speed) < .02) {
            return 0;
        }
        if (Math.abs(speed - nominal) < .25 * nominal) {
            return nominal;
        }
        // ponytail: a spin faster than 8 reads as a jump; it is then told by its place alone, tick by tick.
        return Math.abs(speed) > 8 ? before : (float) Math.clamp(speed, -4, 4);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0;
    }

    public byte[] bytes() {
        ByteBuffer out = ByteBuffer.allocate(controls.length * 2 + 4 + 2 * 17 + 1);
        for (short control : controls) {
            out.putShort(control);
        }
        out.putInt(lamps);
        for (int deck = 0; deck < 2; deck++) {
            out.put((byte) padMode[deck].ordinal()).putInt(track[deck]).putFloat(position[deck]).putFloat(rate[deck])
                    .putFloat(bpm[deck]);
        }
        return out.put((byte) flags).array();
    }

    public int value(Control control, int deck) {
        return controls[control.slot(deck)];
    }

    /**
     * A knob's or fader's place, 0 to 1: a fader's from its near end. The tempo fader's value is the other way
     * round: it is fullest nearest, where the deck is fastest.
     */
    public float place(Control control, int deck) {
        float place = value(control, deck) / (float) Control.FULL;
        return control == Control.TEMPO ? 1 - place : place;
    }

    public boolean down(Control control, int deck) {
        return value(control, deck) != 0;
    }

    public boolean lamp(Control control, int deck) {
        int latch = latch(control);
        return latch >= 0 && (lamps >> latch + deck & 1) != 0;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** The bit of {@link #lamps} for a button whose lamp stays lit once pressed, till it is pressed again; -1 for any other. */
    private static int latch(Control control) {
        return switch (control) {
            case SYNC -> 0;
            case CHANNEL_CUE -> 2;
            case MASTER_CUE -> 4;
            case FX_ON -> 5;
            case SMART_CFX -> 6;
            case SMART_FADER -> 7;
            default -> -1;
        };
    }

    /**
     * A control of it was worked, as ControlMidi.Sink#set tells: its value is kept, a latching button's lamp changes
     * as it goes down, and a pad mode button picks its deck's pad mode.
     */
    public void set(int slot, int value) {
        Control control = Control.ofSlot(slot);
        int deck = slot & 1;
        boolean pressed = value != 0 && controls[slot] == 0;
        controls[slot] = (short) Math.clamp(value, 0, Control.FULL);
        if (!pressed || control.kind != Control.Kind.BUTTON) {
            return;
        }
        // ponytail: lamps are worked out from the presses seen, not read from rekordbox, so one switched there with the
        // mouse is not followed; read rekordbox's lamp messages from a loopback port if that matters.
        if (latch(control) >= 0) {
            lamps ^= 1 << latch(control) + deck;
        }
        PadMode mode = PadMode.of(control, down(Control.SHIFT, deck));
        if (mode != null) {
            padMode[deck] = mode;
        }
    }
}
