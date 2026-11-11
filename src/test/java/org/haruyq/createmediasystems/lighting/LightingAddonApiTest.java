package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec3;

class LightingAddonApiTest {
    @Test
    void aClipKeepsItsSourcesSide() {
        Vec3 source = new Vec3(0, 10, 0);
        // A floor under the source, its normal given pointing down: turned to face the source.
        LightingBeams.Clip floor = LightingBeams.Clip.toward(Vec3.ZERO, new Vec3(0, -1, 0), source);
        assertTrue(floor.normal().distanceTo(new Vec3(0, 1, 0)) < 1e-9);
        assertTrue(floor.distance(source) > 0);
        assertTrue(floor.distance(new Vec3(0, -1, 0)) < 0);
    }

    @Test
    void familiesAreNamedForPlayers() throws IOException {
        String key = ((TranslatableContents) StageLightBlock.familyName(StageLightBlock.Entity.DEFAULT_FAMILY)
                .getContents()).getKey();
        for (String language : new String[] {"en_us", "ja_jp"}) {
            try (var in = LightingAddonApiTest.class.getResourceAsStream("/assets/create_media_systems/lang/" + language + ".json")) {
                assertTrue(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().has(key),
                        language + " names the mod's own family");
            }
        }
        assertEquals("some_mod", StageLightBlock.familyName("some_mod").getString());
        StageLightBlock.nameFamily("some_mod", Component.literal("Some lamps"));
        assertEquals("Some lamps", StageLightBlock.familyName("some_mod").getString());
    }
}
