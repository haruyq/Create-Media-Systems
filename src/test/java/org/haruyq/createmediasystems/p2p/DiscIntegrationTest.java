package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.haruyq.createmediasystems.disc.DiscSource;
import org.haruyq.createmediasystems.disc.DiscStream;
import org.haruyq.createmediasystems.playback.DiscPlaybackProbe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.google.gson.JsonParser;

@EnabledIfEnvironmentVariable(named = "CMS_DISC_FIXTURE", matches = ".+")
class DiscIntegrationTest {
    @Test
    void readsSharesDecodesSeeksAndAdaptsTheSameDisc() throws Exception {
        DiscSource source = DiscSource.open("ffmpeg", "ffprobe", Path.of(System.getenv("CMS_DISC_FIXTURE")), -1);
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        UUID id = UUID.randomUUID();
        UUID hostId = P2pTest.HOST, viewerId = P2pTest.VIEWER;
        Transport[] peers = new Transport[2];
        DiscStream[] streams = new DiscStream[2];
        List<String> log = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            int side = i;
            UUID self = i == 0 ? hostId : viewerId;
            peers[i] = new Transport(Protocol.Ticket.parse(P2pTest.ticket(i == 0 ? "host" : "viewer", self, "")),
                    new Transport.Listener() {
                        @Override public void status(String message) { log.add(message); }
                        @Override public void control(UUID from, byte[] bytes) { if (streams[side] != null) streams[side].control(from, bytes); }
                        @Override public void data(UUID from, byte[] bytes) { if (streams[side] != null) streams[side].data(from, bytes); }
                    }, text -> {
                        var signal = JsonParser.parseString(text).getAsJsonObject();
                        signal.remove("to");
                        signal.addProperty("from", self.toString());
                        peers[1 - side].signal(signal.toString());
                    }, () -> {});
        }
        try (DiscStream host = streams[0] = new DiscStream(peers[0], id, source, log::add);
             DiscStream viewer = streams[1] = new DiscStream(peers[1], id, null, log::add);
             HttpClient http = HttpClient.newHttpClient()) {
            peers[1].signal(members(13_000_000));
            peers[0].signal(members(13_000_000));
            URI remote = viewer.prepare(0);
            URI local = host.prepare(0);
            byte[] first = get(http, remote.resolve("0.ts"));
            assertArrayEquals(get(http, local.resolve("0.ts")), first);
            assertEquals(source.duration(), viewer.duration());
            Path file = Path.of("build", "disc-first.ts");
            Files.write(file, first);
            String probe = probe(file);
            assertTrue(probe.contains("1920") && probe.contains("1080") && probe.contains("60/1") && probe.contains("ac3"), probe);
            peers[0].signal(members(2_000_000));
            peers[1].signal(members(2_000_000));
            Thread.sleep(100);
            Path low = Path.of("build", "disc-low.ts");
            Files.write(low, get(http, remote.resolve("10.ts")));
            String reduced = probe(low);
            assertTrue(reduced.contains("480") && reduced.contains("30/1")
                    || reduced.contains("360") && reduced.contains("24/1"), reduced);
            String vlc = System.getenv("CMS_DISC_VLC_DIR");
            if (vlc != null) {
                host.prepare(8);
                viewer.prepare(8);
                DiscPlaybackProbe.play(vlc, local, remote);
            }
            assertTrue(peers[1].received() > first.length);
            viewer.close();
            try (DiscStream late = streams[1] = new DiscStream(peers[1], id, null, log::add)) {
                URI joined = late.prepare(0);
                Files.write(low, get(http, joined.resolve("0.ts")));
                String lateProfile = probe(low);
                assertTrue(lateProfile.contains("480") || lateProfile.contains("360"),
                        "A late viewer gets a lower bitrate even when the host cached the opening at 1080p: " + lateProfile);
            }
            Files.write(Path.of("build", "disc-integration.log"), log);
        } finally {
            Files.write(Path.of("build", "disc-integration.log"), log);
            peers[0].close();
            peers[1].close();
        }
    }

    private static byte[] get(HttpClient http, URI uri) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static String probe(Path file) throws Exception {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,r_frame_rate", "-of", "json", file.toString())
                .redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), result);
        return result;
    }

    private static String members(long budget) {
        return "{\"type\":\"peers\",\"host_id\":\"" + P2pTest.HOST + "\",\"budget_bps\":" + budget + ",\"peers\":["
                + "{\"peer_id\":\"" + P2pTest.HOST + "\",\"role\":\"host\"},"
                + "{\"peer_id\":\"" + P2pTest.VIEWER + "\",\"role\":\"viewer\"}]}";
    }

}
