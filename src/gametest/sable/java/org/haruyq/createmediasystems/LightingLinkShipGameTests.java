package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import java.util.List;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingLinkItem;
import org.haruyq.createmediasystems.lighting.StageLightBlock;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Lighting links that follow their blocks onto Sable ships and back. */
@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class LightingLinkShipGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void linksFollowSeparateShipsAndRotatedDisassembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 2));
        List<BlockPos> blocks = LightingLinkGameTests.console(level, origin);
        BlockPos aboard = origin.east(4);
        BlockPos video = aboard.north();
        BlockPos ground = origin.south(2);
        BlockPos audio = ground.east(4);
        level.setBlockAndUpdate(aboard, LightingFeature.STAGE_LIGHT.get().defaultBlockState());
        level.setBlockAndUpdate(ground, LightingFeature.LASER_PROJECTOR.get().defaultBlockState());
        level.setBlockAndUpdate(video, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(audio, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        LightingConsoleBlock.Entity root = (LightingConsoleBlock.Entity) level.getBlockEntity(origin);
        helper.assertTrue(root.bind((StageLightBlock.Entity) level.getBlockEntity(aboard), 2), "onboard fixture binds");
        helper.assertTrue(root.bind((StageLightBlock.Entity) level.getBlockEntity(ground), 2), "ground fixture binds");
        root.setAudioSource(audio);
        root.setVideoSource(video);
        root.settings.groups[2].colors[0] = 0x123456;
        ItemStack link = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        link.set(LightingFeature.LIGHTING_TARGET.get(), GlobalPos.of(level.dimension(), origin));
        Player operator = helper.makeMockPlayer(GameType.CREATIVE);
        operator.setItemInHand(InteractionHand.MAIN_HAND, link);
        helper.assertTrue(LightingLinkItem.selected(operator) == root, "legacy item selection adopts an identity");
        blocks.add(aboard);
        blocks.add(video);

        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks,
                new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ() - 1,
                        origin.getX() + 4, origin.getY() + 1, origin.getZ()));
        BlockPos plot = ship.getPlot().getCenterBlock();
        BlockPos movedAboard = plot.offset(aboard.subtract(origin));
        BlockPos movedVideo = plot.offset(video.subtract(origin));
        // The console faces south, so its player sees east as right: the ground light, west of the one aboard, is first.
        check(helper, plot, movedAboard, ground, audio, movedVideo, operator, false);
        helper.assertTrue(((LightingConsoleBlock.Entity) level.getBlockEntity(plot)).settings.groups[2].colors[0] == 0x123456,
                "palette survives console assembly");

        ServerSubLevel second = SubLevelAssemblyHelper.assembleBlocks(level, ground, List.of(ground),
                new BoundingBox3i(ground.getX(), ground.getY(), ground.getZ(), ground.getX(), ground.getY(), ground.getZ()));
        BlockPos secondPlot = second.getPlot().getCenterBlock();
        check(helper, plot, movedAboard, secondPlot, audio, movedVideo, operator, false);
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(10, 0, 15);
        second.logicalPose().orientation().rotationY(-Math.PI / 4);
        second.logicalPose().position().add(4, 0, 8);
        // Turned a quarter and moved off south-east, the console's ship has the ground light out past its own.
        check(helper, plot, movedAboard, secondPlot, audio, movedVideo, operator, true);

        BlockPos destination = origin.south(8);
        var transform = new SubLevelAssemblyHelper.AssemblyTransform(plot, destination, 1, Rotation.COUNTERCLOCKWISE_90, level);
        List<BlockPos> moved = blocks.stream().map(pos -> plot.offset(pos.subtract(origin))).toList();
        SubLevelAssemblyHelper.moveBlocks(level, transform, moved);
        remove(level, ship);
        // Back on the ground facing east, its player sees south as left, where the ground light's ship is.
        check(helper, destination, transform.apply(movedAboard), secondPlot, audio, transform.apply(movedVideo), operator, false);
        remove(level, second);
        helper.succeed();
    }

    /** {@code first} and {@code second} are the fixtures as linked; {@code firstLeft} is whether the first is the further left. */
    private static void check(GameTestHelper helper, BlockPos console, BlockPos first, BlockPos second,
                              BlockPos audio, BlockPos video, Player operator, boolean firstLeft) {
        ServerLevel level = helper.getLevel();
        LightingConsoleBlock.Entity root = (LightingConsoleBlock.Entity) level.getBlockEntity(console);
        helper.assertTrue(root != null, "console exists at its relocated position");
        root.refreshLinks();
        root.updateStructure();
        helper.assertTrue(root.formed && root.controller() == root, "multiblock remains formed after relocation");
        helper.assertTrue(root.fixtures().equals(List.of(first, second)), "fixture order follows both endpoints");
        StageLightBlock.Entity a = (StageLightBlock.Entity) level.getBlockEntity(first);
        StageLightBlock.Entity b = (StageLightBlock.Entity) level.getBlockEntity(second);
        helper.assertTrue(a.controller() == root && b.controller() == root, "ground and ship fixtures keep their console");
        helper.assertTrue(a.group() == 2 && b.group() == 2 && root.groupSize(2) == 2, "group assignment survives relocation");
        helper.assertTrue(a.order() == (firstLeft ? 0 : 1) && b.order() == (firstLeft ? 1 : 0),
                "order runs left to right as the console sees the fixtures, wherever each is");
        helper.assertTrue(audio.equals(root.audioSource()) && video.equals(root.videoSource()), "media references follow their own source");
        helper.assertTrue(LightingLinkItem.selected(operator) == root, "link item follows console identity");
    }

    private static void remove(ServerLevel level, ServerSubLevel ship) {
        var container = SubLevelContainer.getContainer(level);
        var plot = ship.getPlot().plotPos;
        var origin = container.getOrigin();
        container.removeSubLevel(plot.x - origin.x, plot.z - origin.y, SubLevelRemovalReason.REMOVED);
    }
}
