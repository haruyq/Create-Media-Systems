package org.haruyq.createmediasystems.lighting;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
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
        disc.set(VideoFeature.PLAYLIST.get(), List.of("https://example.com/current.mp4", "https://example.com/next.mp4"));
        player.insert(disc, null);
        LightingConsoleBlock.Entity console = (LightingConsoleBlock.Entity) helper.getLevel().getBlockEntity(consolePos);
        console.setVideoSource(playerPos);
        AnalysisCoordinator.use(console);
        UUID loadingSource = BlockIdentity.of(helper.getLevel(), playerPos);
        helper.assertTrue(!(boolean) get(session(helper.getLevel(), loadingSource), "active"),
                "lights ran on a track still loading");
        loaded(player, console);

        ServerPlayer fake = FakePlayerFactory.getMinecraft(helper.getLevel());
        UUID source = BlockIdentity.of(helper.getLevel(), playerPos);
        Object session = session(helper.getLevel(), source);
        helper.assertTrue(session != null, "analysis session was not created");
        @SuppressWarnings("unchecked")
        java.util.Set<UUID> eligible = (java.util.Set<UUID>) get(session, "eligible");
        eligible.add(fake.getUUID());
        Object lease = get(session, "lease");
        put(lease, "owner", fake.getUUID());
        put(lease, "generation", 1);
        put(lease, "sequence", -1L);
        UUID sessionId = (UUID) get(session, "session");
        AnalysisFrame prepared = frame(0, true, false);
        AnalysisPackets.Upload upload = new AnalysisPackets.Upload(source, sessionId, 1, 0, "https://example.com/next.mp4", true,
                List.of(prepared));
        AnalysisCoordinator.upload(fake, upload);
        @SuppressWarnings("unchecked")
        Map<String, AnalysisTimeline> tracks = (Map<String, AnalysisTimeline>) get(session, "tracks");
        helper.assertTrue(tracks.get("https://example.com/next.mp4").prepared(), "prepared next track was not retained");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 7,
                "https://example.com/current.mp4", false, List.of(frame(0, true, true))));
        helper.assertTrue(tracks.get("https://example.com/current.mp4") != null,
                "cached upload throttled a live frame in the same tick");
        helper.assertTrue(get(lease, "sequence").equals(0L) && get(lease, "liveSequence").equals(7L),
                "live and cached sequence domains were not independent");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 8,
                "https://example.com/current.mp4", false, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://example.com/current.mp4").end() == 100,
                "a coalesced live frame was dropped in the same tick");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 8,
                "https://example.com/current.mp4", false, List.of(frame(200, true, true))));
        helper.assertTrue(tracks.get("https://example.com/current.mp4").end() == 100,
                "a duplicate live sequence was accepted");
        for (int sequence = 9; sequence <= 27; sequence++) {
            AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, sequence,
                    "https://example.com/current.mp4", false, List.of(frame((sequence - 7) * 100L, true, true))));
        }
        helper.assertTrue(tracks.get("https://example.com/current.mp4").end() == 1_900,
                "the live rate limit did not cap the burst at 20 frames");
        put(session, "receivedAt", -100L);
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 1,
                "https://example.com/next.mp4", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://example.com/next.mp4").end() == 100,
                "a newer live sequence blocked the cached retry");
        long end = tracks.get("https://example.com/next.mp4").end();
        put(session, "receivedAt", -100L);
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 0, 1,
                "https://example.com/next.mp4", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://example.com/next.mp4").end() == end, "old generation was accepted");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, UUID.randomUUID(), 1, 1,
                "https://example.com/next.mp4", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://example.com/next.mp4").end() == end, "old session was accepted");
        AnalysisCoordinator.upload(fake, new AnalysisPackets.Upload(source, sessionId, 1, 0,
                "https://example.com/next.mp4", true, List.of(frame(100, true, true))));
        helper.assertTrue(tracks.get("https://example.com/next.mp4").end() == end, "duplicate sequence was accepted");
        AnalysisCoordinator.status(fake, new AnalysisPackets.Status(source, sessionId, true, 3_000, 600, 0, 0));
        AnalysisDelay delay = (AnalysisDelay) get(session, "delay");
        helper.assertTrue(delay.from() == 200 && delay.to() == 750 && delay.start() >= 4_000,
                "eligible viewer lag did not schedule a shared delay");
        AnalysisCoordinator.status(fake, new AnalysisPackets.Status(source, sessionId, true, 3_000, 2_001, 0, 0));
        helper.assertTrue(get(session, "delay").equals(delay), "invalid lag report changed the shared delay");
        player.control(Remotable.Action.NEXT, 0, null);
        AnalysisCoordinator.use(console);
        helper.assertTrue(!(boolean) get(session, "active"), "lights ran on the next track while it loaded");
        loaded(player, console);
        helper.assertTrue(get(session, "track").equals("https://example.com/next.mp4"), "player did not advance");
        helper.assertTrue(!get(session, "session").equals(sessionId), "track switch kept the old session");
        helper.assertTrue(tracks.get("https://example.com/next.mp4").prepared(), "track switch discarded prepared analysis");
        helper.assertTrue(get(session, "delay").equals(AnalysisDelay.INITIAL), "track switch kept the previous live delay");
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

    private static Object session(net.minecraft.server.level.ServerLevel level, UUID source) {
        try {
            Field levels = AnalysisCoordinator.class.getDeclaredField("LEVELS");
            levels.setAccessible(true);
            Object sessions = ((Map<?, ?>) levels.get(null)).get(level);
            return sessions == null ? null : ((Map<?, ?>) sessions).get(source);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void put(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
