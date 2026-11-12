package org.haruyq.createmediasystems.pxs1100.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javax.sound.midi.Instrument;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.pxs1100.KeyboardSettings;
import org.haruyq.createmediasystems.pxs1100.KeyboardSettings.Tone;
import org.haruyq.gervill.ModelPatch;
import org.haruyq.gervill.SF2Soundbank;

final class KeyboardSoundbank {
    private static final int MAX_BYTES = 192 * 1024 * 1024;
    private static final String RESOURCE = "/assets/" + CreateMediaSystems.MOD_ID + "/px_s1100/GeneralUser-GS.sf2";
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("CMS keyboard synth").daemon().unstarted(task));
    private static final CompletableFuture<Bank> DEFAULT = CompletableFuture.supplyAsync(KeyboardSoundbank::bundled, IO);
    private static String lastCustom = "";
    private static CompletableFuture<Bank> custom;

    record Patch(Tone tone, String name) {
        int category() { return tone.bank() == KeyboardSettings.DRUM_BANK ? 16 : tone.program() / 8; }
    }

    record Bank(String name, Map<Tone, Instrument> instruments, List<Patch> patches) {
        String title(Tone tone) {
            return patches.stream().filter(patch -> patch.tone.equals(tone)).findFirst()
                    .map(Patch::name).orElse(tone.bank() + ":" + tone.program());
        }
    }

    private KeyboardSoundbank() {}

    static CompletableFuture<KeyboardSynth> synth(String name, int polyphony) {
        return bank(name).thenApplyAsync(bank -> new KeyboardSynth(bank, polyphony), IO);
    }

    static synchronized CompletableFuture<Bank> bank(String name) {
        if (KeyboardSettings.DEFAULT_FONT.equals(name)) return DEFAULT;
        if (!lastCustom.equals(name)) {
            lastCustom = name;
            custom = CompletableFuture.supplyAsync(() -> {
                try {
                    String safe = KeyboardSettings.fontName(name);
                    if (!safe.equals(name) || !safe.toLowerCase(Locale.ROOT).endsWith(".sf2")) return DEFAULT.join();
                    var path = directory().resolve(safe);
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) return DEFAULT.join();
                    return bank(name, new SF2Soundbank(path.toFile()));
                } catch (IOException | RuntimeException e) {
                    LogUtils.getLogger().warn("Could not load keyboard SoundFont {}", name, e);
                    return DEFAULT.join();
                }
            }, IO);
        }
        return custom;
    }

    static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(CreateMediaSystems.MOD_ID).resolve("soundfonts");
    }

    static List<String> choices() {
        var result = new ArrayList<String>();
        result.add(KeyboardSettings.DEFAULT_FONT);
        try {
            Files.createDirectories(directory());
            try (var files = Files.list(directory())) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".sf2") && KeyboardSettings.fontName(name).equals(name))
                        .sorted(String.CASE_INSENSITIVE_ORDER).forEach(result::add);
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Could not list keyboard SoundFonts", e);
        }
        return List.copyOf(result);
    }

    private static Bank bundled() {
        try (var stream = KeyboardSoundbank.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing bundled GeneralUser GS SoundFont");
            return bank(KeyboardSettings.DEFAULT_FONT, new SF2Soundbank(stream));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load bundled keyboard SoundFont", e);
        }
    }

    static Bank bank(String name, SF2Soundbank soundfont) {
        var patches = new LinkedHashMap<Tone, Patch>();
        var instruments = new LinkedHashMap<Tone, Instrument>();
        Arrays.stream(soundfont.getInstruments()).forEach(instrument -> {
            var patch = instrument.getPatch();
            int bank = patch instanceof ModelPatch model && model.isPercussion() ? KeyboardSettings.DRUM_BANK : patch.getBank();
            var tone = new Tone(bank, patch.getProgram());
            patches.putIfAbsent(tone, new Patch(tone, instrument.getName().strip()));
            instruments.putIfAbsent(tone, instrument);
        });
        if (patches.isEmpty()) throw new IllegalArgumentException("No SoundFont instruments in " + name);
        return new Bank(name, Map.copyOf(instruments), patches.values().stream()
                .sorted(Comparator.comparingInt(Patch::category).thenComparingInt(patch -> patch.tone.program())
                        .thenComparingInt(patch -> patch.tone.bank())).toList());
    }
}
