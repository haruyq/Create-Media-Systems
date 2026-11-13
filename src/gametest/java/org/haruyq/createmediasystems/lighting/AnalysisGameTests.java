package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A console lit by a video player that one listener hears: the listener, ready, is chosen to analyse the track, and
 * what it uploads reaches the lights, as far as the server lets it.
 */
@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AnalysisGameTests {
    private static final String CURRENT = "https://youtu.be/current";
    private static final String NEXT = "https://youtu.be/next";

    /**
     * Long enough for two of a client's statuses, which the server takes at most twice a second, at any tick rate; in a
     * batch of its own, as its listener hears the other tests' speakers, and they it.
     */
    @GameTest(batch = "analysis", template = "empty", timeoutTicks = 6000)
    public static void preparedNextTrackSurvivesTrackSwitch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos playerPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos consolePos = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos speakerPos = helper.absolutePos(new BlockPos(1, 2, 2));
        level.setBlockAndUpdate(playerPos, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(consolePos, LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState());
        level.setBlockAndUpdate(speakerPos, AudioFeature.SPEAKER.get().defaultBlockState());
        VideoPlayerBlock.Entity player = (VideoPlayerBlock.Entity) level.getBlockEntity(playerPos);
        ItemStack disc = new ItemStack(DiscFeature.BLU_RAY.get());
        disc.set(DiscFeature.PLAYLIST.get(), List.of(CURRENT, NEXT));
        player.insert(disc, null);
        LightingConsoleBlock.Entity console = (LightingConsoleBlock.Entity) level.getBlockEntity(consolePos);
        console.setVideoSource(playerPos);
        SpeakerBlock.Entity speaker = (SpeakerBlock.Entity) level.getBlockEntity(speakerPos);
        List<CustomPacketPayload> received = new ArrayList<>();
        ServerPlayer viewer = VideoPlayerGameTests.listener(level, speakerPos, received);
        speaker.configure(level, playerPos, 1, Streams.Channel.MIX);
        UUID source = BlockIdentity.of(level, playerPos);
        AnalysisCoordinator.use(console);

        until(helper, console, () -> state(received, source) != null, () -> {
            helper.assertTrue(!state(received, source).active(), "lights ran on a track still loading");
            Streams.started(viewer, speakerPos, 600);
            until(helper, console, () -> active(received, source, CURRENT), () -> {
                UUID session = state(received, source).session();
                AnalysisCoordinator.status(viewer, new AnalysisPackets.Status(source, session, true, 3_000, 0, 0, 0));
                until(helper, console, () -> viewer.getUUID().equals(state(received, source).owner()), () -> {
                    int generation = state(received, source).generation();
                    uploads(helper, viewer, source, session, generation, received);
                    helper.runAfterDelay(1, () -> retries(helper, console, viewer, source, session, generation, () ->
                            delays(helper, console, viewer, source, session, received, () ->
                                    switches(helper, console, player, speaker, viewer, source, session, received))));
                });
            });
        });
    }

    /** The owner's first uploads, all in one tick. */
    private static void uploads(GameTestHelper helper, ServerPlayer viewer, UUID source, UUID session, int generation,
                                List<CustomPacketPayload> received) {
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 0, NEXT, true, 0));
        helper.assertTrue(snapshot(source).nextEnd() == 0, "prepared next track was not retained");
        helper.assertTrue(received.contains(new AnalysisPackets.Accepted(source, session, generation, 0)),
                "the owner is not told its upload was taken");
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 7, CURRENT, false, 0));
        helper.assertTrue(snapshot(source).trackEnd() == 0, "cached upload throttled a live frame in the same tick");
        helper.assertTrue(received.stream().anyMatch(p -> p instanceof AnalysisPackets.Batch batch && !batch.prepared()
                && batch.track().equals(CURRENT)), "a live frame is not passed on to who sees the lights");
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 8, CURRENT, false, 100));
        helper.assertTrue(snapshot(source).trackEnd() == 100, "a coalesced live frame was dropped in the same tick");
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 8, CURRENT, false, 200));
        helper.assertTrue(snapshot(source).trackEnd() == 100, "a duplicate live sequence was accepted");
        for (int sequence = 9; sequence <= 108; sequence++) {
            AnalysisCoordinator.upload(viewer, upload(source, session, generation, sequence, CURRENT, false, (sequence - 7) * 100L));
        }
        long burst = snapshot(source).trackEnd();
        helper.assertTrue(burst > 100 && burst < 10_100, "a burst of live frames was not limited, reached " + burst);
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 1, NEXT, true, 100));
        helper.assertTrue(snapshot(source).nextEnd() == 0, "two cached uploads were taken in one tick");
    }

    /** A tick on: what is not the owner's latest is refused, and what is, taken. */
    private static void retries(GameTestHelper helper, LightingConsoleBlock.Entity console, ServerPlayer viewer, UUID source,
                                UUID session, int generation, Runnable then) {
        AnalysisCoordinator.use(console);
        AnalysisCoordinator.upload(viewer, upload(source, session, generation - 1, 2, NEXT, true, 100));
        helper.assertTrue(snapshot(source).nextEnd() == 0, "old generation was accepted");
        AnalysisCoordinator.upload(viewer, upload(source, UUID.randomUUID(), generation, 2, NEXT, true, 100));
        helper.assertTrue(snapshot(source).nextEnd() == 0, "old session was accepted");
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 0, NEXT, true, 100));
        helper.assertTrue(snapshot(source).nextEnd() == 0, "duplicate sequence was accepted");
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 1, NEXT, true, 100));
        helper.assertTrue(snapshot(source).nextEnd() == 100, "a newer live sequence blocked the cached retry");
        long live = snapshot(source).trackEnd();
        AnalysisCoordinator.upload(viewer, upload(source, session, generation, 109, CURRENT, false, live + 100));
        helper.assertTrue(snapshot(source).trackEnd() == live + 100, "live frames are not taken again after a burst");
        then.run();
    }

    /** The owner says it lags: everyone's lights are delayed to cover it, unless what it says can't be. */
    private static void delays(GameTestHelper helper, LightingConsoleBlock.Entity console, ServerPlayer viewer, UUID source,
                               UUID session, List<CustomPacketPayload> received, Runnable then) {
        until(helper, console, () -> {
            AnalysisCoordinator.status(viewer, new AnalysisPackets.Status(source, session, true, 3_000, 600, 0, 0));
            return state(received, source).delay().to() >= 600;
        }, () -> {
            AnalysisDelay delay = state(received, source).delay();
            // A minute on, long past the last change of delay: only the lag itself can keep it as it is.
            until(helper, console, () -> {
                AnalysisCoordinator.status(viewer, new AnalysisPackets.Status(source, session, true, 60_000,
                        AnalysisDelay.MAX_MILLIS + 1, 1, 0));
                return received.stream().anyMatch(AnalysisPackets.Clock.class::isInstance);
            }, () -> helper.runAfterDelay(2, () -> {
                helper.assertTrue(state(received, source).delay().equals(delay), "invalid lag report changed the shared delay");
                then.run();
            }));
        });
    }

    /** The player goes on to the next track: the lights wait for it, and keep what was prepared of it. */
    private static void switches(GameTestHelper helper, LightingConsoleBlock.Entity console, VideoPlayerBlock.Entity player,
                                 SpeakerBlock.Entity speaker, ServerPlayer viewer, UUID source, UUID session,
                                 List<CustomPacketPayload> received) {
        ServerLevel level = helper.getLevel();
        player.control(Remotable.Action.NEXT, 0, null);
        speaker.configure(level, player.getBlockPos(), 1, Streams.Channel.MIX);
        until(helper, console, () -> !state(received, source).active(), () -> {
            Streams.started(viewer, speaker.getBlockPos(), 600);
            until(helper, console, () -> active(received, source, NEXT), () -> {
                AnalysisPackets.State state = state(received, source);
                helper.assertTrue(!state.session().equals(session), "track switch kept the old session");
                helper.assertTrue(snapshot(source).trackEnd() == 100, "track switch discarded prepared analysis");
                helper.assertTrue(state.delay().equals(AnalysisDelay.INITIAL), "track switch kept the previous live delay");
                Streams.stop(speaker);
                level.removePlayerImmediately(viewer, Entity.RemovalReason.DISCARDED);
                helper.succeed();
            });
        });
    }

    /**
     * Once {@code done} holds, checked a tick at a time with {@code console} kept on its source, runs {@code then};
     * the test times out if it never does.
     */
    private static void until(GameTestHelper helper, LightingConsoleBlock.Entity console, BooleanSupplier done, Runnable then) {
        helper.runAfterDelay(1, () -> {
            AnalysisCoordinator.use(console);
            if (done.getAsBoolean()) {
                then.run();
            } else {
                until(helper, console, done, then);
            }
        });
    }

    /** The last state of {@code source}'s lights its listener was sent; null if none. */
    private static AnalysisPackets.State state(List<CustomPacketPayload> received, UUID source) {
        for (int i = received.size() - 1; i >= 0; i--) {
            if (received.get(i) instanceof AnalysisPackets.State state && state.source().equals(source)) {
                return state;
            }
        }
        return null;
    }

    private static boolean active(List<CustomPacketPayload> received, UUID source, String track) {
        AnalysisPackets.State state = state(received, source);
        return state != null && state.active() && state.track().equals(track);
    }

    private static AnalysisCoordinator.Snapshot snapshot(UUID source) {
        return AnalysisCoordinator.snapshots().stream().filter(each -> each.source().equals(source)).findFirst().orElseThrow();
    }

    private static AnalysisPackets.Upload upload(UUID source, UUID session, int generation, long sequence, String track,
                                                 boolean prepared, long millis) {
        return new AnalysisPackets.Upload(source, session, generation, sequence, track, prepared,
                List.of(new AnalysisFrame(millis, .5f, .4f, .3f, .2f, .1f, 120, 0x102030, 0x405060, true, true)));
    }
}
