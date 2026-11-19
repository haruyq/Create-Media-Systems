package org.haruyq.createmediasystems.blocks.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.blocks.audio.LineMixerBlock;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxBlock;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxFeature;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxKey;
import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import org.haruyq.createmediasystems.blocks.piano.PianoTestPlayers;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanFeature;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RadioGameTests {
    /** A transmitter linked to a tower of {@code masts} masts and an antenna, east of it. */
    private static RadioTransmitterBlock.Transmitter station(GameTestHelper h, int masts) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(at, RadioFeature.TRANSMITTER.get().defaultBlockState());
        for (int y = 0; y < masts; y++) {
            level.setBlockAndUpdate(at.east().above(y), RadioFeature.MAST.get().defaultBlockState());
        }
        level.setBlockAndUpdate(at.east().above(masts), RadioFeature.ANTENNA.get().defaultBlockState());
        var transmitter = (RadioTransmitterBlock.Transmitter) level.getBlockEntity(at);
        h.assertTrue(transmitter.linkTower(level, at.east()), "A tower with an antenna links from any of its blocks");
        return transmitter;
    }

    @GameTest(batch = "launcher", template = "empty")
    public static void aMastUsedOnATowerGoesOnTopAndTheReachFollowsTheHeight(GameTestHelper h) {
        var transmitter = station(h, 1);
        var level = h.getLevel();
        var foot = transmitter.getBlockPos().east();
        h.assertTrue(transmitter.masts() == 1 && RadioFeature.reach(2) - RadioFeature.reach(1) == RadioFeature.REACH_PER_MAST.getAsInt()
                && RadioFeature.reach(0) == RadioFeature.BASE_REACH, "Each mast under the antenna adds the same reach");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var masts = new ItemStack(RadioFeature.MAST_ITEM.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, masts);
        var hit = new BlockHitResult(foot.getCenter(), Direction.NORTH, foot, false);
        level.getBlockState(foot).useItemOn(masts, level, player, InteractionHand.MAIN_HAND, hit);
        transmitter.relay(level);
        h.assertTrue(level.getBlockState(foot.above()).is(RadioFeature.MAST.get())
                && foot.above(2).equals(transmitter.antenna()) && transmitter.masts() == 2 && masts.getCount() == 1,
                "A mast used on a finished tower goes under its antenna, which moves up one");

        level.setBlockAndUpdate(foot.above(2), Blocks.AIR.defaultBlockState());
        transmitter.relay(level);
        h.assertTrue(transmitter.antenna() == null, "A tower that lost its antenna is off air");
        level.getBlockState(foot).useItemOn(masts, level, player, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(level.getBlockState(foot.above(2)).is(RadioFeature.MAST.get()), "A mast used on an unfinished tower goes on its top");
        h.succeed();
    }

    @GameTest(batch = "launcher", template = "empty", timeoutTicks = 100)
    public static void aLoudmanOnTheStationsFrequencyHearsItAndTheSourceKeepsItsOwnSpeakers(GameTestHelper h) {
        var transmitter = station(h, 1);
        var level = h.getLevel();
        var at = transmitter.getBlockPos();
        var source = at.south();
        level.setBlockAndUpdate(source, AudioFeature.DISC_READER.get().defaultBlockState());
        var disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString());
        ((DiscReaderBlock.Entity) level.getBlockEntity(source)).insert(disc, null);
        transmitter.configure(level, source, 1, Streams.Channel.MIX);

        var listener = PianoTestPlayers.near(h, at.getCenter());
        // A frequency of its own: the tests run side by side, within reach of each other's stations.
        RadioTransmitterBlock.setFrequency(listener, new RadioPackets.SetFrequency(at, 901));
        var loudman = new ItemStack(LoudmanFeature.BLUE.get());
        loudman.set(LoudmanFeature.TUNING.get(), new LoudmanItem.Tuning(transmitter.frequency(), LoudmanItem.MAX_VOLUME, true));
        listener.setItemInHand(InteractionHand.MAIN_HAND, loudman);
        h.runAfterDelay(2 * Streams.RELAY_TICKS, () -> {
            h.assertTrue(RadioStations.audience(level, at).containsKey(listener), "A Loudman on its frequency receives the station");
            h.assertTrue(Streams.relays().stream().anyMatch(relay -> relay.sound() != null
                    && relay.sound().source().equals(source) && relay.listeners() == 1), "The source's track is sent to the listener");
            var radio = RadioStations.get(level);
            var saved = RadioStations.load(radio.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(radio.stations.containsKey(at) && saved.stations.equals(radio.stations), "The stations on air are saved with the world");
            h.assertTrue(Streams.emitters(level, source).isEmpty(),
                    "The transmitter is no speaker of its source: a display linked to the same source keeps its sound");
            loudman.set(LoudmanFeature.TUNING.get(), new LoudmanItem.Tuning(transmitter.frequency() + 10, LoudmanItem.MAX_VOLUME, true));
        });
        h.runAfterDelay(4 * Streams.RELAY_TICKS, () -> {
            h.assertTrue(RadioStations.audience(level, at).isEmpty() && Streams.relays().stream().noneMatch(
                    relay -> relay.sound() != null && relay.sound().source().equals(source)), "Tuned away, it stops receiving");
            h.succeed();
        });
    }

    /** Linked to a mixer, a station sends each source the mixer feeds, each as loud as its fader has it. */
    @GameTest(batch = "launcher", template = "empty", timeoutTicks = 100)
    public static void aStationLinkedToAMixerSendsEachSourceItFeeds(GameTestHelper h) {
        var transmitter = station(h, 1);
        var level = h.getLevel();
        var at = transmitter.getBlockPos();
        BlockPos first = h.absolutePos(new BlockPos(4, 2, 1)), second = h.absolutePos(new BlockPos(5, 2, 1));
        BlockPos mixerPos = h.absolutePos(new BlockPos(1, 2, 4));
        for (BlockPos player : List.of(first, second)) {
            level.setBlockAndUpdate(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
            var disc = new ItemStack(DiscFeature.BLU_RAY.get());
            disc.set(DiscFeature.PLAYLIST.get(), List.of("cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID()));
            ((VideoPlayerBlock.Entity) level.getBlockEntity(player)).insert(disc, null);
        }
        float own = ((VideoPlayerBlock.Entity) level.getBlockEntity(first)).gain();
        BlockState state = AudioFeature.LINE_MIXER.get().defaultBlockState().setValue(LineMixerBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(mixerPos, state);
        level.setBlockAndUpdate(mixerPos.relative(LineMixerBlock.right(state)), state.setValue(LineMixerBlock.RIGHT, true));
        var mixer = (LineMixerBlock.Entity) level.getBlockEntity(mixerPos);
        mixer.plug(level, 0, first);
        mixer.plug(level, 1, second);
        mixer.setFader(1, 0.5f);
        transmitter.configure(level, mixerPos, 1, Streams.Channel.MIX);

        List<CustomPacketPayload> received = new ArrayList<>();
        var listener = VideoPlayerGameTests.listener(level, at, received);
        RadioTransmitterBlock.setFrequency(listener, new RadioPackets.SetFrequency(at, 955));
        var loudman = new ItemStack(LoudmanFeature.BLACK.get());
        loudman.set(LoudmanFeature.TUNING.get(), new LoudmanItem.Tuning(transmitter.frequency(), LoudmanItem.MAX_VOLUME, true));
        listener.setItemInHand(InteractionHand.MAIN_HAND, loudman);
        h.runAfterDelay(2 * Streams.RELAY_TICKS, () -> {
            try {
                h.assertTrue(received.stream().anyMatch(p -> p instanceof RadioPackets.Signal signal && signal.strength() == 1),
                        "Beside the tower, the listener is told the signal is full: " + received);
                for (BlockPos source : List.of(first, second)) {
                    h.assertTrue(received.stream().anyMatch(p -> p instanceof RadioPackets.Play play
                            && play.station().pos().equals(at) && play.station().source().equals(source)),
                            "The listener is sent each source the mixer feeds: " + received);
                    float volume = (float) received.stream().filter(p -> p instanceof RadioPackets.Tune tune
                            && tune.station().source().equals(source)).mapToDouble(p -> ((RadioPackets.Tune) p).volume())
                            .reduce(-1, (before, last) -> last);
                    h.assertTrue(Math.abs(volume - own * (source.equals(first) ? 1 : 0.25f)) < 1e-4,
                            "Each as loud as its fader has it: " + volume);
                }
            } finally {
                level.removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
            }
            h.succeed();
        });
    }

    /**
     * A CD radio on a station's frequency plays the station's source to whoever is near it, with no Loudman, and a
     * display linked to that source keeps its own sound beside it. Tuned away, the set stops and hisses.
     */
    @GameTest(batch = "launcher", template = "empty", timeoutTicks = 100)
    public static void aCdRadioOnTheStationsFrequencyPlaysItToThoseNearIt(GameTestHelper h) {
        var transmitter = station(h, 1);
        var level = h.getLevel();
        var at = transmitter.getBlockPos();
        var source = at.south();
        // A Blu-ray player: a display plays the sound of none but a source with a picture.
        level.setBlockAndUpdate(source, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        var disc = new ItemStack(DiscFeature.BLU_RAY.get());
        disc.set(DiscFeature.PLAYLIST.get(), List.of("cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID()));
        ((VideoPlayerBlock.Entity) level.getBlockEntity(source)).insert(disc, null);
        transmitter.configure(level, source, 1, Streams.Channel.MIX);
        BlockPos anchor = h.absolutePos(new BlockPos(6, 2, 1)), setPos = h.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        var display = (DisplayBlock.Entity) level.getBlockEntity(anchor);
        display.link(source);
        level.setBlockAndUpdate(setPos, BoomboxFeature.BLOCK.get().defaultBlockState());
        var set = (BoomboxBlock.Entity) level.getBlockEntity(setPos);

        List<CustomPacketPayload> received = new ArrayList<>();
        var listener = VideoPlayerGameTests.listener(level, setPos, received);
        // A frequency of its own, one step above where the set is first tuned.
        RadioTransmitterBlock.setFrequency(listener, new RadioPackets.SetFrequency(at, 933));
        CompoundTag tuned = set.saveWithoutMetadata(level.registryAccess());
        tuned.putInt("Frequency", 932);
        set.loadCustomOnly(tuned, level.registryAccess());
        set.press(listener, BoomboxKey.NEXT);
        h.assertTrue(set.frequency() == 932, "Switched off, its keys do nothing");
        set.press(listener, BoomboxKey.POWER);
        set.press(listener, BoomboxKey.NEXT);
        h.runAfterDelay(2 * Streams.RELAY_TICKS, () -> {
            try {
                display.relay(level);
                h.assertTrue(set.frequency() == 933 && set.signal() == 1 && set.hiss() == 0, "Beside the tower it receives the station in full");
                h.assertTrue(received.stream().anyMatch(p -> p instanceof StreamPackets.PlayAtBlock play && play.pos().equals(setPos)
                        && play.sound().source().equals(source) && play.range().volume() > 0),
                        "The listener, who carries no Loudman, is sent the source's track from the set: " + received);
                float shown = (float) received.stream().mapToDouble(p -> p instanceof StreamPackets.PlayAtBlock play
                        && play.pos().equals(anchor) ? play.range().volume() : p instanceof StreamPackets.RetuneAtBlock retune
                        && retune.pos().equals(anchor) ? retune.range().volume() : -1).filter(volume -> volume >= 0)
                        .reduce(-1, (before, last) -> last);
                h.assertTrue(shown > 0, "The source's display keeps its sound beside the set: " + shown);
                set.press(listener, BoomboxKey.NEXT);
                h.assertTrue(set.signal() == 0 && set.hiss() > 0 && !Streams.emitters(level, source).contains(set),
                        "Tuned away, it stops the station and hisses");
            } finally {
                level.removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
            }
            h.succeed();
        });
    }
}
