package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.stream.Streams;

import org.junit.jupiter.api.Test;

import net.minecraft.client.sounds.AudioStream;

class NativeAudioTest {
    private static final URI SOURCE = URI.create("https://media.invalid/synthetic.mkv");
    private static final NativeAudio.Status VOD = new NativeAudio.Status(true, false, true, false, false);

    static final class Tap implements AudioStream, DeviceClock.Timed {
        final AtomicInteger closes = new AtomicInteger();

        @Override public AudioFormat getFormat() { return new AudioFormat(48_000, 16, 1, true, false); }
        @Override public ByteBuffer read(int size) { return ByteBuffer.allocateDirect(96); }
        @Override public void close() { closes.incrementAndGet(); }
        @Override public long attach(int source, long now) { return source + now; }
    }

    static final class Player implements NativeAudio.Backend {
        final Tap tap = new Tap();
        AudioStream tapped = tap;
        final AtomicInteger releases = new AtomicInteger();
        final List<String> actions = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile NativeAudio.Status state = VOD;
        long sought = -1;
        Streams.Channel channel;
        boolean failStart;
        boolean failTap;

        @Override public AudioStream tap(Streams.Channel channel) throws IOException {
            actions.add("tap"); this.channel = channel;
            if (failTap) throw new IOException("no tap");
            return tapped;
        }
        @Override public void start(URI uri) {
            actions.add("start");
            if (failStart) throw new IllegalArgumentException("secret signed URL");
        }
        @Override public NativeAudio.Status status() { return state; }
        @Override public void seek(long millis) { actions.add("seek"); sought = millis; }
        @Override public void resume() { actions.add("resume"); }
        @Override public void release() { releases.incrementAndGet(); }
    }

    @Test
    void joinsVodAfterResolutionAndPreservesSpeakerChannel() throws Exception {
        Player player = new Player();
        try (AudioStream stream = own(player, 5, System.nanoTime() - 2_000_000_000L,
                () -> false, Streams.Channel.RIGHT)) {
            assertTrue(player.sought >= 7000 && player.sought < 7300);
            assertEquals(List.of("tap", "start", "seek", "resume"), player.actions);
            assertEquals(Streams.Channel.RIGHT, player.channel);
            assertEquals(48_000, stream.getFormat().getSampleRate());
        }
        assertEquals(1, player.releases.get());
    }

    @Test
    void speakerLinkedMidwayTapsThePlayerOfItsPlayback() throws Exception {
        URI source = URI.create("https://media.invalid/shared.mkv");
        List<Player> players = new ArrayList<>();
        Supplier<NativeAudio.Backend> open = () -> {
            Player player = new Player();
            players.add(player);
            return player;
        };
        long asked = System.nanoTime();
        AudioStream jukebox = NativeAudio.open(open, source, null, null, 0, asked, () -> false, Streams.Channel.MIX, null, s -> {});
        // Linked 10 s later, when the server says the track is 10 s in.
        AudioStream speaker = NativeAudio.open(open, source, null, null, 10, asked + 10_000_000_000L,
                () -> false, Streams.Channel.RIGHT, null, s -> {});
        assertEquals(1, players.size());
        assertEquals(List.of("tap", "start", "resume", "tap"), players.get(0).actions);
        assertEquals(Streams.Channel.RIGHT, players.get(0).channel);
        // The track started over: a playback of its own.
        AudioStream restarted = NativeAudio.open(open, source, null, null, 0, asked + 30_000_000_000L,
                () -> false, Streams.Channel.MIX, null, s -> {});
        assertEquals(2, players.size());

        jukebox.close();
        assertEquals(0, players.get(0).releases.get(), "the speaker still plays it");
        speaker.close();
        assertEquals(1, players.get(0).releases.get());
        restarted.close();
        assertEquals(1, players.get(1).releases.get());
    }

    @Test
    void stereoPairStartsOnlyOnceBothSpeakersTapThePlayer() throws Exception {
        URI source = URI.create("https://media.invalid/kept.m4a");
        Player player = new Player();
        player.state = NativeAudio.Status.WAITING;
        long asked = System.nanoTime();
        var left = java.util.concurrent.CompletableFuture.supplyAsync(() -> open(() -> player, source, asked, Streams.Channel.LEFT));
        await(() -> player.actions.contains("start"));
        var right = java.util.concurrent.CompletableFuture.supplyAsync(() -> open(() -> fail("shares"), source, asked, Streams.Channel.RIGHT));
        // toArray holds the list's lock; streaming it doesn't, and the speakers add to it meanwhile.
        await(() -> java.util.Arrays.stream(player.actions.toArray()).filter("tap"::equals).count() == 2);
        player.state = VOD; // a kept file: ready at once
        left.get(5, java.util.concurrent.TimeUnit.SECONDS).close();
        right.get(5, java.util.concurrent.TimeUnit.SECONDS).close();
        assertEquals(List.of("tap", "start", "tap", "resume"), player.actions, "the right speaker taps before it plays");
        assertEquals(1, player.releases.get());
    }

    private static AudioStream open(Supplier<NativeAudio.Backend> players, URI source, long asked, Streams.Channel channel) {
        try {
            return NativeAudio.open(players, source, "jukebox", null, 0, asked, () -> false, channel, null, s -> {});
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    /** A stream of a playback of its own (a source nobody else plays), on {@code player}. */
    private static AudioStream own(Player player, double startSeconds, long asked, java.util.function.BooleanSupplier cancelled,
                                   Streams.Channel channel, java.util.concurrent.CompletableFuture<NativeAudio.Go> go)
            throws IOException {
        return NativeAudio.open(() -> player, URI.create("https://media.invalid/" + java.util.UUID.randomUUID() + ".mkv"),
                null, null, startSeconds, asked, cancelled, channel, go, s -> {});
    }

    private static AudioStream own(Player player, double startSeconds, long asked, java.util.function.BooleanSupplier cancelled,
                                   Streams.Channel channel) throws IOException {
        return own(player, startSeconds, asked, cancelled, channel, null);
    }

    /** What the sound engine reads: a stream that has opened on {@code player}, which plays a video on demand. */
    private static NativeAudio.Owned playing(Player player) throws IOException {
        return (NativeAudio.Owned) own(player, 0, System.nanoTime(), () -> false, Streams.Channel.MIX);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(5);
        }
    }

    @Test
    void speakerOfTheSameSourceJoinsHoweverFarTheServerLags() throws Exception {
        URI source = URI.create("https://media.invalid/lagging.mkv");
        List<Player> players = new ArrayList<>();
        Supplier<NativeAudio.Backend> open = () -> {
            Player player = new Player();
            players.add(player);
            return player;
        };
        Object jukebox = "jukebox at 0 64 0";
        long asked = System.nanoTime();
        AudioStream left = NativeAudio.open(open, source, jukebox, null, 0, asked, () -> false, Streams.Channel.LEFT, null, s -> {});
        // 60 s on, a server at 18 TPS says only 54 s have played.
        AudioStream right = NativeAudio.open(open, source, jukebox, null, 54, asked + 60_000_000_000L,
                () -> false, Streams.Channel.RIGHT, null, s -> {});
        // Another source playing the same disc is a playback of its own.
        AudioStream other = NativeAudio.open(open, source, "jukebox at 9 64 9", null, 54, asked + 60_000_000_000L,
                () -> false, Streams.Channel.MIX, null, s -> {});
        assertEquals(2, players.size());
        assertEquals(List.of("tap", "start", "resume", "tap"), players.get(0).actions);
        left.close();
        right.close();
        other.close();
    }

    @Test
    void freshSourceStartsAtItsBeginning() throws Exception {
        Player player = new Player();
        try (AudioStream ignored = own(player, 0, System.nanoTime() - 2_000_000_000L,
                () -> false, null)) {
            assertEquals(-1, player.sought);
        }
    }

    @Test
    void loadedPlayerWaitsPausedForTheServerToSayPlay() throws Exception {
        Player player = new Player();
        var go = new java.util.concurrent.CompletableFuture<NativeAudio.Go>();
        var opened = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return own(player, 0, System.nanoTime(), () -> false, Streams.Channel.MIX, go);
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
        await(() -> player.actions.contains("start"));
        Thread.sleep(100);
        assertFalse(player.actions.contains("resume"), "the other listeners are still loading it");
        go.complete(new NativeAudio.Go(0, System.nanoTime()));
        opened.get(5, java.util.concurrent.TimeUnit.SECONDS).close();
        assertEquals(List.of("tap", "start", "resume"), player.actions, "from the top, with everyone");
    }

    @Test
    void slowerClientStartsWhereTheServerSays() throws Exception {
        Player player = new Player();
        // The others had it loaded 3 s before this one did.
        var go = java.util.concurrent.CompletableFuture.completedFuture(new NativeAudio.Go(3, System.nanoTime()));
        try (AudioStream ignored = own(player, 0, System.nanoTime(), () -> false, Streams.Channel.MIX, go)) {
            assertTrue(player.sought >= 3000 && player.sought < 3100);
        }
        Player inStep = new Player();
        var near = java.util.concurrent.CompletableFuture.completedFuture(new NativeAudio.Go(0.1, System.nanoTime()));
        try (AudioStream ignored = own(inStep, 0, System.nanoTime(), () -> false, Streams.Channel.MIX, near)) {
            assertEquals(-1, inStep.sought, "close enough: seeking would only stall it");
        }
    }

    @Test
    void liveSourceNeverSeeks() throws Exception {
        Player player = new Player();
        player.state = new NativeAudio.Status(true, true, true, false, false);
        try (AudioStream ignored = own(player, 50, System.nanoTime(), () -> false, Streams.Channel.MIX)) {
            assertEquals(-1, player.sought);
        }
    }

    @Test
    void unseekableSourceNeverSeeks() throws Exception {
        Player player = new Player();
        player.state = new NativeAudio.Status(true, false, false, false, false);
        try (AudioStream ignored = own(player, 50, System.nanoTime(), () -> false, Streams.Channel.MIX)) {
            assertEquals(-1, player.sought);
        }
    }

    @Test
    void cancellationBeforeOpeningReleasesWithoutStarting() {
        Player player = new Player();
        assertThrows(IOException.class, () -> own(player, 0, System.nanoTime(), () -> true, Streams.Channel.MIX));
        assertTrue(player.actions.isEmpty());
        assertEquals(1, player.releases.get());
    }

    @Test
    void cancellationDuringLoadingClosesTapAndPlayer() {
        Player player = new Player();
        player.state = NativeAudio.Status.WAITING;
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> own(player, 0, System.nanoTime(),
                () -> checks.incrementAndGet() > 1, Streams.Channel.MIX));
        assertEquals(List.of("tap", "start"), player.actions);
        assertEquals(1, player.tap.closes.get());
        assertEquals(1, player.releases.get());
    }

    @Test
    void brokenSourceFailsAndReleasesWithoutHttpFallback() {
        Player player = new Player();
        player.state = NativeAudio.Status.BROKEN;
        IOException error = assertThrows(IOException.class, () -> own(player, 0,
                System.nanoTime(), () -> false, Streams.Channel.MIX));
        assertEquals("The native player could not open this source", error.getMessage());
        assertEquals(List.of("tap", "start"), player.actions);
        assertEquals(1, player.tap.closes.get());
        assertEquals(1, player.releases.get());
    }

    @Test
    void tapFailureStillReleasesOwner() {
        Player player = new Player(); player.failTap = true;
        assertThrows(IOException.class, () -> own(player, 0, System.nanoTime(),
                () -> false, null));
        assertEquals(1, player.releases.get());
        assertEquals(0, player.tap.closes.get());
    }

    @Test
    void nativeErrorDoesNotExposeSignedUrls() {
        Player player = new Player(); player.failStart = true;
        IOException error = assertThrows(IOException.class, () -> own(player, 0,
                System.nanoTime(), () -> false, Streams.Channel.MIX));
        assertEquals("Native playback failed to initialize", error.getMessage());
        assertEquals(1, player.tap.closes.get());
        assertEquals(1, player.releases.get());
    }

    @Test
    void concurrentCloseReleasesNativeOwnerExactlyOnce() throws Exception {
        Player player = new Player();
        NativeAudio.Owned owned = playing(player);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = Thread.ofPlatform().start(() -> {
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                owned.close();
            });
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) thread.join(2000);
        assertEquals(1, player.tap.closes.get());
        assertEquals(1, player.releases.get());
        assertEquals(0, owned.read(9600).remaining());
    }

    @Test
    void wrapperRetainsOpenAlScheduling() throws Exception {
        Player player = new Player();
        NativeAudio.Owned owned = playing(player);
        assertEquals(1200, owned.attach(200, 1000));
        assertFalse(owned.advancesWhilePaused());
        owned.close();

        Player videoPlayer = new Player();
        videoPlayer.tapped = new VlcAudio().open(Streams.Channel.LEFT);
        NativeAudio.Owned video = playing(videoPlayer);
        assertTrue(video.advancesWhilePaused());
        video.close();
    }

    @Test
    void onlyTheEndOrABreakCountsAsDone() throws Exception {
        // Closed by the sound engine (its OpenAL source ran dry): not done, so the stream starts again.
        Player dropped = new Player();
        NativeAudio.Owned owned = playing(dropped);
        owned.read(9600);
        owned.close();
        assertFalse(owned.done());

        Player broken = new Player();
        NativeAudio.Owned interrupted = playing(broken);
        broken.state = NativeAudio.Status.BROKEN;
        assertThrows(IOException.class, () -> interrupted.read(9600));
        assertTrue(interrupted.done());

        Player ending = new Player();
        NativeAudio.Owned finished = playing(ending);
        ending.state = new NativeAudio.Status(true, false, true, true, false);
        finished.read(9600);
        Thread.sleep(600); // past the tail the last chunks get to play
        assertEquals(0, finished.read(9600).remaining());
        assertTrue(finished.done());
    }

    @Test
    void failureAfterOpeningReleasesOwner() throws Exception {
        Player player = new Player();
        NativeAudio.Owned owned = playing(player);
        player.state = NativeAudio.Status.BROKEN;
        assertThrows(IOException.class, () -> owned.read(9600));
        owned.close();
        assertEquals(1, player.releases.get());
    }

    @Test
    void naturalEndDrainsTailThenSignalsEof() throws Exception {
        Player player = new Player();
        NativeAudio.Owned owned = playing(player);
        player.state = new NativeAudio.Status(false, false, false, true, false);
        assertEquals(96, owned.read(9600).remaining());
        Thread.sleep(550);
        assertEquals(0, owned.read(9600).remaining());
        assertEquals(1, player.releases.get());
    }

    @Test
    void seekableHttpVodIsNotClassifiedAsLive() {
        assertFalse(NativeAudio.classifyLive(false, true, 10_000, true));
    }

    @Test
    void explicitBroadcastOverridesFiniteNativeDuration() {
        assertTrue(NativeAudio.classifyLive(true, true, 10_000, true));
    }

    @Test
    void incompleteOrUnseekableMediaIsNotTreatedAsSeekableVod() {
        assertTrue(NativeAudio.classifyLive(false, false, 10_000, true));
        assertTrue(NativeAudio.classifyLive(false, true, 0, true));
        assertTrue(NativeAudio.classifyLive(false, true, 10_000, false));
    }
}
