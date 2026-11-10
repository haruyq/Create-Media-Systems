package org.haruyq.createmediasystems.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

class NoteBlockSoundsTest {
    @Test
    void eachCopperShapeAndWaxedVariantUsesItsOxidationSound() {
        for (String age : new String[] {"", "exposed_", "weathered_", "oxidized_"}) {
            String sound = "trumpet" + (age.isEmpty() ? "" : "_" + age.substring(0, age.length() - 1));
            for (String shape : new String[] {"copper", "cut_copper", "chiseled_copper", "cut_copper_stairs", "cut_copper_slab"}) {
                for (String wax : new String[] {"", "waxed_"}) {
                    String name = wax + age + shape + (age.isEmpty() && shape.equals("copper") ? "_block" : "");
                    Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(name));
                    assertEquals(NoteBlockInstrument.HARP, block.defaultBlockState().instrument(), name);
                    assertEquals(ResourceLocation.fromNamespaceAndPath("create_media_systems", "block.note_block." + sound),
                            NoteBlockSounds.sound(SoundEvents.NOTE_BLOCK_HARP, block.defaultBlockState()).value().getLocation(), name);
                }
            }
        }
    }

    @Test
    void otherBlocksAndInstrumentsKeepTheirSounds() {
        for (Block block : new Block[] {Blocks.STONE, Blocks.OAK_PLANKS, Blocks.AIR, Blocks.COPPER_ORE,
                Blocks.COPPER_GRATE, Blocks.OXIDIZED_COPPER_BULB, Blocks.WAXED_COPPER_DOOR, Blocks.COPPER_TRAPDOOR}) {
            assertSame(SoundEvents.NOTE_BLOCK_HARP, NoteBlockSounds.sound(SoundEvents.NOTE_BLOCK_HARP, block.defaultBlockState()));
        }
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            if (instrument == NoteBlockInstrument.HARP) continue;
            assertSame(instrument.getSoundEvent(), NoteBlockSounds.sound(instrument.getSoundEvent(), Blocks.COPPER_BLOCK.defaultBlockState()));
        }
    }
}
