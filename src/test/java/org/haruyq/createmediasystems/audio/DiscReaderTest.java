package org.haruyq.createmediasystems.audio;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

class DiscReaderTest {
    @Test
    void redstoneStartsAndStopsTheDisc() {
        DiscReaderBlock.Entity reader = new DiscReaderBlock.Entity(BlockPos.ZERO,
                AudioFeature.DISC_READER.get().defaultBlockState());
        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), "https://example.com/a.mp3");
        reader.insert(disc, null);
        assertNotNull(reader.nowPlaying(), "an inserted disc plays");

        reader.power(true);
        assertNotNull(reader.nowPlaying(), "a signal leaves a playing disc playing");
        reader.power(false);
        assertNull(reader.nowPlaying(), "losing the signal stops it");
        reader.power(false);
        assertNull(reader.nowPlaying());
        reader.power(true);
        assertNotNull(reader.nowPlaying(), "a new signal plays it again");
    }
}
