package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;

class DiscCellTierTest {
    @Test void ordinaryProgressionNeverNeedsAnotherAddon() {
        int shots = 0; float damage = 7;
        for (DiscCellTier tier : DiscCellTier.values()) {
            assertTrue(tier.shots > shots && tier.damage > damage);
            if (!tier.optional()) assertTrue(tier.upgradeMaterials().stream()
                    .allMatch(id -> id.startsWith("minecraft:") || id.startsWith("create:")));
            shots = tier.shots; damage = tier.damage;
        }
    }
    @Test void eachUpgradeRecipeIsTheTierItNamesAndOnlyTheOptionalOneNeedsItsMod() throws Exception {
        for (DiscCellTier tier : DiscCellTier.values()) {
            if (tier.previous() == null) continue;
            var stream = getClass().getResourceAsStream("/data/create_media_systems/recipe/" + tier.itemId() + ".json");
            assertNotNull(stream, tier.itemId());
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var recipe = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals("create_media_systems:disc_cell_upgrade", recipe.get("type").getAsString());
                assertEquals(tier.level, recipe.get("level").getAsInt());
                var conditions = recipe.getAsJsonArray("neoforge:conditions");
                assertEquals(tier.optional(), conditions != null, tier.itemId());
                if (conditions != null) {
                    var condition = conditions.get(0).getAsJsonObject();
                    assertEquals("neoforge:mod_loaded", condition.get("type").getAsString());
                    assertEquals(DiscCellTier.PLANT_MOD_ID, condition.get("modid").getAsString());
                }
            }
        }
    }
}
