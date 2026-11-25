package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings.Tone;

final class KeyboardSoundbank {
    private static final int MAX_BYTES = 192 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("CMS keyboard synth").daemon().unstarted(task));
    // Not on IO: a custom bank that fails waits there for this one.
    private static final CompletableFuture<Bank> DEFAULT = SoundFont.bundled()
            .thenApply(font -> bank(KeyboardSettings.DEFAULT_FONT, font));
    private static String lastCustom = "";
    private static CompletableFuture<Bank> custom;

    record Patch(Tone tone, String name) {
        int category() { return tone.bank() == KeyboardSettings.DRUM_BANK ? 16 : tone.program() / 8; }
    }

    record Bank(String name, SoundFont font, List<Patch> patches) {
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
                    if (!safe.equals(name) || !soundFont(safe)) return DEFAULT.join();
                    var path = directory().resolve(safe);
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) return DEFAULT.join();
                    return bank(name, SoundFont.read(ByteBuffer.wrap(Files.readAllBytes(path))));
                } catch (IOException | RuntimeException e) {
                    LogUtils.getLogger().warn("Could not load keyboard SoundFont {}", name, e);
                    return DEFAULT.join();
                }
            }, IO);
        }
        return custom;
    }

    /** An .sf2 or .sf3 file's name. */
    private static boolean soundFont(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sf2") || lower.endsWith(".sf3");
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
                        .filter(name -> soundFont(name) && KeyboardSettings.fontName(name).equals(name))
                        .sorted(String.CASE_INSENSITIVE_ORDER).forEach(result::add);
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Could not list keyboard SoundFonts", e);
        }
        return List.copyOf(result);
    }

    static Bank bank(String name, SoundFont font) {
        var patches = new LinkedHashMap<Tone, Patch>();
        // By bank, then program: a bank-1 variation comes before the drum kit of its tone (see KeyboardSynth.bankSelect).
        new TreeMap<>(font.names()).forEach((preset, title) -> {
            int bank = preset >> 8;
            var tone = new Tone(bank == SoundFont.DRUMS ? KeyboardSettings.DRUM_BANK : bank << 7, preset & 255);
            patches.putIfAbsent(tone, new Patch(tone, title));
        });
        if (patches.isEmpty()) throw new IllegalArgumentException("No SoundFont instruments in " + name);
        return new Bank(name, font, patches.values().stream()
                .sorted(Comparator.comparingInt(Patch::category).thenComparingInt(patch -> patch.tone.program())
                        .thenComparingInt(patch -> patch.tone.bank())).toList());
    }
}
