package org.haruyq.createmediasystems.blocks.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class LineMixerGameTests {
    /** A mixer at {@code pos}, both its halves, its front to the south. */
    private static LineMixerBlock.Entity mixer(ServerLevel level, BlockPos pos) {
        BlockState state = AudioFeature.LINE_MIXER.get().defaultBlockState().setValue(LineMixerBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(pos, state);
        level.setBlockAndUpdate(pos.relative(LineMixerBlock.right(state)), state.setValue(LineMixerBlock.RIGHT, true));
        return (LineMixerBlock.Entity) level.getBlockEntity(pos);
    }

    /** A Blu-ray player at {@code pos} playing a disc of its own; what it plays. */
    private static String playing(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        String url = "cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID();
        ItemStack disc = new ItemStack(DiscFeature.BLU_RAY.get());
        disc.set(DiscFeature.PLAYLIST.get(), List.of(url));
        ((VideoPlayerBlock.Entity) level.getBlockEntity(pos)).insert(disc, null);
        return url;
    }

    /** How loud {@code received} last had the speaker at {@code speaker} play the source at {@code source}; -1 if never. */
    private static float volume(List<CustomPacketPayload> received, BlockPos speaker, BlockPos source) {
        float volume = -1;
        for (CustomPacketPayload payload : received) {
            if (payload instanceof StreamPackets.PlayAtBlock play && play.pos().equals(speaker)
                    && play.sound().source().equals(source)) {
                volume = play.range().volume();
            } else if (payload instanceof StreamPackets.RetuneAtBlock retune && retune.pos().equals(speaker)
                    && retune.source().equals(source)) {
                volume = retune.range().volume();
            }
        }
        return volume;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4;
    }

    /**
     * A speaker linked to a mixer plays every source plugged into it, each as loud as its channel's fader and the
     * master's have it; a fader moved is heard without the track starting over, and a source unplugged stops alone.
     */
    @GameTest(template = "empty")
    public static void aSpeakerLinkedToAMixerPlaysEachSourceAtItsFader(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 2, 1)), second = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos mixerPos = helper.absolutePos(new BlockPos(1, 2, 4)), speakerPos = helper.absolutePos(new BlockPos(5, 2, 4));
        String firstUrl = playing(level, first), secondUrl = playing(level, second);
        float own = ((VideoPlayerBlock.Entity) level.getBlockEntity(first)).gain();
        LineMixerBlock.Entity mixer = mixer(level, mixerPos);
        level.setBlockAndUpdate(speakerPos, AudioFeature.SPEAKER.get().defaultBlockState());
        SpeakerBlock.Entity speaker = (SpeakerBlock.Entity) level.getBlockEntity(speakerPos);
        List<CustomPacketPayload> received = new ArrayList<>();
        ServerPlayer listener = VideoPlayerGameTests.listener(level, speakerPos, received);
        try {
            mixer.plug(level, 0, first);
            mixer.plug(level, 1, second);
            mixer.setFader(1, 0.5f);
            speaker.configure(level, mixerPos, 1, Streams.Channel.MIX);
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof StreamPackets.PlayAtBlock play
                            && play.url().equals(firstUrl) && play.sound().source().equals(first))
                            && received.stream().anyMatch(p -> p instanceof StreamPackets.PlayAtBlock play
                            && play.url().equals(secondUrl) && play.sound().source().equals(second)),
                    "the speaker plays both sources: " + received);
            helper.assertTrue(near(volume(received, speakerPos, first), own)
                            && near(volume(received, speakerPos, second), own * 0.25f),
                    "each as loud as its fader has it: " + volume(received, speakerPos, first) + ", "
                            + volume(received, speakerPos, second));

            received.clear();
            mixer.setFader(LineMixerBlock.MASTER, 0.5f);
            helper.assertTrue(near(volume(received, speakerPos, first), own * 0.25f)
                            && near(volume(received, speakerPos, second), own * 0.0625f),
                    "the master turns both down at once: " + received);
            helper.assertTrue(received.stream().noneMatch(p -> p instanceof StreamPackets.PlayAtBlock
                    || p instanceof StreamPackets.StopAtBlock), "without starting either over: " + received);

            received.clear();
            mixer.plug(level, 0, null);
            helper.assertTrue(received.contains(new StreamPackets.StopAtBlock(speakerPos, first))
                            && !received.contains(new StreamPackets.StopAtBlock(speakerPos, second)),
                    "a source unplugged stops, the other plays on: " + received);

            // The same source in another channel moves there: it is never fed twice.
            mixer.plug(level, 4, second);
            helper.assertTrue(mixer.input(1) == null && second.equals(mixer.input(4)) && mixer.feeds(level).size() == 1,
                    "a source is in one channel at a time");
        } finally {
            Streams.stop(speaker);
            level.removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    /**
     * A mixer set against another's left side is its extension: seven channels more, numbered before its own, under
     * its master, for a speaker linked to either. Standing alone again, it has its own five and its master.
     */
    @GameTest(template = "empty")
    public static void aMixerToTheLeftOfAnotherExtendsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos mainPos = helper.absolutePos(new BlockPos(3, 2, 4));
        LineMixerBlock.Entity main = mixer(level, mainPos);
        BlockPos extensionPos = mainPos.relative(LineMixerBlock.right(main.getBlockState()), -2);
        LineMixerBlock.Entity extension = mixer(level, extensionPos);
        BlockPos first = helper.absolutePos(new BlockPos(1, 2, 1)), second = helper.absolutePos(new BlockPos(3, 2, 1));
        playing(level, first);
        playing(level, second);
        helper.assertTrue(main.channels() == LineMixerBlock.MAIN_CHANNELS && extension.channels() == LineMixerBlock.EXTENSION_CHANNELS
                && extension.extension() && !main.extension() && extension.firstChannel() == 0
                && main.firstChannel() == LineMixerBlock.EXTENSION_CHANNELS, "the one to the left is the extension");

        main.plug(level, 0, first);
        extension.plug(level, 6, second);
        main.setFader(LineMixerBlock.MASTER, 0.5f);
        extension.setFader(6, 0.5f);
        extension.setFader(LineMixerBlock.MASTER, 0.1f);
        for (LineMixerBlock.Entity linked : List.of(main, extension)) {
            helper.assertTrue(linked.feeds(level).equals(List.of(new org.haruyq.createmediasystems.stream.MixBus.Feed(second, 0.0625f),
                    new org.haruyq.createmediasystems.stream.MixBus.Feed(first, 0.25f))),
                    "both play the row's sources under the rightmost one's master: " + linked.feeds(level));
        }
        // Plugged in again elsewhere in the row, a source leaves where it was.
        extension.plug(level, 0, first);
        helper.assertTrue(main.input(0) == null && first.equals(extension.input(0)), "a source is in one channel of the row");

        level.destroyBlock(mainPos, false);
        helper.assertTrue(extension.channels() == LineMixerBlock.MAIN_CHANNELS && !extension.extension(),
                "alone, it is a mixer of its own");
        helper.assertTrue(extension.feeds(level).equals(List.of(new org.haruyq.createmediasystems.stream.MixBus.Feed(first, 1f))),
                "with its own master, and only the channels it then has: " + extension.feeds(level));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void breakingEitherHalfRemovesTheOther(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos right = pos.relative(LineMixerBlock.right(mixer(level, pos).getBlockState()));
        level.destroyBlock(right, false);
        helper.assertTrue(level.getBlockState(pos).isAir(), "the left half goes with the right one");
        mixer(level, pos);
        level.destroyBlock(pos, false);
        helper.assertTrue(level.getBlockState(right).isAir(), "the right half goes with the left one");
        helper.succeed();
    }
}
