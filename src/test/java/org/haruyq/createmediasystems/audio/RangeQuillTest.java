package org.haruyq.createmediasystems.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.haruyq.createmediasystems.stream.EmitterRange;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class RangeQuillTest {
    @Test
    void oldSavesWithTheAreaInPlaceAreMadeRelative() {
        BlockPos at = new BlockPos(100, 64, 100);
        CompoundTag saved = new CompoundTag();
        saved.put("area", new IntArrayTag(new int[] {100, 60, 90, 110, 70, 110}));
        saved.putFloat("volume", 0.5f);
        EmitterRange settings = RangeQuill.JUKEBOX_SERIALIZER.read(
                new JukeboxBlockEntity(at, Blocks.JUKEBOX.defaultBlockState()), saved, RegistryAccess.EMPTY);
        assertEquals(Optional.of(new BoundingBox(0, -4, -10, 10, 6, 10)), settings.area());
        assertEquals(0.5f, settings.volume());
        assertEquals(settings, RangeQuill.JUKEBOX_SERIALIZER.read(null,
                RangeQuill.JUKEBOX_SERIALIZER.write(settings, RegistryAccess.EMPTY), RegistryAccess.EMPTY));
    }
}
