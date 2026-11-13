package org.haruyq.createmediasystems.blocks.audio;


import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.DataMapHooks;

public final class NoteBlockSounds {
    private NoteBlockSounds() {}

    public static Holder<SoundEvent> sound(Holder<SoundEvent> original, BlockState below) {
        if (original != SoundEvents.NOTE_BLOCK_HARP) return original;
        Block block = below.getBlock();
        Block unwaxed = DataMapHooks.getBlockUnwaxed(block);
        if (unwaxed != null) block = unwaxed;
        if (!(block instanceof WeatheringCopper copper)) return original;
        Block first = WeatheringCopper.getFirst(block);
        if (first != Blocks.COPPER_BLOCK && first != Blocks.CUT_COPPER && first != Blocks.CHISELED_COPPER
                && first != Blocks.CUT_COPPER_STAIRS && first != Blocks.CUT_COPPER_SLAB) return original;
        return switch (copper.getAge()) {
            case UNAFFECTED -> AudioFeature.NOTE_BLOCK_TRUMPET;
            case EXPOSED -> AudioFeature.NOTE_BLOCK_TRUMPET_EXPOSED;
            case WEATHERED -> AudioFeature.NOTE_BLOCK_TRUMPET_WEATHERED;
            case OXIDIZED -> AudioFeature.NOTE_BLOCK_TRUMPET_OXIDIZED;
        };
    }
}
