package org.haruyq.createmediasystems.lighting;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AnalysisGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void preparedNextTrackSurvivesTrackSwitch(GameTestHelper helper) {
        BlockPos playerPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos consolePos = helper.absolutePos(new BlockPos(3, 2, 2));
        helper.getLevel().setBlockAndUpdate(playerPos, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        helper.getLevel().setBlockAndUpdate(consolePos, LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState());
        VideoPlayerBlock.Entity player = (VideoPlayerBlock.Entity) helper.getLevel().getBlockEntity(playerPos);
        ItemStack disc = new ItemStack(VideoFeature.BLU_RAY.get());
        disc.set(VideoFeature.PLAYLIST.get(), List.of("https://youtu.be/current", "https://youtu.be/next"));
        player.insert(disc, null);
        LightingConsoleBlock.Entity console = (LightingConsoleBlock.Entity) helper.getLevel().getBlockEntity(consolePos);
        console.setVideoSource(playerPos);
        AnalysisCoordinator.use(console);
        UUID loadingSource = BlockIdentity.of(helper.getLevel(), playerPos);
        helper.assertTrue(!AnalysisCoordinator.session(helper.getLevel(), loadingSource).active,
                "lights ran on a track still loading");
        loaded(player, console);

        ServerPlayer fake = FakePlayerFactory.getMinecraft(helper.getLevel());
        UUID source = BlockIdentity.of(helper.getLevel(), playerPos);
        AnalysisCoordinator.Session session = AnalysisCoordinator.session(helper.getLevel(), source);
        helper.assertTrue(session != null, "analysis session was not created");
        session.eligible.add(fake.getUUID());
        AnalysisLease lease = session.lease;
        lease.owner = fake.getUUID();
        lease.generation = 1;
        lease.sequence = -1L;
        UUID sessionId = session.id;
        AnalysisFrame prepared = frame(0, true, false);
        AnalysisPackets.Upload upload = new AnalysisPackets.Upload(source, sessionId, 1, 0, "https://youtu.be/next", true,
                List.of(prepared));
        AnalysisCoordinator.upload(fake, upload);
        var tracks = session.tracks;
        helper.assertTrue(tracks.get("https://youtu.be/next").prepared(), "prepared next track was not retained");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 7,
                "https://youtu.be/current", false, List.of(frame(0, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/current") != null,
                "cached upload throttled a live frame in the same tick");
        helper.assertTrue(lease.sequence == 0L && lease.liveSequence == 7L,
                "live and cached sequence domains were not independent");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 8,
                "https://youtu.be/current", false, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/current").end() == 100,
                "a coalesced live frame was dropped in the same tick");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 8,
                "https://youtu.be/current", false, List.of(frame(200, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/current").end() == 100,
                "a duplicate live sequence was accepted");
        for (int sequence = 9; sequence <= 27; sequence++) {
            AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, sequence,
                    "https://youtu.be/current", false, List.of(frame((sequence - 7) * 100L, true, true))));
        }
        helper.assertTrue(tracks.get("https://youtu.be/current").end() == 1_900,
                "the live rate limit did not cap the burst at 20 frames");
        session.receivedAt = -100L;
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 1,
                "https://youtu.be/next", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/next").end() == 100,
                "a newer live sequence blocked the cached retry");
        long end = tracks.get("https://youtu.be/next").end();
        session.receivedAt = -100L;
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 0, 1,
                "https://youtu.be/next", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/next").end() == end, "old generation was accepted");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, UUID.randomUUID(), 1, 1,
                "https://youtu.be/next", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/next").end() == end, "old session was accepted");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 0,
                "https://youtu.be/next", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://youtu.be/next").end() == end, "duplicate sequence was accepted");
        AnalysisCoordinator.status(fake, new AnalysisPackets.Status(source, sessionId, true, 3_000, 600, 0, 0));
        AnalysisDelay delay = session.delay;
        helper.assertTrue(delay.from() == 200 && delay.to() == 750 && delay.start() >= 4_000,
                "eligible viewer lag did not schedule a shared delay");
        AnalysisCoordinator.status(fake, new AnalysisPackets.Status(source, sessionId, true, 3_000, 2_001, 0, 0));
        helper.assertTrue(session.delay.equals(delay), "invalid lag report changed the shared delay");
        player.control(Remotable.Action.NEXT, 0, null);
        AnalysisCoordinator.use(console);
        helper.assertTrue(!session.active, "lights ran on the next track while it loaded");
        loaded(player, console);
        helper.assertTrue(session.track.equals("https://youtu.be/next"), "player did not advance");
        helper.assertTrue(!session.id.equals(sessionId), "track switch kept the old session");
        helper.assertTrue(tracks.get("https://youtu.be/next").prepared(), "track switch discarded prepared analysis");
        helper.assertTrue(session.delay.equals(AnalysisDelay.INITIAL), "track switch kept the previous live delay");
        helper.succeed();
    }

    /** Nobody listens here: its track plays once it has waited as long as a track waits for its listeners. */
    private static void loaded(VideoPlayerBlock.Entity player, LightingConsoleBlock.Entity console) {
        Streams.tick(player, player.transport().clock(), player.getLevel().getGameTime() + Streams.MAX_LOAD_TICKS);
        AnalysisCoordinator.use(console);
    }

    private static AnalysisFrame frame(long millis, boolean audio, boolean video) {
        return new AnalysisFrame(millis, .5f, .4f, .3f, .2f, .1f, 120, 0x102030, 0x405060, audio, video);
    }
}
