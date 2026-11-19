package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.stream.StreamPackets;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoomboxGameTests {
    /**
     * The lid on its top takes a CD by hand, not a disc with a picture; shut on CD, the disc plays from its first
     * track to those near the set, the keys work it, and an empty hand takes it out of the open lid again.
     */
    @GameTest(batch = "launcher", template = "empty")
    public static void aDiscUnderTheLidPlaysOnceTheLidIsShut(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pos, BoomboxFeature.BLOCK.get().defaultBlockState());
        var set = (BoomboxBlock.Entity) level.getBlockEntity(pos);
        List<String> tracks = List.of("cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID(),
                "cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID());
        var cd = new ItemStack(DiscFeature.CD.get());
        cd.set(DiscFeature.PLAYLIST.get(), tracks);
        List<CustomPacketPayload> received = new ArrayList<>();
        var listener = VideoPlayerGameTests.listener(level, pos, received);
        try {
            set.press(listener, BoomboxKey.POWER);
            set.press(listener, BoomboxKey.CD);
            set.press(listener, BoomboxKey.OPEN);
            listener.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DiscFeature.BLU_RAY.get()));
            set.press(listener, BoomboxKey.LID);
            h.assertTrue(!set.lidOpen() && set.disc().isEmpty() && !listener.getMainHandItem().isEmpty(),
                    "A Blu-ray does not go in: the lid only shuts");

            set.press(listener, BoomboxKey.OPEN);
            listener.setItemInHand(InteractionHand.MAIN_HAND, cd);
            set.press(listener, BoomboxKey.LID);
            h.assertTrue(set.lidOpen() && set.disc().is(DiscFeature.CD.get()) && listener.getMainHandItem().isEmpty()
                    && set.nowPlaying() == null, "A CD goes under the open lid, and waits there");

            set.press(listener, BoomboxKey.PLAY);
            h.assertTrue(!set.lidOpen() && set.transport().playing() && set.index() == 0 && set.nowPlaying() != null,
                    "Play shuts the lid, and the disc plays from its first track");
            h.assertTrue(received.stream().anyMatch(p -> p instanceof StreamPackets.PlayAtBlock play && play.pos().equals(pos)
                    && play.sound().source().equals(pos) && play.url().equals(tracks.getFirst()) && play.range().volume() > 0),
                    "The listener is sent the track from the set: " + received);
            set.press(listener, BoomboxKey.NEXT);
            h.assertTrue(set.index() == 1 && set.transport().playing(), "The next key goes to the second track");
            set.press(listener, BoomboxKey.FM);
            h.assertTrue(!set.transport().playing() && set.nowPlaying() == null, "Switched to FM, the disc stops");
            set.press(listener, BoomboxKey.CD);
            set.press(listener, BoomboxKey.PLAY);
            h.assertTrue(set.transport().playing() && set.nowPlaying() != null, "Back on CD with the lid shut, play starts the disc");
            set.press(listener, BoomboxKey.STOP);
            set.press(listener, BoomboxKey.POWER);
            set.press(listener, BoomboxKey.POWER);
            set.press(listener, BoomboxKey.PLAY);
            h.assertTrue(set.transport().playing() && set.index() == 0, "Stopped and switched off and on, play starts it from the top");

            set.press(listener, BoomboxKey.OPEN);
            set.press(listener, BoomboxKey.LID);
            h.assertTrue(set.disc().isEmpty() && listener.getInventory().contains(stack -> stack.is(DiscFeature.CD.get())),
                    "An empty hand takes the disc out of the open lid");
        } finally {
            level.removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }
}
