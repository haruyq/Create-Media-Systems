package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KeyboardSableGameTests {
    @GameTest(template = "empty")
    public static void keyboardSpeakersAndPowerFollowASableShip(GameTestHelper test) {
        var level = test.getLevel();
        var root = test.absolutePos(new BlockPos(3, 4, 3));
        var state = PxS1100Feature.BLOCK.get().defaultBlockState();
        var bass = KeyboardBlock.bass(root, state);
        test.assertTrue(KeyboardBlock.place(level, root, state), "Fixture placement failed");
        ((KeyboardBlockEntity) level.getBlockEntity(root)).runtime().charge(1600, false);
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, root, List.of(root, bass),
                new BoundingBox3i(root.getX(), root.getY(), root.getZ(), bass.getX(), bass.getY(), bass.getZ()));
        var plot = ship.getPlot().getCenterBlock();
        var plotBass = KeyboardBlock.bass(plot, state);
        var keyboard = (KeyboardBlockEntity) level.getBlockEntity(plot);
        test.assertTrue(keyboard != null && keyboard.runtime().energy() == 1600
                && KeyboardBlock.part(level.getBlockState(plotBass), KeyboardBlock.Part.BASS, state)
                && level.getBlockState(root).isAir() && level.getBlockState(bass).isAir(), "Sable did not move both halves and FE");
        var before = keyboard.frame();
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(7, 0, 12);
        var after = keyboard.frame();
        test.assertTrue(before.centre().distanceTo(after.centre()) > 8
                && after.left().distanceTo(ship.logicalPose().transformPosition(KeyboardBlock.speaker(plot, state, 0))) < .001
                && after.right().distanceTo(ship.logicalPose().transformPosition(KeyboardBlock.speaker(plot, state, 1))) < .001,
                "The stereo speaker positions did not follow the Sable pose");
        var landing = root.east(6);
        SubLevelAssemblyHelper.moveBlocks(level,
                new SubLevelAssemblyHelper.AssemblyTransform(plot, landing, 3, Rotation.CLOCKWISE_90, level), List.of(plot, plotBass));
        var container = SubLevelContainer.getContainer(level);
        var plotPos = ship.getPlot().plotPos;
        var grid = container.getOrigin();
        container.removeSubLevel(plotPos.x - grid.x, plotPos.z - grid.y, SubLevelRemovalReason.REMOVED);
        var landed = (KeyboardBlockEntity) level.getBlockEntity(landing);
        test.assertTrue(landed != null && landed.runtime().energy() == 1600
                && KeyboardBlock.part(level.getBlockState(KeyboardBlock.bass(landing, landed.state())), KeyboardBlock.Part.BASS, landed.state()),
                "Landing did not restore the charged keyboard");
        test.succeed();
    }
}
