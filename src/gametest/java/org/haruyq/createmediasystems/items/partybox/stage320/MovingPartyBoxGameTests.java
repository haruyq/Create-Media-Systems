package org.haruyq.createmediasystems.items.partybox.stage320;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MovingPartyBoxGameTests {
    @GameTest(batch = "launcher", template = "empty")
    public static void trolleyPlacesOnAnAssembledDeckAndMovesWithIt(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos center = h.absolutePos(new BlockPos(4, 3, 4));
        level.setBlockAndUpdate(center.below(), AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(center.below());
        for (var pos : BlockPos.betweenClosed(center.north(), center.east(2).south())) {
            level.setBlockAndUpdate(pos, Blocks.IRON_BLOCK.defaultBlockState());
        }
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(center.north()).minmax(new AABB(center.east(2).south()))));
        level.setBlockAndUpdate(center.below(2), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        h.runAfterDelay(5, () -> placeOnRunningDeck(h, bearing));
    }

    private static void placeOnRunningDeck(GameTestHelper h, MechanicalBearingBlockEntity bearing) {
        var level = h.getLevel();
        var carrier = bearing.getMovedContraption();
        h.assertTrue(carrier != null && carrier.isAlive() && bearing.getSpeed() != 0, "The powered bearing assembled a deck");
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 localTop = new Vec3(1.5, 1, .5);
        Vec3 top = carrier.toGlobalVector(localTop, 1);
        player.setPos(top.add(0, .35, 0));
        player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PartyBoxFeature.PARTYBOX.get()));
        var result = new PartyBoxPlacement(carrier.getId(), false).place(player);
        h.assertTrue(result.consumesAction() && player.getMainHandItem().isEmpty(), "The server accepts the clicked floor");
        var boxes = level.getEntitiesOfClass(PartyBoxEntity.class, new AABB(top, top).inflate(.5));
        h.assertTrue(boxes.size() == 1, "The trolley spawns on the actual deck face");
        var box = boxes.getFirst();
        h.runAfterDelay(18, () -> {
            var expected = carrier.toGlobalVector(localTop, 1);
            h.assertTrue(carrier.isAlive() && expected.distanceTo(top) > .5 && box.position().distanceTo(expected) < .5,
                    "The trolley moves with the deck from " + top + " toward " + expected + ", current " + box.position());
            bearing.disassemble();
            h.succeed();
        });
    }
}
