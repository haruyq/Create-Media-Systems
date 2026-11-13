package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings.Tone;

final class KeyboardToneHistory {
    private static final Gson JSON = new Gson();
    private record Saved(List<String> favorites, List<String> recent) {}
    private static final KeyboardToneHistory INSTANCE = new KeyboardToneHistory();

    private final List<String> favorites = new ArrayList<>(), recent = new ArrayList<>();

    private KeyboardToneHistory() {
        var file = ClientFiles.DIR.resolve("keyboard-tones.json");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 65536) return;
            try (var reader = Files.newBufferedReader(file)) {
                var saved = JSON.fromJson(reader, Saved.class);
                if (saved != null) {
                    if (saved.favorites != null) saved.favorites.stream().limit(200).forEach(favorites::add);
                    if (saved.recent != null) saved.recent.stream().limit(32).forEach(recent::add);
                }
            }
        } catch (IOException | RuntimeException error) {
            LogUtils.getLogger().warn("Could not read keyboard tone history", error);
        }
    }

    static KeyboardToneHistory get() { return INSTANCE; }

    boolean favorite(String bank, Tone tone) { return favorites.contains(key(bank, tone)); }
    int recent(String bank, Tone tone) { return recent.indexOf(key(bank, tone)); }

    void toggle(String bank, Tone tone) {
        var key = key(bank, tone);
        if (!favorites.remove(key) && favorites.size() < 200) favorites.add(key);
        save();
    }

    void used(String bank, Tone tone) {
        var key = key(bank, tone);
        recent.remove(key);
        recent.addFirst(key);
        if (recent.size() > 32) recent.removeLast();
        save();
    }

    private static String key(String bank, Tone tone) { return bank + "|" + tone.bank() + "|" + tone.program(); }

    private void save() {
        try {
            Files.createDirectories(ClientFiles.DIR);
            try (var writer = Files.newBufferedWriter(ClientFiles.DIR.resolve("keyboard-tones.json"))) {
                JSON.toJson(new Saved(favorites, recent), writer);
            }
        } catch (IOException error) {
            LogUtils.getLogger().warn("Could not save keyboard tone history", error);
        }
    }
}
