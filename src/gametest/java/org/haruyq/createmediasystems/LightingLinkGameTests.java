package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import java.util.ArrayList;
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
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class LightingLinkGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void linksFollowSeparateShipsAndRotatedDisassembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 2));
        List<BlockPos> blocks = console(level, origin);
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
        check(helper, plot, movedAboard, ground, audio, movedVideo, operator);
        helper.assertTrue(((LightingConsoleBlock.Entity) level.getBlockEntity(plot)).settings.groups[2].colors[0] == 0x123456,
                "palette survives console assembly");

        ServerSubLevel second = SubLevelAssemblyHelper.assembleBlocks(level, ground, List.of(ground),
                new BoundingBox3i(ground.getX(), ground.getY(), ground.getZ(), ground.getX(), ground.getY(), ground.getZ()));
        BlockPos secondPlot = second.getPlot().getCenterBlock();
        check(helper, plot, movedAboard, secondPlot, audio, movedVideo, operator);
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(10, 0, 15);
        second.logicalPose().orientation().rotationY(-Math.PI / 4);
        second.logicalPose().position().add(4, 0, 8);
        check(helper, plot, movedAboard, secondPlot, audio, movedVideo, operator);

        BlockPos destination = origin.south(8);
        var transform = new SubLevelAssemblyHelper.AssemblyTransform(plot, destination, 1, Rotation.COUNTERCLOCKWISE_90, level);
        List<BlockPos> moved = blocks.stream().map(pos -> plot.offset(pos.subtract(origin))).toList();
        SubLevelAssemblyHelper.moveBlocks(level, transform, moved);
        remove(level, ship);
        check(helper, destination, transform.apply(movedAboard), secondPlot, audio, transform.apply(movedVideo), operator);
        remove(level, second);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void legacyLinksReloadAndReplacementBlocksDoNotInheritThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 2));
        console(level, origin);
        BlockPos fixture = origin.east(4);
        BlockPos media = fixture.north();
        level.setBlockAndUpdate(fixture, LightingFeature.STAGE_LIGHT.get().defaultBlockState());
        level.setBlockAndUpdate(media, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        LightingConsoleBlock.Entity root = (LightingConsoleBlock.Entity) level.getBlockEntity(origin);
        root.bind((StageLightBlock.Entity) level.getBlockEntity(fixture), 2);
        root.setAudioSource(media);
        root.setVideoSource(media);
        root.settings.groups[2].colors[0] = 0x123456;
        CompoundTag savedRoot = root.saveWithFullMetadata(level.registryAccess());
        savedRoot.remove("Identity");
        savedRoot.remove("FixtureIds");
        savedRoot.remove("AudioId");
        savedRoot.remove("VideoId");
        CompoundTag savedLight = level.getBlockEntity(fixture).saveWithFullMetadata(level.registryAccess());
        savedLight.remove("ConsoleId");
        root = (LightingConsoleBlock.Entity) reload(level, origin, savedRoot);
        StageLightBlock.Entity light = (StageLightBlock.Entity) reload(level, fixture, savedLight);
        root.refreshLinks();
        helper.assertTrue(light.controller() == root, "legacy position links upgrade after reload");
        helper.assertTrue(root.audioSource().equals(media) && root.videoSource().equals(media), "legacy media links upgrade");
        helper.assertTrue(root.settings.groups[2].colors[0] == 0x123456, "reload preserves palette");
        root = (LightingConsoleBlock.Entity) reload(level, origin, root.saveWithFullMetadata(level.registryAccess()));
        light = (StageLightBlock.Entity) reload(level, fixture, light.saveWithFullMetadata(level.registryAccess()));
        root.refreshLinks();
        helper.assertTrue(light.controller() == root, "identity links survive block-entity reload");

        level.setBlockAndUpdate(media, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(media, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        root.refreshLinks();
        helper.assertTrue(root.audioSource() == null && root.videoSource() == null, "replacement media does not inherit links");
        ItemStack link = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        link.set(LightingFeature.LIGHTING_TARGET.get(), GlobalPos.of(level.dimension(), origin));
        link.set(LightingFeature.LIGHTING_SOURCE.get(), root.identity());
        Player operator = helper.makeMockPlayer(GameType.CREATIVE);
        operator.setItemInHand(InteractionHand.MAIN_HAND, link);
        level.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(origin, LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState()
                .setValue(LightingConsoleBlock.FACING, Direction.SOUTH));
        LightingConsoleBlock.Entity replacement = (LightingConsoleBlock.Entity) level.getBlockEntity(origin);
        replacement.updateStructure();
        replacement.refreshLinks();
        helper.assertTrue(light.controller() == null, "replacement console does not inherit fixture links");
        helper.assertTrue(LightingLinkItem.selected(operator) == null, "selection does not follow a replacement console");
        helper.assertTrue(replacement.fixtures().isEmpty(), "replacement console starts unlinked");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void consoleFormsAndBreaksUpOnTheNextTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 2));
        BlockPos monitor = LightingConsoleBlock.at(origin, Direction.SOUTH, 3, 1, 1);
        build(level, origin);
        level.setBlockAndUpdate(monitor, Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(!level.getBlockState(origin).getValue(LightingConsoleBlock.FORMED), "incomplete console stays apart");
            level.setBlockAndUpdate(monitor, LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                    .setValue(LightingConsoleBlock.FACING, Direction.SOUTH));
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(((LightingConsoleBlock.Entity) level.getBlockEntity(origin)).formed, "last monitor forms the console");
                level.setBlockAndUpdate(LightingConsoleBlock.at(origin, Direction.SOUTH, 2, 0, 1), Blocks.AIR.defaultBlockState());
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(!level.getBlockState(origin).getValue(LightingConsoleBlock.FORMED)
                            && !level.getBlockState(monitor).getValue(LightingConsoleBlock.FORMED), "a missing desk breaks it up");
                    helper.succeed();
                });
            });
        });
    }

    private static List<BlockPos> console(ServerLevel level, BlockPos origin) {
        List<BlockPos> blocks = build(level, origin);
        ((LightingConsoleBlock.Entity) level.getBlockEntity(origin)).updateStructure();
        return blocks;
    }

    private static List<BlockPos> build(ServerLevel level, BlockPos origin) {
        List<BlockPos> blocks = new ArrayList<>();
        Direction facing = Direction.SOUTH;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos pos = LightingConsoleBlock.at(origin, facing, x, 0, z);
                level.setBlockAndUpdate(pos, LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState()
                        .setValue(LightingConsoleBlock.FACING, facing));
                blocks.add(pos);
            }
            BlockPos monitor = LightingConsoleBlock.at(origin, facing, x, 1, 1);
            level.setBlockAndUpdate(monitor, LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                    .setValue(LightingConsoleBlock.FACING, facing));
            blocks.add(monitor);
        }
        return blocks;
    }

    private static BlockEntity reload(ServerLevel level, BlockPos pos, CompoundTag tag) {
        var state = level.getBlockState(pos);
        level.removeBlockEntity(pos);
        BlockEntity entity = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
        level.setBlockEntity(entity);
        return entity;
    }

    private static void check(GameTestHelper helper, BlockPos console, BlockPos first, BlockPos second,
                              BlockPos audio, BlockPos video, Player operator) {
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
        helper.assertTrue(a.group() == 2 && b.group() == 2 && a.order() == 0 && b.order() == 1 && root.groupSize(2) == 2,
                "group assignment and order survive relocation");
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
