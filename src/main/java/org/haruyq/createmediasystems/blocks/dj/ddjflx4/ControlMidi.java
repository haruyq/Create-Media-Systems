package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.Kind;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.PadMode;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.Port;

/**
 * The DDJ-FLX4's MIDI, both ways: what the controller sends read into what its controls did ({@link #read}), and what
 * a control does written as the controller would send it ({@link #press}, {@link #move}, {@link #turn}). A message is
 * an int as javax.sound.midi packs it: its status, then its two data bytes, from the low byte up.
 */
public final class ControlMidi {
    /** What a controller's messages come to. */
    public interface Sink {
        /** A button, a pad or the jog wheel's top went down (1) or up (0), a knob or fader came to stand at {@code value}, a switch at a place. */
        void set(int slot, int value);

        /** The jog wheel or the browse knob turned {@code steps}, clockwise if positive. */
        void turn(int slot, int steps);

        /** A pad of {@code mode} was pressed on {@code deck}: that is the mode its pads are in. */
        void mode(int deck, PadMode mode);
    }

    private static final int NOTE = 0x90, CHANGE = 0xB0;
    private static final int MIXER = 6, FX = 4, PADS = 7;
    /** The beat FX channel switch's three places, as notes on the first beat FX's channel. */
    private static final int[] FX_CHANNEL = {0x10, 0x11, 0x14};
    /** A status and first data byte, to the slot it belongs to. */
    private static final Map<Integer, Integer> KEYS = new HashMap<>();

    static {
        for (Control control : Control.values()) {
            for (int deck = 0; deck < control.count(); deck++) {
                int slot = control.slot(deck);
                int status = control.kind.pressed() ? NOTE : CHANGE;
                for (int channel : channels(control, deck, -1)) {
                    if (control.port == Port.NONE || control.port == Port.PADS) {
                        continue;
                    }
                    key(status | channel, number(control, deck, false), slot);
                    if (control.shifted >= 0) {
                        key(status | channel, number(control, deck, true), slot);
                    }
                }
            }
        }
        for (int deck = 0; deck < 2; deck++) {
            int jog = Control.JOG.slot(deck), sync = Control.SYNC.slot(deck);
            key(NOTE | deck, Control.JOG_TOUCH, jog);
            key(NOTE | deck, Control.JOG_TOUCH_SHIFTED, jog);
            key(NOTE | deck, Control.SYNC_LONG, sync);
            key(CHANGE | deck, Control.JOG_RING, jog);
            key(CHANGE | deck, Control.JOG_BEND, jog);
            key(NOTE | MIXER, deck == 0 ? Control.LOAD_SHIFTED_1 : Control.LOAD_SHIFTED_2, Control.LOAD.slot(deck));
        }
        key(NOTE | MIXER, Control.BROWSE_PRESS, Control.BROWSE.slot(0));
        key(NOTE | MIXER, Control.BROWSE_PRESS_SHIFTED, Control.BROWSE.slot(0));
    }

    /** The most significant half of each continuous control's value, till its other half comes. */
    private final int[] coarse = new int[Control.SLOTS];

    private static void key(int status, int number, int slot) {
        KEYS.put(status << 8 | number, slot);
    }

    private static int[] channels(Control control, int deck, int fxChannel) {
        return switch (control.port) {
            case DECK -> new int[]{deck};
            case MIXER -> new int[]{MIXER};
            // Read from either; written to the one its switch stands at.
            case FX -> fxChannel < 0 ? new int[]{FX, FX + 1} : new int[]{FX + (fxChannel == 1 ? 1 : 0)};
            default -> new int[0];
        };
    }

    /** LOAD and CFX are numbered one after the other for the two decks; every other control has its deck's channel. */
    private static int number(Control control, int deck, boolean shifted) {
        int number = shifted && control.shifted >= 0 ? control.shifted : control.number;
        return control.port == Port.MIXER && control.apart != 0 ? number + deck : number;
    }

    /** What {@code message} from the controller did, told to {@code sink}; nothing for a message that is none of its. */
    public void read(int message, Sink sink) {
        int status = message & 0xFF, first = message >> 8 & 0x7F, second = message >> 16 & 0x7F;
        int command = status & 0xF0, channel = status & 0x0F;
        if (command == 0x80) {
            command = NOTE;
            second = 0;
        }
        if (command == NOTE && channel >= PADS && channel <= PADS + 3) {
            int deck = channel - PADS >> 1, pad = first & 15;
            if (pad < 8) {
                if (second != 0) {
                    sink.mode(deck, PadMode.values()[first >> 4]);
                }
                sink.set(Control.values()[Control.PAD_1.ordinal() + pad].slot(deck), second != 0 ? 1 : 0);
            }
            return;
        }
        if (command == NOTE && channel == FX) {
            for (int place = 0; place < FX_CHANNEL.length; place++) {
                if (first == FX_CHANNEL[place] && second != 0) {
                    sink.set(Control.FX_CHANNEL.slot(0), place);
                    return;
                }
            }
        }
        Integer slot = KEYS.get((command | channel) << 8 | first);
        // The least significant half of a continuous control is numbered 0x20 above its other half; the jog wheel's
        // own numbers lie among those, and are none.
        boolean fine = slot == null && command == CHANGE && first >= 0x20 && first < 0x40;
        if (fine) {
            slot = KEYS.get((command | channel) << 8 | first - 0x20);
        }
        if (slot == null) {
            return;
        }
        Kind kind = Control.ofSlot(slot).kind;
        if (command == NOTE) {
            sink.set(slot, second != 0 ? 1 : 0);
        } else if (kind == Kind.JOG) {
            sink.turn(slot, second - 0x40);
        } else if (kind == Kind.ENCODER) {
            sink.turn(slot, second < 0x40 ? second : second - 0x80);
        } else if (!kind.continuous()) {
            return;
        } else if (fine) {
            sink.set(slot, coarse[slot] << 7 | second);
        } else {
            coarse[slot] = second;
        }
    }

    /**
     * A button, a pad, the jog wheel's top or the browse knob going down or up, or a switch coming to stand at
     * {@code value}: {@code shifted} with its deck's SHIFT held, {@code mode} the pad mode of its deck, {@code fxChannel}
     * where the beat FX channel switch stands.
     */
    public static void press(int slot, int value, boolean shifted, PadMode mode, int fxChannel, IntConsumer out) {
        Control control = Control.ofSlot(slot);
        int deck = slot & 1, velocity = value != 0 ? 0x7F : 0;
        switch (control.kind) {
            case SWITCH -> out.accept(message(NOTE | FX, FX_CHANNEL[Math.clamp(value, 0, 2)], 0x7F));
            case JOG -> out.accept(message(NOTE | deck, shifted ? Control.JOG_TOUCH_SHIFTED : Control.JOG_TOUCH, velocity));
            case ENCODER -> out.accept(message(NOTE | MIXER, shifted ? Control.BROWSE_PRESS_SHIFTED : Control.BROWSE_PRESS, velocity));
            case PAD -> out.accept(message(NOTE | PADS + deck * 2 + (shifted ? 1 : 0), mode.note() + control.number, velocity));
            case BUTTON -> {
                int number = control == Control.LOAD && shifted ? deck == 0 ? Control.LOAD_SHIFTED_1 : Control.LOAD_SHIFTED_2
                        : number(control, deck, shifted);
                for (int channel : channels(control, deck, fxChannel)) {
                    out.accept(message(NOTE | channel, number, velocity));
                }
            }
            default -> {
            }
        }
    }

    /** A knob or fader coming to stand at {@code value} (0 to Control#FULL). */
    public static void move(int slot, int value, boolean shifted, int fxChannel, IntConsumer out) {
        Control control = Control.ofSlot(slot);
        if (!control.kind.continuous()) {
            return;
        }
        int number = number(control, slot & 1, shifted);
        for (int channel : channels(control, slot & 1, fxChannel)) {
            out.accept(message(CHANGE | channel, number, value >> 7 & 0x7F));
            out.accept(message(CHANGE | channel, number + 0x20, value & 0x7F));
        }
    }

    /** The jog wheel's top or the browse knob turning {@code steps}. */
    /**
     * rekordbox's own mapping of the controller, the text of its DDJ-FLX4.midi.csv, as rekordbox's MIDI settings can
     * import it for a port that gives back what it is sent: named for {@code port}, and with nothing sent out, since
     * each lamp rekordbox lit there would come back to it as a press of that lamp's button.
     */
    public static String mapping(String csv, String port) {
        List<String> out = new ArrayList<>();
        for (String line : csv.split("\r?\n", -1)) {
            String[] cells = line.split(",", -1);
            if (out.isEmpty() && cells.length > 2) {
                cells[2] = port;
            } else if (cells.length == 15 && !cells[0].isEmpty() && !cells[0].startsWith("#")) {
                Arrays.fill(cells, 8, 13, "");
                if (String.join("", Arrays.copyOfRange(cells, 3, 8)).isEmpty()) {
                    // a lamp alone
                    continue;
                }
            }
            out.add(String.join(",", cells));
        }
        return String.join("\r\n", out);
    }

    public static void turn(int slot, int steps, boolean shifted, IntConsumer out) {
        Control control = Control.ofSlot(slot);
        int deck = slot & 1;
        // A message carries at most 63 steps either way.
        for (int left = steps; left != 0; ) {
            int now = Math.clamp(left, -63, 63);
            left -= now;
            if (control.kind == Kind.JOG) {
                out.accept(message(CHANGE | deck, shifted ? control.shifted : control.number, 0x40 + now));
            } else if (control.kind == Kind.ENCODER) {
                out.accept(message(CHANGE | MIXER, shifted ? control.shifted : control.number, now & 0x7F));
            }
        }
    }

    private static int message(int status, int first, int second) {
        return status | first << 8 | second << 16;
    }
}
