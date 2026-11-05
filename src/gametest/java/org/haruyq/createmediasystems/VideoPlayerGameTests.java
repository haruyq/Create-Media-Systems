package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsPacket;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class VideoPlayerGameTests {
    @GameTest(template = "empty")
    public static void wrenchSetsLightingAndVolumeApart(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, CreateMediaSystems.VIDEO_PLAYER.get());
        VideoPlayerBlock.Entity player = helper.getBlockEntity(pos);
        ServerPlayer wrencher = FakePlayerFactory.getMinecraft(helper.getLevel());
        wrencher.setPos(Vec3.atCenterOf(helper.absolutePos(pos.north())));
        // What the client sends when a value box is set with a wrench (see Create's ValueSettingsClient).
        int lightingId = player.getBehaviour(ScrollValueBehaviour.TYPE).netId(); // lighting has the default type
        new ValueSettingsPacket(helper.absolutePos(pos), 0, 40, null, null, Direction.NORTH, false, lightingId)
                .handle(wrencher);
        helper.assertTrue(player.lighting() == 0.4f, "lighting set, was " + player.lighting());
        helper.assertTrue(player.volume() == 1, "volume untouched, was " + player.volume());
        helper.succeed();
    }
}
