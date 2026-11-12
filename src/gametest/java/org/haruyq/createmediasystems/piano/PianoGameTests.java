package org.haruyq.createmediasystems.piano;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class PianoGameTests {
    @GameTest(template = "empty")
    public static void grandPianoPlacesAndDropsAsOneInstrument(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(2, 2, 2));
        var item = PianoFeature.PIANO_ITEM.get();
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(90);
        player.setPos(root.getX() + 2.5, root.getY(), root.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        Direction facing = player.getDirection().getOpposite();
        for (var part : GrandPianoBlock.Part.values()) {
            level.setBlockAndUpdate(part.at(root, facing).below(), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        var hit = new BlockHitResult(Vec3.atCenterOf(root.below()).add(0, .5, 0), Direction.UP, root.below(), false);
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        var tail = GrandPianoBlock.Part.TAIL.at(root, facing);
        level.setBlockAndUpdate(tail, Blocks.STONE.defaultBlockState());
        helper.assertFalse(item.place(context).consumesAction(), "The occupied tail should prevent placement");
        helper.assertTrue(level.getBlockState(root).isAir() && player.getMainHandItem().is(item),
                "An obstructed placement changed the root or the item");
        level.removeBlock(tail, false);
        helper.assertTrue(item.place(context).consumesAction() && player.getMainHandItem().isEmpty(),
                "The item did not place the full piano");
        for (var part : GrandPianoBlock.Part.values()) {
            var pos = part.at(root, facing);
            var state = level.getBlockState(pos);
            helper.assertTrue(state.is(PianoFeature.PIANO.get()) && GrandPianoBlock.root(pos, state).equals(root),
                    "Missing or incorrectly rotated piano part " + part);
            helper.assertTrue((level.getBlockEntity(pos) instanceof PianoBlockEntity) == (part == GrandPianoBlock.Part.KEYBOARD),
                    "Unexpected block entity on " + part);
        }
        var piano = (PianoBlockEntity) level.getBlockEntity(root);
        piano.toggleLid();
        helper.assertTrue(level.getBlockState(root).getValue(GrandPianoBlock.OPEN) && level.getBlockEntity(root) == piano,
                "Toggling the lid did not keep the piano");
        level.destroyBlock(tail, true, player);
        for (var part : GrandPianoBlock.Part.values()) {
            helper.assertTrue(level.getBlockState(part.at(root, facing)).isAir(), "Orphaned piano part " + part);
        }
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(4),
                entity -> entity.getItem().is(item));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "Expected exactly one piano drop");
        helper.assertTrue(level.getRecipeManager().byKey(CreateMediaSystems.id("grand_piano"))
                .filter(recipe -> recipe.value().getResultItem(level.registryAccess()).is(item)).isPresent(),
                "The shaped recipe is missing");
        helper.succeed();
    }
}
