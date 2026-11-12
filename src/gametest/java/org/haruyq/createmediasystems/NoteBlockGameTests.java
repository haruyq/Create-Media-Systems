package org.haruyq.createmediasystems;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class NoteBlockGameTests {
    @GameTest(template = "empty")
    public static void copperNotesKeepTheirPitchAndHeadPriority(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        BlockPos absolute = helper.absolutePos(pos);
        List<PlayLevelSoundEvent.AtPosition> sounds = new ArrayList<>();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = event -> {
            if (event.getLevel() == helper.getLevel() && event.getPosition().equals(Vec3.atCenterOf(absolute))) sounds.add(event);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            helper.setBlock(pos, Blocks.NOTE_BLOCK);
            Block[] copper = {Blocks.COPPER_BLOCK, Blocks.EXPOSED_CUT_COPPER_STAIRS,
                    Blocks.WAXED_WEATHERED_CHISELED_COPPER, Blocks.OXIDIZED_CUT_COPPER_SLAB};
            String[] names = {"trumpet", "trumpet_exposed", "trumpet_weathered", "trumpet_oxidized"};
            for (int i = 0; i < copper.length; i++) {
                helper.setBlock(pos.below(), copper[i]);
                for (int note : new int[] {0, 12, 24}) {
                    sounds.clear();
                    BlockState state = helper.getBlockState(pos).setValue(NoteBlock.NOTE, note);
                    helper.assertTrue(state.triggerEvent(helper.getLevel(), absolute, 0, 0), "Note event was rejected");
                    helper.assertTrue(sounds.size() == 1, "Expected one note sound");
                    PlayLevelSoundEvent.AtPosition sound = sounds.getFirst();
                    helper.assertTrue(sound.getSound().value().getLocation().equals(
                            CreateMediaSystems.id("block.note_block." + names[i])), "Wrong copper instrument");
                    helper.assertTrue(sound.getOriginalPitch() == NoteBlock.getPitchFromNote(note), "Note pitch changed");
                    helper.assertTrue(sound.getOriginalVolume() == 3 && sound.getSource() == SoundSource.RECORDS,
                            "Note volume or category changed");
                }
            }
            helper.setBlock(pos.above(), Blocks.ZOMBIE_HEAD);
            assertSound(helper, pos, sounds, SoundEvents.NOTE_BLOCK_IMITATE_ZOMBIE.value().getLocation());
            helper.setBlock(pos.above(), Blocks.AIR);
            helper.setBlock(pos.below(), Blocks.DIRT);
            assertSound(helper, pos, sounds, SoundEvents.NOTE_BLOCK_HARP.value().getLocation());
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lockedTuningYieldsOnlyToWhoLockedIt(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, Blocks.NOTE_BLOCK);
        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        Player other = helper.makeMockPlayer(GameType.SURVIVAL);

        helper.useBlock(pos, other);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HONEYCOMB));
        helper.useBlock(pos, owner);
        helper.assertTrue(owner.getMainHandItem().isEmpty(), "Locking did not use up the honeycomb");
        helper.useBlock(pos, owner);
        other.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        helper.useBlock(pos, other);
        helper.useBlock(pos, other);
        helper.assertBlockProperty(pos, NoteBlock.NOTE, 1);

        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        helper.useBlock(pos, owner);
        helper.assertBlockProperty(pos, NoteBlock.NOTE, 1);
        helper.useBlock(pos, other);
        helper.assertBlockProperty(pos, NoteBlock.NOTE, 2);

        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HONEYCOMB));
        helper.useBlock(pos, owner);
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, Blocks.NOTE_BLOCK);
        helper.useBlock(pos, other);
        helper.assertBlockProperty(pos, NoteBlock.NOTE, 1);
        helper.succeed();
    }

    private static void assertSound(GameTestHelper helper, BlockPos pos, List<PlayLevelSoundEvent.AtPosition> sounds,
                                    ResourceLocation expected) {
        sounds.clear();
        helper.getBlockState(pos).triggerEvent(helper.getLevel(), helper.absolutePos(pos), 0, 0);
        helper.assertTrue(sounds.size() == 1 && sounds.getFirst().getSound().value().getLocation().equals(expected),
                "Expected " + expected);
    }
}
