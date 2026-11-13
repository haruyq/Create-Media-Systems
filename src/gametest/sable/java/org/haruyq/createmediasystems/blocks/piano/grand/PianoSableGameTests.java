package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.List;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class PianoSableGameTests {
    @GameTest(template = "empty")
    public static void shipCarriesAllPianoPartsAndTheSoundboard(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(3, 4, 3));
        var state = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.OPEN, true)
                .setValue(GrandPianoBlock.KEYBOARD_OPEN, false)
                .setValue(GrandPianoBlock.FULL_SIZE, true);
        helper.assertTrue(GrandPianoBlock.placeParts(level, root, state), "Piano placement failed");
        List<BlockPos> parts = GrandPianoBlock.parts(state).stream().map(part -> part.at(root, state)).toList();
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, root, parts, new BoundingBox3i(
                root.getX() - 1, root.getY(), root.getZ(), root.getX() + 2, root.getY() + 1, root.getZ() + 5));
        var plot = ship.getPlot().getCenterBlock();
        var mounted = (PianoBlockEntity) level.getBlockEntity(plot);
        helper.assertTrue(mounted != null && mounted.frame().lidOpen()
                && !mounted.getBlockState().getValue(GrandPianoBlock.KEYBOARD_OPEN), "Sable did not preserve both lids");
        var initial = mounted.frame().origin();
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(7, 0, 12);
        var expected = ship.logicalPose().transformPosition(GrandPianoBlock.soundboard(plot, state));
        helper.assertTrue(mounted.frame().origin().distanceTo(expected) < .001 && initial.distanceTo(expected) > 8,
                "Soundboard position failed to follow the moved and turned ship");
        for (var part : GrandPianoBlock.parts(state)) {
            var onPlot = part.at(plot, state);
            var carried = level.getBlockState(onPlot);
            helper.assertTrue(carried.is(PianoFeature.PIANO.get()) && GrandPianoBlock.root(onPlot, carried).equals(plot)
                    && level.getBlockState(part.at(root, state)).isAir(), "Split piano part " + part);
        }

        var landing = root.east(6);
        var transform = new SubLevelAssemblyHelper.AssemblyTransform(plot, landing, 3, Rotation.CLOCKWISE_90, level);
        SubLevelAssemblyHelper.moveBlocks(level, transform, parts.stream().map(pos -> plot.offset(pos.subtract(root))).toList());
        var container = SubLevelContainer.getContainer(level);
        var plotPos = ship.getPlot().plotPos;
        var grid = container.getOrigin();
        container.removeSubLevel(plotPos.x - grid.x, plotPos.z - grid.y, SubLevelRemovalReason.REMOVED);
        var landed = level.getBlockState(landing);
        helper.assertTrue(landed.is(PianoFeature.PIANO.get()) && landed.getValue(GrandPianoBlock.OPEN)
                && !landed.getValue(GrandPianoBlock.KEYBOARD_OPEN), "The piano did not retain both lids after landing");
        for (var part : GrandPianoBlock.parts(landed)) {
            var pos = part.at(landing, landed);
            var restored = level.getBlockState(pos);
            helper.assertTrue(restored.is(PianoFeature.PIANO.get()) && restored.getValue(GrandPianoBlock.PART) == part
                    && GrandPianoBlock.root(pos, restored).equals(landing), "Misplaced piano part " + part);
        }
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(10)).isEmpty(),
                "Moving between world and plot duplicated the piano");
        helper.succeed();
    }
}
