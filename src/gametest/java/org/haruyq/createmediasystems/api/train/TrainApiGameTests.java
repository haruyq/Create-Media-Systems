package org.haruyq.createmediasystems.api.train;

import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.train.TrainFeature;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrainApiGameTests {
    @GameTest(template = "empty")
    public static void aReceiversChannelIsReadAndSetThroughTheApi(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        h.assertTrue(TrainChannels.channel(level, pos) == null, "No channel where there is no detector or receiver");
        h.assertFalse(TrainChannels.setChannel(level, pos, "a"), "Nothing to set there");
        level.setBlockAndUpdate(pos, TrainFeature.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState());
        h.assertTrue("".equals(TrainChannels.channel(level, pos)), "A receiver just placed is on no channel");
        h.assertTrue(TrainChannels.setChannel(level, pos, "  platform 1 "), "A receiver's channel can be set");
        h.assertTrue("platform 1".equals(TrainChannels.channel(level, pos)), "Set with its ends' spaces dropped");
        h.assertFalse(TrainChannels.setChannel(level, pos, "x".repeat(TrainChannels.MAX_LENGTH + 1)), "Too long a name is refused");
        h.assertFalse(TrainChannels.setChannel(level, pos, "a\nb"), "A control character is refused");
        h.assertTrue("platform 1".equals(TrainChannels.channel(level, pos)), "A refused name leaves it as it was");
        h.assertTrue(TrainChannels.setChannel(level, pos, ""), "It can be set to none");
        h.assertFalse(TrainChannels.isOn(level.getServer(), "platform 1"), "No train is over any detector here");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void announcementsNeedATrain(GameTestHelper h) {
        var server = h.getLevel().getServer();
        UUID none = UUID.randomUUID();
        h.assertFalse(TrainAnnouncements.play(server, none, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 1),
                "Nothing plays on a train that isn't there");
        h.assertFalse(TrainAnnouncements.stop(server, none), "Nor is there anything to stop");
        h.assertTrue(TrainAnnouncements.playing(server, none).isEmpty(), "Nor is anything playing on it");
        h.succeed();
    }
}
