package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardKeys;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.junit.jupiter.api.Test;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardSynthTest {
    @Test void midiChannelTenPlaysEveryPianoKeyIncludingSameBatchReleases() throws Exception {
        var bank = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
        var settings = new KeyboardSettings();
        settings.reverb = 0;
        var synth = new KeyboardSynth(bank, 192);
        for (int key = KeyboardLayout.FIRST; key <= KeyboardLayout.LAST; key++) {
            synth.apply(settings, true, List.of());
            synth.apply(settings, false, List.of(new KeyboardMidi(ON | 9, key, 36), new KeyboardMidi(OFF | 9, key, 64)));
            assertTrue(peak(synth, 8) > 120, "MIDI channel 10 lost note " + key);
        }
        synth.apply(settings, true, List.of(new KeyboardMidi(CONTROL | 9, 0, 120),
                new KeyboardMidi(PROGRAM | 9, 0, 0), new KeyboardMidi(ON | 9, 38, 100)));
        assertTrue(peak(synth, 8) > 300, "An explicit MIDI percussion bank lost its snare");
    }

    @Test void theDefaultPianoGetsProgressivelyLouderAndUsesFineMidiVelocity() throws Exception {
        var bank = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
        for (int key : new int[] {21, 24, 25, 60, 96, 108}) {
            int previous = 0, soft = 0;
            for (int velocity : new int[] {1, 18, 36, 48, 64, 80, 96, 112, 127}) {
                int peak = attack(bank, key, velocity, 0);
                assertTrue(peak > previous && peak > 120, "Velocity " + velocity + " lost dynamics at MIDI note " + key);
                if (velocity == 1) soft = peak;
                previous = peak;
            }
            // The top keys sound far above their cutoff, where a strike's brightness counts for more.
            assertTrue(previous < soft * 16, "The quietest strike nearly disappeared at MIDI note " + key);
        }
        assertTrue(attack(bank, 24, 55, 127) > attack(bank, 24, 55, 0), "CC88 must affect the next strike");

        var keys = new KeyboardKeys();
        keys.apply(new KeyboardMidi(CONTROL | 9, VELOCITY_PREFIX, 103));
        keys.apply(new KeyboardMidi(ON | 9, 24, 40));
        keys.apply(new KeyboardMidi(ON | 9, 25, 40));
        assertEquals(List.of(new KeyboardMidi(CONTROL | 9, VELOCITY_PREFIX, 103), new KeyboardMidi(ON | 9, 24, 40),
                new KeyboardMidi(ON | 9, 25, 40)), keys.snapshot());
    }

    @Test void bundledSoundFontPlaysGeneralMidiAndStereoPercussion() throws Exception {
        var bank = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
        assertSame(SoundFont.bundled().join(), bank.font(), "The PX must share the A-88MKII's copy of the bank");
        assertEquals(128, bank.patches().stream().filter(p -> p.tone().bank() == 0).count());
        assertTrue(bank.patches().stream().anyMatch(p -> p.tone().bank() == KeyboardSettings.DRUM_BANK));
        var synth = new KeyboardSynth(bank, 192);
        var settings = new KeyboardSettings();
        settings.reverb = 0;
        var silence = peak(synth, 4);
        synth.apply(settings, false, List.of(KeyboardMidi.note(60, 100)));
        assertTrue(peak(synth, 12) > silence + 300, "The piano did not synthesize its SoundFont sample");
        settings = new KeyboardSettings();
        settings.main = new KeyboardSettings.Tone(KeyboardSettings.DRUM_BANK, 0);
        synth.apply(settings, true, List.of(KeyboardMidi.note(38, 110)));
        assertTrue(peak(synth, 12) > silence + 300, "Selecting a drum kit produced silence");
    }

    @Test void damperAndSostenutoSurviveALateJoin() {
        var keys = new KeyboardKeys();
        keys.apply(KeyboardMidi.note(60, 110));
        keys.apply(cc(SOSTENUTO, 127));
        keys.apply(KeyboardMidi.note(60, 0));
        keys.apply(cc(SUSTAIN, 105));
        keys.apply(KeyboardMidi.note(67, 98));
        keys.apply(KeyboardMidi.note(64, 82));
        keys.apply(KeyboardMidi.note(64, 0));
        var replay = new KeyboardKeys();
        keys.snapshot().forEach(replay::apply);
        assertEquals(keys.snapshot(), replay.snapshot());
        assertTrue(replay.pressed(67));
        assertFalse(replay.pressed(60));
        assertFalse(replay.pressed(64));
        assertEquals(105, replay.pedalAmount(SUSTAIN));
        assertEquals(127, replay.pedalAmount(SOSTENUTO));
    }

    @Test void theDamperKeepsANoteAudibleAfterNoteOff() throws Exception {
        var bank = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
        var settings = new KeyboardSettings();
        settings.main = new KeyboardSettings.Tone(0, 16);
        settings.reverb = 0;
        var synth = new KeyboardSynth(bank, 192);
        synth.apply(settings, false, List.of(cc(SUSTAIN, 127), KeyboardMidi.note(60, 105)));
        assertTrue(peak(synth, 15) > 300);
        synth.apply(settings, false, List.of(KeyboardMidi.note(60, 0)));
        peak(synth, 70);
        assertTrue(peak(synth, 3) > 300, "A note stopped while the damper was held");
        synth.apply(settings, false, List.of(cc(SUSTAIN, 0)));
        peak(synth, 90);
        assertTrue(peak(synth, 3) < 100, "The damper release did not end the note");
    }

    @Test void aSoundFontInTheConfigDirectoryCanBeSelectedAndPlayed() throws Exception {
        var directory = Files.createDirectories(KeyboardSoundbank.directory());
        var custom = Files.createTempFile(directory, "keyboard-test-", ".sf3");
        try (var samples = getClass().getResourceAsStream("/assets/create_media_systems/keyboard/a88mkii/generaluser_gs.sf3")) {
            Files.copy(samples, custom, StandardCopyOption.REPLACE_EXISTING);
            String name = custom.getFileName().toString();
            assertTrue(KeyboardSoundbank.choices().contains(name));
            var bank = KeyboardSoundbank.bank(name).join();
            assertEquals(name, bank.name());
            var synth = new KeyboardSynth(bank, 192);
            synth.apply(new KeyboardSettings(), false, List.of(KeyboardMidi.note(65, 100)));
            assertTrue(peak(synth, 12) > 300);
        } finally {
            Files.deleteIfExists(custom);
        }
    }

    @Test void aSoundFontBorrowsTheTonesItLacksFromTheBundledOne() throws Exception {
        var custom = Files.createTempFile(Files.createDirectories(KeyboardSoundbank.directory()), "keyboard-test-", ".sf3");
        try (var samples = getClass().getResourceAsStream("/assets/create_media_systems/keyboard/a88mkii/generaluser_gs.sf3")) {
            byte[] bytes = samples.readAllBytes();
            var file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            // Moves every preset but each bank's first program to bank 5.
            int phdr = new String(bytes, StandardCharsets.ISO_8859_1).lastIndexOf("phdr");
            for (int at = phdr + 8; at + 38 < phdr + 8 + file.getInt(phdr + 4); at += 38) {
                if (file.getShort(at + 20) != 0) file.putShort(at + 22, (short) 5);
            }
            Files.write(custom, bytes);
            var bank = KeyboardSoundbank.bank(custom.getFileName().toString()).join();
            assertNull(bank.font().name(0, 48));
            var bundled = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
            var settings = new KeyboardSettings();
            settings.main = new KeyboardSettings.Tone(0, 48);
            byte[] borrowed = audio(bank, settings);
            assertTrue(peak(borrowed) > 300, "A tone the SoundFont lacks was silent");
            assertArrayEquals(audio(bundled, settings), borrowed, "A tone the SoundFont lacks must come from the bundled one");
            settings.main = new KeyboardSettings.Tone(0, 0);
            assertFalse(Arrays.equals(audio(bundled, settings), audio(bank, settings)), "The SoundFont's own piano must play");
        } finally {
            Files.deleteIfExists(custom);
        }
    }

    private static byte[] audio(KeyboardSoundbank.Bank bank, KeyboardSettings settings) {
        var synth = new KeyboardSynth(bank, 192);
        synth.apply(settings, true, List.of(KeyboardMidi.note(60, 100)));
        byte[] audio = new byte[KeyboardSynth.RATE / 5 * 4];
        synth.read(audio);
        return audio;
    }

    private static int peak(byte[] audio) {
        int peak = 0;
        for (int at = 0; at < audio.length; at += 2) peak = Math.max(peak, Math.abs((short) ((audio[at] & 255) | audio[at + 1] << 8)));
        return peak;
    }

    private static int peak(KeyboardSynth synth, int blocks) throws Exception {
        byte[] audio = new byte[KeyboardSynth.RATE / 50 * 4];
        int peak = 0;
        for (int block = 0; block < blocks; block++) {
            synth.read(audio);
            for (int at = 0; at < audio.length; at += 2) {
                peak = Math.max(peak, Math.abs((short) ((audio[at] & 255) | audio[at + 1] << 8)));
            }
        }
        return peak;
    }

    private static int attack(KeyboardSoundbank.Bank bank, int note, int velocity, int fineVelocity) throws Exception {
        var settings = new KeyboardSettings();
        settings.reverb = 0;
        var synth = new KeyboardSynth(bank, 192);
        synth.apply(settings, false, List.of(cc(VELOCITY_PREFIX, fineVelocity), KeyboardMidi.note(note, velocity)));
        return peak(synth, 8);
    }
}
