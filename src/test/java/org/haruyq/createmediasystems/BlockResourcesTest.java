package org.haruyq.createmediasystems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Every block's resources and tags against one table: what mines it and whether it drops itself. What else a block
 * needs follows from the registry: a blockstate whose models exist, and, with an item, an item model and its name in
 * both languages. The Sable silent-removal tag lists exactly the blocks that are a SableCompat.SilentAssemblyRemoval.
 * A new block fails here until it has a row and its files.
 */
class BlockResourcesTest {
    private enum Tool { PICKAXE, AXE, NONE }

    private record Row(Tool tool, boolean stoneTool, boolean dropsItself) {
    }

    private static final Map<String, Row> BLOCKS = new TreeMap<>(Map.ofEntries(
            Map.entry("blu_ray_player", new Row(Tool.PICKAXE, false, true)),
            Map.entry("broadcast_array", new Row(Tool.PICKAXE, false, true)),
            Map.entry("broadcast_base", new Row(Tool.PICKAXE, false, true)),
            Map.entry("broadcast_digital_horn", new Row(Tool.PICKAXE, false, true)),
            Map.entry("broadcast_horn", new Row(Tool.PICKAXE, false, true)),
            Map.entry("broadcast_mast", new Row(Tool.PICKAXE, false, true)),
            Map.entry("disc_cell_charger", new Row(Tool.PICKAXE, true, true)),
            Map.entry("disc_drive_emulator", new Row(Tool.PICKAXE, false, true)),
            Map.entry("disc_reader", new Row(Tool.AXE, false, true)),
            Map.entry("display", new Row(Tool.PICKAXE, false, true)),
            Map.entry("equalizer", new Row(Tool.PICKAXE, false, true)),
            Map.entry("grand_piano", new Row(Tool.AXE, false, true)),
            Map.entry("hifi_speaker", new Row(Tool.PICKAXE, false, true)),
            // Drops its units as it is removed; it has no item of its own.
            Map.entry("hifi_stack", new Row(Tool.PICKAXE, false, false)),
            Map.entry("image_viewer", new Row(Tool.PICKAXE, false, true)),
            Map.entry("laser_projector", new Row(Tool.PICKAXE, false, true)),
            Map.entry("lighting_console", new Row(Tool.PICKAXE, false, true)),
            Map.entry("lighting_monitor", new Row(Tool.PICKAXE, false, true)),
            Map.entry("midi_keyboard", new Row(Tool.PICKAXE, false, true)),
            Map.entry("px_s1100", new Row(Tool.PICKAXE, false, true)),
            // Legacy and invisible: old saves only clear it away.
            Map.entry("reload_glow", new Row(Tool.NONE, false, false)),
            Map.entry("speaker", new Row(Tool.AXE, false, true)),
            Map.entry("stage_light", new Row(Tool.PICKAXE, false, true)),
            Map.entry("strip_light", new Row(Tool.PICKAXE, false, true)),
            Map.entry("train_detector", new Row(Tool.PICKAXE, false, true)),
            Map.entry("train_disc_reader", new Row(Tool.AXE, false, true)),
            Map.entry("train_signal_receiver", new Row(Tool.PICKAXE, false, true)),
            Map.entry("train_speaker", new Row(Tool.AXE, false, true))));

    private static final String NS = CreateMediaSystems.MOD_ID;
    /** The mod's resources as built (tests don't run in the project folder). */
    private static final Path RESOURCES = resources();

    private static Path resources() {
        try {
            // assets/<mod>/lang/en_us.json, four folders down.
            return Path.of(BlockResourcesTest.class.getResource("/assets/" + NS + "/lang/en_us.json").toURI())
                    .getParent().getParent().getParent().getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Block> registered() {
        return CreateMediaSystems.BLOCKS.getEntries().stream()
                .collect(Collectors.toMap(entry -> entry.getId().getPath(), DeferredHolder::get, (a, b) -> a, TreeMap::new));
    }

    @Test
    void everyRegisteredBlockHasARow() {
        assertEquals(BLOCKS.keySet(), registered().keySet());
    }

    @Test
    void blockstatesNameModelsThatExist() throws IOException {
        for (String id : registered().keySet()) {
            Path blockstate = RESOURCES.resolve("assets/" + NS + "/blockstates/" + id + ".json");
            assertTrue(Files.exists(blockstate), "no blockstate for " + id);
            List<String> models = new ArrayList<>();
            strings(json(blockstate), "model", models);
            assertTrue(!models.isEmpty(), id + "'s blockstate names no model");
            for (String model : models) {
                assertTrue(model.startsWith(NS + ":"), id + " names another mod's model " + model);
                assertTrue(Files.exists(RESOURCES.resolve("assets/" + NS + "/models/" + model.substring(NS.length() + 1)
                        + ".json")), id + "'s blockstate names a missing model " + model);
            }
        }
        assertEquals(registered().keySet(), names(RESOURCES.resolve("assets/" + NS + "/blockstates")),
                "a blockstate for a block that isn't registered");
    }

    @Test
    void blocksWithAnItemHaveItsModelAndName() throws IOException {
        Set<String> named = new TreeSet<>();
        for (var entry : registered().entrySet()) {
            if (entry.getValue().asItem() == Items.AIR) {
                continue;
            }
            named.add(entry.getKey());
            assertTrue(Files.exists(RESOURCES.resolve("assets/" + NS + "/models/item/" + entry.getKey() + ".json")),
                    "no item model for " + entry.getKey());
        }
        for (String language : List.of("en_us", "ja_jp")) {
            Set<String> keys = json(RESOURCES.resolve("assets/" + NS + "/lang/" + language + ".json")).getAsJsonObject()
                    .keySet().stream().filter(key -> key.startsWith("block." + NS + "."))
                    .map(key -> key.substring(("block." + NS + ".").length())).collect(Collectors.toCollection(TreeSet::new));
            assertTrue(keys.containsAll(named), language + " lacks a block's name: " + difference(named, keys));
            assertTrue(registered().keySet().containsAll(keys),
                    language + " names a block that isn't registered: " + difference(keys, registered().keySet()));
        }
    }

    @Test
    void blocksThatDropThemselvesHaveTheirLootTable() throws IOException {
        Set<String> dropping = rows(row -> row.dropsItself);
        for (String id : dropping) {
            List<String> names = new ArrayList<>();
            strings(json(RESOURCES.resolve("data/" + NS + "/loot_table/blocks/" + id + ".json")), "name", names);
            assertEquals(List.of(NS + ":" + id), names, id + "'s loot table");
        }
        assertEquals(dropping, names(RESOURCES.resolve("data/" + NS + "/loot_table/blocks")));
    }

    @Test
    void toolTagsFollowTheTable() throws IOException {
        assertEquals(rows(row -> row.tool == Tool.PICKAXE), tag("data/minecraft/tags/block/mineable/pickaxe.json"));
        assertEquals(rows(row -> row.tool == Tool.AXE), tag("data/minecraft/tags/block/mineable/axe.json"));
        assertEquals(rows(row -> row.stoneTool), tag("data/minecraft/tags/block/needs_stone_tool.json"));
    }

    /** Sable removes these without their onRemove when it assembles a ship (see SilentAssemblyRemoval). */
    @Test
    void sableRemovesSilentlyExactlyTheBlocksThatSaySo() throws IOException {
        Set<String> silent = registered().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof SableCompat.SilentAssemblyRemoval)
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(silent, tag("data/sable/tags/block/silent_assembly_removal.json"));
    }

    private static Set<String> rows(java.util.function.Predicate<Row> which) {
        return BLOCKS.entrySet().stream().filter(entry -> which.test(entry.getValue())).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> tag(String path) throws IOException {
        Set<String> ids = new TreeSet<>();
        for (JsonElement value : json(RESOURCES.resolve(path)).getAsJsonObject().getAsJsonArray("values")) {
            String id = value.getAsString();
            assertTrue(id.startsWith(NS + ":"), path + " lists " + id);
            ids.add(id.substring(NS.length() + 1));
        }
        return ids;
    }

    private static Set<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString().replaceFirst("\\.json$", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static JsonElement json(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader);
        }
    }

    /** Every string under {@code key} anywhere in {@code json}. */
    private static void strings(JsonElement json, String key, List<String> into) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> strings(element, key, into));
        } else if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            for (var entry : object.entrySet()) {
                if (entry.getKey().equals(key) && entry.getValue().isJsonPrimitive()) {
                    into.add(entry.getValue().getAsString());
                } else {
                    strings(entry.getValue(), key, into);
                }
            }
        }
    }
}
