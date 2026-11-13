package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import net.minecraft.nbt.CompoundTag;

public final class KeyboardSettings {
    public static final String DEFAULT_FONT = "GeneralUser GS";
    public static final int DRUM_BANK = 128;

    public record Tone(int bank, int program) {
        public Tone {
            bank = Math.clamp(bank, 0, 16383);
            program = Math.clamp(program, 0, 127);
        }
        public static Tone fromMidiBank(int bank, int program) {
            return new Tone(bank >>> 7 == 120 ? DRUM_BANK : bank, program);
        }
        int pack() { return bank << 7 | program; }
        static Tone from(int value) { return new Tone(value >>> 7, value & 127); }
    }

    public String font = DEFAULT_FONT;
    public Tone main = new Tone(0, 0), layer = new Tone(0, 48), lower = new Tone(0, 33);
    public boolean layered, split;
    public int volume = 96, splitKey = 60, transpose, touch = 2, reverb = 40, chorus, bpm = 120, beats = 4, polyphony = 192;

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("Font", font);
        tag.putIntArray("Values", new int[] {main.pack(), layer.pack(), lower.pack(), layered ? 1 : 0, split ? 1 : 0,
                volume, splitKey, transpose, touch, reverb, chorus, bpm, beats, polyphony});
        return tag;
    }

    public static KeyboardSettings load(CompoundTag tag) {
        var s = new KeyboardSettings();
        if (tag.contains("Font")) s.font = fontName(tag.getString("Font"));
        var v = tag.getIntArray("Values");
        if (v.length != 14) return s;
        s.main = Tone.from(v[0]);
        s.layer = Tone.from(v[1]);
        s.lower = Tone.from(v[2]);
        s.layered = v[3] != 0;
        s.split = v[4] != 0;
        s.volume = Math.clamp(v[5], 0, 127);
        s.splitKey = Math.clamp(v[6], 22, 108);
        s.transpose = Math.clamp(v[7], -36, 36);
        s.touch = Math.clamp(v[8], 0, 4);
        s.reverb = Math.clamp(v[9], 0, 127);
        s.chorus = Math.clamp(v[10], 0, 127);
        s.bpm = Math.clamp(v[11], 20, 300);
        s.beats = Math.clamp(v[12], 1, 12);
        s.polyphony = Math.clamp(v[13], 32, 256);
        return s;
    }

    public static String fontName(String input) {
        String name = input.strip();
        return name.length() <= 96 && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> c < 32 || c == '\\' || c == '/' || c == ':') ? name : DEFAULT_FONT;
    }
}
