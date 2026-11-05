package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

class JukeboxSettingsTest {
    private static final Vec3 JUKEBOX = new Vec3(0.5, 0.5, 0.5);

    @Test
    void defaultRangeIsVanillaDiscReach() {
        JukeboxSettings settings = JukeboxSettings.DEFAULT;
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(60, 0.5, 0.5), 0));
        assertFalse(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(70, 0.5, 0.5), 0));
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(70, 0.5, 0.5), JukeboxSettings.DROP_MARGIN));
        assertEquals(1, settings.gain(BlockPos.ZERO, JUKEBOX, JUKEBOX), 1e-6);
        assertEquals(0.5, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(32.5, 0.5, 0.5)), 1e-6);
        assertEquals(0, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(100, 0.5, 0.5)), 1e-6);
    }

    @Test
    void areaBoundsHearingAndFalloffReachesItsFarthestCorner() {
        // Blocks 0..9 on x, 0..0 on y and z: the farthest corner is (10, 1, 1).
        JukeboxSettings settings = new JukeboxSettings(Optional.of(new BoundingBox(0, 0, 0, 9, 0, 0)), 0.5f);
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(9.9, 0.5, 0.5), 0));
        assertFalse(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(0.5, 3, 0.5), 0));
        assertEquals(0.5, settings.gain(BlockPos.ZERO, JUKEBOX, JUKEBOX), 1e-6, "full volume at the jukebox");
        float halfway = settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(5.5, 0.5, 0.5));
        float near = settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(9.5, 0.5, 0.5));
        assertTrue(0.5 > halfway && halfway > near && near > 0, "quieter further out, still heard at the edge");
        assertEquals(0, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(10, 1, 1)), 1e-6, "silent at the farthest corner");
    }

    @Test
    void areaGoesWhereverTheJukeboxIs() {
        // Saved as one block east of the jukebox; the jukebox has since moved (onto a ship's plot, say).
        JukeboxSettings settings = new JukeboxSettings(Optional.of(new BoundingBox(1, 0, 0, 1, 0, 0)), 1);
        BlockPos moved = new BlockPos(1000, 64, -2000);
        Vec3 center = moved.getCenter();
        assertTrue(settings.inRange(moved, center, center.add(1, 0, 0), 0));
        assertFalse(settings.inRange(moved, center, new Vec3(1.5, 0.5, 0.5), 0));
    }

    @Test
    void oldSavesWithTheAreaInPlaceAreMadeRelative() {
        BlockPos at = new BlockPos(100, 64, 100);
        CompoundTag saved = new CompoundTag();
        saved.put("area", new IntArrayTag(new int[] {100, 60, 90, 110, 70, 110}));
        saved.putFloat("volume", 0.5f);
        JukeboxSettings settings = JukeboxSettings.SERIALIZER.read(
                new JukeboxBlockEntity(at, Blocks.JUKEBOX.defaultBlockState()), saved, RegistryAccess.EMPTY);
        assertEquals(Optional.of(new BoundingBox(0, -4, -10, 10, 6, 10)), settings.area());
        assertEquals(0.5f, settings.volume());
        assertEquals(settings, JukeboxSettings.SERIALIZER.read(null,
                JukeboxSettings.SERIALIZER.write(settings, RegistryAccess.EMPTY), RegistryAccess.EMPTY));
    }

    @Test
    void volumeIsClamped() {
        assertEquals(1, new JukeboxSettings(Optional.empty(), 3).volume());
        assertEquals(0, new JukeboxSettings(Optional.empty(), -1).volume());
    }
}
