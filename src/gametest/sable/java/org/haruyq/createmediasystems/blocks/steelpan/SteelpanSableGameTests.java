package org.haruyq.createmediasystems.blocks.steelpan;

import java.util.List;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class SteelpanSableGameTests {
    @GameTest(template = "empty")
    public static void playingUsesTheMovedShipsWorldPosition(GameTestHelper helper) {
        var level = helper.getLevel();
        var initial = helper.absolutePos(new BlockPos(2, 3, 2));
        level.setBlockAndUpdate(initial, SteelpanFeature.BLOCK.get().defaultBlockState());
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, initial, List.of(initial),
                new BoundingBox3i(initial.getX(), initial.getY(), initial.getZ(), initial.getX(), initial.getY(), initial.getZ()));
        var plot = ship.getPlot().getCenterBlock();
        var target = SteelpanPackets.Target.placed(plot);
        helper.assertTrue(level.getBlockEntity(plot) instanceof SteelpanBlock.Entity && level.getBlockState(initial).isAir(),
                "The steelpan was not mounted on the Sable ship");

        var before = target.origin(level, 1);
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(12, 2, 7);
        var expected = ship.logicalPose().transformPosition(SteelpanPackets.Target.soundPosition(plot));
        var moved = target.origin(level, 1);
        helper.assertTrue(before != null && moved != null && moved.distanceTo(expected) < .001 && before.distanceTo(moved) > 8,
                "The audio origin did not move with the ship");
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(moved.x + 1, moved.y, moved.z);
        helper.assertTrue(SteelpanPackets.canPlay(player, target), "The player could not reach the moved steelpan");
        player.setPos(before.x, before.y, before.z);
        helper.assertFalse(SteelpanPackets.canPlay(player, target), "The player could play from the ship's old position");
        helper.succeed();
    }
}
