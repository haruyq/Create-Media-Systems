package org.haruyq.createmediasystems.keyboard.client;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.haruyq.createmediasystems.keyboard.KeyboardKeys;
import org.haruyq.createmediasystems.keyboard.KeyboardMidi;
import org.haruyq.createmediasystems.keyboard.KeyboardSettings;
import org.junit.jupiter.api.Test;

import static org.haruyq.createmediasystems.keyboard.KeyboardMidi.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardSynthTest {
    @Test void bundledSoundFontPlaysGeneralMidiAndStereoPercussion() throws Exception {
        var bank = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join();
        assertEquals(128, bank.patches().stream().filter(p -> p.tone().bank() == 0).count());
        assertTrue(bank.patches().stream().anyMatch(p -> p.tone().bank() == KeyboardSettings.DRUM_BANK));
        try (var synth = new KeyboardSynth(bank, 192)) {
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
        try (var synth = new KeyboardSynth(bank, 192)) {
            synth.apply(settings, false, List.of(cc(SUSTAIN, 127), KeyboardMidi.note(60, 105)));
            assertTrue(peak(synth, 15) > 300);
            synth.apply(settings, false, List.of(KeyboardMidi.note(60, 0)));
            peak(synth, 70);
            assertTrue(peak(synth, 3) > 300, "A note stopped while the damper was held");
            synth.apply(settings, false, List.of(cc(SUSTAIN, 0)));
            peak(synth, 90);
            assertTrue(peak(synth, 3) < 100, "The damper release did not end the note");
        }
    }

    @Test void aSoundFontInTheConfigDirectoryCanBeSelectedAndPlayed() throws Exception {
        var directory = Files.createDirectories(KeyboardSoundbank.directory());
        var custom = Files.createTempFile(directory, "keyboard-test-", ".sf2");
        try (var samples = getClass().getResourceAsStream("/assets/create_media_systems/px_s1100/GeneralUser-GS.sf2")) {
            Files.copy(samples, custom, StandardCopyOption.REPLACE_EXISTING);
            String name = custom.getFileName().toString();
            assertTrue(KeyboardSoundbank.choices().contains(name));
            var bank = KeyboardSoundbank.bank(name).join();
            assertEquals(name, bank.name());
            try (var synth = new KeyboardSynth(bank, 192)) {
                synth.apply(new KeyboardSettings(), false, List.of(KeyboardMidi.note(65, 100)));
                assertTrue(peak(synth, 12) > 300);
            }
        } finally {
            Files.deleteIfExists(custom);
        }
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
}
