package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec3;

class LightingAddonApiTest {
    @Test
    void fadesAreTheBeamShadersModes() {
        // stage_beam.vsh reads a strip's mode as 0 (cone), 1 (sheet), 2 (ray).
        assertEquals(0, LightingBeams.Fade.CONE.ordinal());
        assertEquals(1, LightingBeams.Fade.SHEET.ordinal());
        assertEquals(2, LightingBeams.Fade.SOLID.ordinal());
    }

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
    void familiesAreNamedForPlayers() {
        String key = ((TranslatableContents) StageLightBlock.familyName(StageLightBlock.Entity.DEFAULT_FAMILY)
                .getContents()).getKey();
        assertEquals("create_media_systems.lighting.family", key);
        assertEquals("some_mod", StageLightBlock.familyName("some_mod").getString());
        StageLightBlock.nameFamily("some_mod", Component.literal("Some lamps"));
        assertEquals("Some lamps", StageLightBlock.familyName("some_mod").getString());
    }
}
