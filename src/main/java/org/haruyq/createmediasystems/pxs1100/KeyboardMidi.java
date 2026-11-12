package org.haruyq.createmediasystems.pxs1100;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record KeyboardMidi(int status, int key, int value) {
    public static final int OFF = 0x80, ON = 0x90, CONTROL = 0xB0, PROGRAM = 0xC0, BEND = 0xE0;
    public static final int SUSTAIN = 64, SOSTENUTO = 66, SOFT = 67, CLICK = 84, SILENCE = 120, RESET = 121, NOTES_OFF = 123;
    public static final StreamCodec<ByteBuf, KeyboardMidi> CODEC = ByteBufCodecs.VAR_INT.map(KeyboardMidi::unpack, KeyboardMidi::pack);

    public static KeyboardMidi note(int key, int velocity) {
        return new KeyboardMidi(velocity == 0 ? OFF : ON, key, velocity);
    }

    public static KeyboardMidi cc(int key, int value) { return new KeyboardMidi(CONTROL, key, value); }
    public int command() { return status & 0xf0; }
    public int channel() { return status & 15; }
    public int bend() { return key | value << 7; }

    public boolean valid() {
        if (status < 0x80 || status > 0xef || key < 0 || key > 127 || value < 0 || value > 127) return false;
        return switch (command()) {
            case ON, OFF, PROGRAM, BEND -> true;
            case CONTROL -> switch (key) {
                case 0, 7, 11, 32, SUSTAIN, SOSTENUTO, SOFT, SILENCE, RESET, NOTES_OFF -> true;
                case CLICK -> channel() == 9 && (value == 1 || value == 2);
                default -> false;
            };
            default -> false;
        };
    }

    int pack() { return status << 14 | key << 7 | value; }

    public boolean metronome() { return command() == CONTROL && key == CLICK; }

    static KeyboardMidi unpack(int packed) {
        var event = new KeyboardMidi(packed >>> 14, packed >>> 7 & 127, packed & 127);
        if (!event.valid()) throw new DecoderException("Invalid keyboard MIDI event");
        return event;
    }
}
