package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.lighting.ConsoleLayout;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingLinkItem;
import org.haruyq.createmediasystems.lighting.StageLightBlock;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class LightingLinkGameTests {
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
        BlockPos monitor = ConsoleLayout.at(origin, Direction.SOUTH, 3, 1, 1);
        build(level, origin);
        level.setBlockAndUpdate(monitor, Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(!level.getBlockState(origin).getValue(LightingConsoleBlock.FORMED), "incomplete console stays apart");
            level.setBlockAndUpdate(monitor, LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                    .setValue(LightingConsoleBlock.FACING, Direction.SOUTH));
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(((LightingConsoleBlock.Entity) level.getBlockEntity(origin)).formed, "last monitor forms the console");
                level.setBlockAndUpdate(ConsoleLayout.at(origin, Direction.SOUTH, 2, 0, 1), Blocks.AIR.defaultBlockState());
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(!level.getBlockState(origin).getValue(LightingConsoleBlock.FORMED)
                            && !level.getBlockState(monitor).getValue(LightingConsoleBlock.FORMED), "a missing desk breaks it up");
                    helper.succeed();
                });
            });
        });
    }

    static List<BlockPos> console(ServerLevel level, BlockPos origin) {
        List<BlockPos> blocks = build(level, origin);
        ((LightingConsoleBlock.Entity) level.getBlockEntity(origin)).updateStructure();
        return blocks;
    }

    private static List<BlockPos> build(ServerLevel level, BlockPos origin) {
        return new ArrayList<>(ConsoleLayout.place(level, origin, Direction.SOUTH, Block.UPDATE_ALL));
    }

    private static BlockEntity reload(ServerLevel level, BlockPos pos, CompoundTag tag) {
        var state = level.getBlockState(pos);
        level.removeBlockEntity(pos);
        BlockEntity entity = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
        level.setBlockEntity(entity);
        return entity;
    }
}
