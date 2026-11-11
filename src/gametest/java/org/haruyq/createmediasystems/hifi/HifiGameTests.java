package org.haruyq.createmediasystems.hifi;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class HifiGameTests {
    private static final BlockPos STACK = new BlockPos(1, 2, 1);

    private static HifiStackBlock.Entity stack(GameTestHelper helper, Unit... units) {
        helper.setBlock(STACK, HifiFeature.HIFI_STACK.get());
        HifiStackBlock.Entity stack = helper.getBlockEntity(STACK);
        for (Unit unit : units) {
            stack.add(unit);
        }
        return stack;
    }

    /** A player of this test's own: tests run side by side, and one's hand must not be another's. */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "hifi_tester"));
        player.setPos(Vec3.atCenterOf(helper.absolutePos(STACK.north())));
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    private static void hold(ServerPlayer player, ItemStack item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
    }

    @GameTest(template = "empty")
    public static void aDiscIsHeardOncePluggedInAndTakenUpWhereItPaused(GameTestHelper helper) {
        HifiStackBlock.Entity stack = stack(helper, Unit.DISC_PLAYER, Unit.AMPLIFIER);
        ServerPlayer player = player(helper);
        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), "https://example.com/hifi-disc.mp3");
        stack.press(player, Control.POWER, 0);
        stack.press(player, Control.TRAY, 0);
        helper.assertFalse(stack.trayOpen(), "a disc player not plugged into the amplifier is dead");
        hold(player, new ItemStack(HifiFeature.HIFI_CABLE.get(), 2));
        stack.press(player, Control.DISC_PLUG, 0);
        stack.press(player, Control.LINE_IN, 0);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "each cable run uses one up");
        hold(player, disc);
        stack.press(player, Control.TRAY, 0);
        helper.assertTrue(stack.trayOpen(), "the tray opens");
        stack.press(player, Control.TRAY, 0);
        helper.assertTrue(player.getMainHandItem().isEmpty() && !stack.disc().isEmpty(), "the disc is on the tray");
        stack.press(player, Control.OPEN, 0);
        helper.assertTrue(stack.discPlaying() && !stack.trayOpen(), "closing the tray plays the disc");
        helper.runAfterDelay(45, () -> {
            Streams.Source playing = Streams.playing(stack);
            helper.assertTrue(playing != null && playing.url().equals("https://example.com/hifi-disc.mp3"), "it is heard");
            long paused = stack.discTicks();
            helper.assertTrue(paused > 10 && paused < 45, "with nobody to wait for it plays on, was at " + paused);
            stack.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, player);
            helper.assertTrue(stack.nowPlaying() == null, "paused");
            helper.runAfterDelay(20, () -> {
                stack.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, player);
                helper.runAfterDelay(3, () -> {
                    Streams.Source again = Streams.playing(stack);
                    helper.assertTrue(again != null && again.ticks() == paused,
                            "it starts again where it paused, at " + paused + ", was " + (again == null ? null : again.ticks()));
                    stack.press(player, Control.LINE_OUT, 0);
                    helper.assertTrue(stack.nowPlaying() == null && stack.discPlaying(), "without its line cable it plays unheard");
                    helper.assertTrue(player.getInventory().countItem(HifiFeature.HIFI_CABLE.get()) == 1,
                            "a cable pulled out comes back");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void aTapeRemembersWhereItWasStopped(GameTestHelper helper) {
        HifiStackBlock.Entity stack = stack(helper, Unit.CASSETTE_DECK, Unit.AMPLIFIER);
        ServerPlayer player = player(helper);
        hold(player, new ItemStack(HifiFeature.HIFI_CABLE.get()));
        stack.press(player, Control.POWER, 0);
        stack.press(player, Control.SOCKET_DECK, 0);
        stack.press(player, Control.INPUT_TAPE, 0);
        ItemStack tape = new ItemStack(HifiFeature.CASSETTE.get());
        tape.set(VideoFeature.PLAYLIST.get(), List.of("https://example.com/hifi-a.mp3", "https://example.com/hifi-b.mp3"));
        hold(player, tape);
        stack.press(player, Control.DOOR_1, 0);
        stack.press(player, Control.PLAY_1, 0);
        helper.assertTrue(stack.playingDeck() == 0, "deck I plays");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(stack.nowPlaying() != null, "the tape is heard");
            stack.press(player, Control.FORWARD_1, 0);
            helper.runAfterDelay(4, () -> {
                helper.assertTrue(stack.nowPlaying() == null, "not while it winds");
                stack.press(player, Control.STOP_1, 0);
                long stopped = stack.tapeTicks(0);
                helper.assertTrue(stopped >= 3 * HifiStackBlock.WIND, "wound on, to " + stopped);
                stack.press(player, Control.DOOR_1, 0);
                ItemStack out = player.getInventory().items.stream().filter(item -> item.is(HifiFeature.CASSETTE.get()))
                        .findFirst().orElse(ItemStack.EMPTY);
                helper.assertTrue(stack.tape(0).isEmpty() && out.is(HifiFeature.CASSETTE.get()), "the tape comes out");
                hold(player, out);
                stack.press(player, Control.DOOR_2, 0);
                helper.assertTrue(stack.tapeTicks(1) == stopped, "deck II takes it up at " + stopped + ", was at " + stack.tapeTicks(1));
                stack.press(player, Control.PLAY_2, 0);
                helper.runAfterDelay(3, () -> {
                    Streams.Source playing = Streams.playing(stack);
                    helper.assertTrue(playing != null && playing.ticks() == stopped, "and is heard from there");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void aDiscStartedUnheardLeavesTheTapeAloneAndIsTakenUpWhereItIs(GameTestHelper helper) {
        HifiStackBlock.Entity stack = stack(helper, Unit.CASSETTE_DECK, Unit.DISC_PLAYER, Unit.AMPLIFIER);
        ServerPlayer player = player(helper);
        hold(player, new ItemStack(HifiFeature.HIFI_CABLE.get(), 3));
        stack.press(player, Control.POWER, 0);
        stack.press(player, Control.SOCKET_DECK, 0);
        stack.press(player, Control.SOCKET_DISC, 0);
        stack.press(player, Control.LINE_IN, 0);
        stack.press(player, Control.INPUT_TAPE, 0);
        ItemStack tape = new ItemStack(HifiFeature.CASSETTE.get());
        tape.set(VideoFeature.PLAYLIST.get(), List.of("https://example.com/hifi-tape.mp3"));
        hold(player, tape);
        stack.press(player, Control.DOOR_1, 0);
        stack.press(player, Control.PLAY_1, 0);
        helper.runAfterDelay(5, () -> {
            long tapeLoad = Streams.loadId(stack);
            helper.assertTrue(tapeLoad != 0 && Streams.playing(stack) != null, "the tape is heard");
            ItemStack disc = new ItemStack(HifiFeature.CD.get());
            disc.set(VideoFeature.PLAYLIST.get(), List.of("https://example.com/hifi-cd.mp3"));
            stack.press(player, Control.TRAY, 0);
            hold(player, disc);
            stack.press(player, Control.TRAY, 0);
            stack.press(player, Control.OPEN, 0);
            helper.assertTrue(stack.discPlaying(), "the disc plays, unheard");
            helper.runAfterDelay(30, () -> {
                Streams.Source heard = Streams.playing(stack);
                helper.assertTrue(heard != null && heard.url().equals("https://example.com/hifi-tape.mp3")
                        && Streams.loadId(stack) == tapeLoad, "the tape plays on, not started over by the disc");
                long discAt = stack.discTicks();
                helper.assertTrue(discAt >= 29, "the disc has played on meanwhile, to " + discAt);
                stack.press(player, Control.INPUT_DISC, 0);
                helper.runAfterDelay(3, () -> {
                    Streams.Source disc2 = Streams.playing(stack);
                    helper.assertTrue(disc2 != null && disc2.url().equals("https://example.com/hifi-cd.mp3")
                            && disc2.ticks() >= discAt && disc2.ticks() <= discAt + 1, "the disc is heard from where it is, "
                            + discAt + ", was " + (disc2 == null ? null : disc2.ticks()));
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void aSpeakerCableRunsFromATerminalToASpeaker(GameTestHelper helper) {
        HifiStackBlock.Entity stack = stack(helper, Unit.AMPLIFIER);
        ServerPlayer player = player(helper);
        BlockPos speakerPos = STACK.east(2);
        helper.setBlock(speakerPos, HifiFeature.HIFI_SPEAKER.get());
        HifiSpeakerBlock.Entity speaker = helper.getBlockEntity(speakerPos);
        hold(player, new ItemStack(HifiFeature.HIFI_CABLE.get()));
        helper.useBlock(speakerPos, player);
        helper.assertTrue(speaker.source().isEmpty(), "a cable that starts at no terminal goes nowhere");
        stack.press(player, Control.SPEAKER_LEFT, 0);
        helper.useBlock(speakerPos, player);
        helper.assertTrue(speaker.source().map(helper.absolutePos(STACK)::equals).orElse(false)
                && speaker.channel() == Streams.Channel.LEFT, "linked to the amplifier's left channel");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the cable is used up");
        player.setShiftKeyDown(true);
        helper.useBlock(speakerPos, player);
        helper.assertTrue(speaker.source().isEmpty() && player.getInventory().countItem(HifiFeature.HIFI_CABLE.get()) == 1,
                "pulled out, the cable comes back");
        player.setShiftKeyDown(false);
        hold(player, ItemStack.EMPTY);
        stack.pop(player);
        helper.assertBlockNotPresent(HifiFeature.HIFI_STACK.get(), STACK);
        helper.assertTrue(player.getInventory().countItem(HifiFeature.hifiUnit(Unit.AMPLIFIER)) == 1,
                "the last unit taken off takes the stack with it");
        helper.succeed();
    }

    /** The remote seeks the disc playing: heard, it is loaded again from there; paused, it plays on from there. */
    @GameTest(template = "empty")
    public static void aRemoteSeeksTheDisc(GameTestHelper helper) {
        HifiStackBlock.Entity stack = stack(helper, Unit.DISC_PLAYER, Unit.AMPLIFIER);
        ServerPlayer player = player(helper);
        hold(player, new ItemStack(HifiFeature.HIFI_CABLE.get(), 2));
        stack.press(player, Control.POWER, 0);
        stack.press(player, Control.DISC_PLUG, 0);
        stack.press(player, Control.LINE_IN, 0);
        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), "https://example.com/hifi-seek.mp3");
        stack.press(player, Control.TRAY, 0);
        hold(player, disc);
        stack.press(player, Control.TRAY, 0);
        stack.press(player, Control.OPEN, 0);
        helper.runAfterDelay(HifiStackBlock.WARM_UP + 5, () -> {
            long load = Streams.loadId(stack);
            stack.control(VideoPlayerBlock.Action.SEEK, 90, player);
            helper.runAfterDelay(2, () -> {
                Streams.Source heard = Streams.playing(stack);
                helper.assertTrue(Streams.loadId(stack) != load && heard != null && heard.ticks() == 90 * 20,
                        "heard again from 90 s, was " + (heard == null ? null : heard.ticks()));
                stack.control(VideoPlayerBlock.Action.SEEK_BY, 10, player);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(Streams.playing(stack).ticks() == 100 * 20, "ten seconds on is 100 s");
                    stack.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, player);
                    stack.control(VideoPlayerBlock.Action.SEEK, 30, player);
                    helper.assertTrue(stack.discPaused() && stack.discTicks() == 30 * 20, "paused, it waits at 30 s");
                    stack.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, player);
                    helper.runAfterDelay(2, () -> {
                        helper.assertTrue(Streams.playing(stack).ticks() == 30 * 20, "and plays on from there");
                        helper.succeed();
                    });
                });
            });
        });
    }
}
