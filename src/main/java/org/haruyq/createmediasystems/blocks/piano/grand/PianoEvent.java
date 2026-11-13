package org.haruyq.createmediasystems.blocks.piano.grand;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record PianoEvent(Kind kind, int number, int value) {
    public static final int FIRST_NOTE = 21, LAST_NOTE = 108, KEYS = 88, MAX_VELOCITY = 16_383;
    public static final int VOLUME = 7, EXPRESSION = 11, DAMPER = 64, SOSTENUTO = 66, SOFT = 67;
    public static final int ALL_SOUND_OFF = 120, RESET_CONTROLLERS = 121, ALL_NOTES_OFF = 123;

    public enum Kind { ON, OFF, CONTROL }

    public static final StreamCodec<ByteBuf, PianoEvent> CODEC = ByteBufCodecs.VAR_INT.map(PianoEvent::unpack, PianoEvent::pack);

    public static boolean acceptsControl(int controller) {
        return switch (controller) {
            case VOLUME, EXPRESSION, DAMPER, SOSTENUTO, SOFT, ALL_SOUND_OFF, RESET_CONTROLLERS, ALL_NOTES_OFF -> true;
            default -> false;
        };
    }

    public static boolean isNote(int note) {
        return note >= FIRST_NOTE && note <= LAST_NOTE;
    }

    public static boolean black(int note) {
        return switch (note % 12) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    public static PianoEvent press(int note, int velocity) {
        return new PianoEvent(Kind.ON, note, velocity * 129);
    }

    public static PianoEvent release(int note, int velocity) {
        return new PianoEvent(Kind.OFF, note, velocity * 129);
    }

    public static PianoEvent control(int controller, int value) {
        return new PianoEvent(Kind.CONTROL, controller, value);
    }

    private int pack() {
        return (kind.ordinal() << 21) | (number << 14) | value;
    }

    private static PianoEvent unpack(int packed) {
        int kind = packed >>> 21, number = (packed >>> 14) & 127, value = packed & MAX_VELOCITY;
        if (kind > 2 || (kind == 2 ? !acceptsControl(number) || value > 127 : !isNote(number) || kind == 0 && value == 0)) {
            throw new DecoderException("Invalid piano event");
        }
        return new PianoEvent(Kind.values()[kind], number, value);
    }
}
