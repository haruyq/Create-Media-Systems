package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class YtDlpTest {
    @Test
    void hlsPlaysItsMasterPlaylistWithTheOriginHeaders() throws Exception {
        var info = JsonParser.parseString("""
                {"is_live": true, "requested_formats": [
                  {"format_id": "v", "protocol": "m3u8_native", "vcodec": "avc1", "ext": "mp4",
                   "url": "https://cdn.test/v.m3u8", "manifest_url": "https://cdn.test/master.m3u8",
                   "http_headers": {"User-Agent": "UA", "Cookie": "secret=1", "Accept": "*/*"}},
                  {"format_id": "a", "protocol": "m3u8_native", "vcodec": "none", "ext": "mp4",
                   "url": "https://cdn.test/a.m3u8"}]}
                """).getAsJsonObject();
        var prepared = YtDlp.prepare(info, 1080);
        assertEquals(URI.create("https://cdn.test/master.m3u8"), prepared.uri());
        assertTrue(prepared.live());
        assertEquals(0, prepared.duration(), "a live stream has no length");
        var options = prepared.options();
        assertTrue(options.contains(":adaptive-maxheight=1080"));
        assertTrue(options.contains(":http-user-agent=UA"));
        assertFalse(options.stream().anyMatch(option -> option.contains("secret")), "cookies never reach VLC");
    }

    @Test
    void separateNonMp4TracksPlayTogether() throws Exception {
        var info = JsonParser.parseString("""
                {"requested_formats": [
                  {"protocol": "https", "vcodec": "vp9", "ext": "webm", "url": "https://cdn.test/v.webm"},
                  {"protocol": "https", "vcodec": "none", "ext": "webm", "url": "https://cdn.test/a.webm"}]}
                """).getAsJsonObject();
        var prepared = YtDlp.prepare(info, 0);
        assertEquals(URI.create("https://cdn.test/v.webm"), prepared.uri());
        assertFalse(prepared.live());
        assertTrue(prepared.options().contains(":input-slave=https://cdn.test/a.webm"));
    }

    @Test
    void oneFileOpensAsItIs() throws Exception {
        var prepared = YtDlp.prepare(JsonParser.parseString("""
                {"protocol": "https", "vcodec": "avc1", "acodec": "mp4a", "ext": "mp4", "url": "https://cdn.test/f.mp4",
                 "duration": 177, "is_live": false}
                """).getAsJsonObject(), 0);
        assertEquals(URI.create("https://cdn.test/f.mp4"), prepared.uri());
        assertEquals(177, prepared.duration(), "known on demand: not taken for live while VLC has no length yet");
    }

    @Test
    void aRecordsAudioOpensItsOwnPlaylistNotTheMaster() {
        var prepared = YtDlp.audio(JsonParser.parseString("""
                {"format_id": "234", "protocol": "m3u8_native", "vcodec": "none", "ext": "mp4", "duration": 228,
                 "url": "https://cdn.test/a.m3u8", "manifest_url": "https://cdn.test/master.m3u8",
                 "http_headers": {"User-Agent": "UA", "Cookie": "secret=1"}}
                """).getAsJsonObject());
        assertEquals(URI.create("https://cdn.test/a.m3u8"), prepared.uri(), "the master would fetch the video too");
        assertEquals(228, prepared.duration());
        assertFalse(prepared.live());
        assertTrue(prepared.options().contains(":http-user-agent=UA"));
        assertFalse(prepared.options().stream().anyMatch(option -> option.contains("secret")));
    }

    @Test
    void reasonIsYtDlpsLastError() {
        assertEquals("[youtube] x: Sign in to confirm you're not a bot",
                YtDlp.reason("WARNING: a\nERROR: [youtube] y: first\nERROR: [youtube] x: Sign in to confirm you're not a bot\n"));
        assertEquals("yt-dlp could not resolve this page", YtDlp.reason("Traceback"));
    }

    @Test
    void cookiesKeepYoutubeAlone() {
        String kept = CookieReceiver.youtubeOnly("""
                # Netscape HTTP Cookie File
                .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc
                .evil.test\tTRUE\t/\tTRUE\t0\tSID\tx
                www.youtube.com\tFALSE\t/\tFALSE\t0\tPREF\tf
                """);
        assertNotNull(kept);
        assertTrue(kept.contains("SID\tabc") && kept.contains("PREF\tf"));
        assertFalse(kept.contains("evil"));
        assertNull(CookieReceiver.youtubeOnly(".evil.test\tTRUE\t/\tTRUE\t0\tSID\tx\n"), "nothing for YouTube");
        assertNull(CookieReceiver.youtubeOnly("<html>not cookies</html>"));
    }

    @Test
    void readsBothChecksumFormats() throws Exception {
        String sums = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3  yt-dlp\n"
                + "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d  yt-dlp.exe\n";
        assertEquals("f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d", Tools.sumFor(sums, "yt-dlp.exe"));
        assertEquals("e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3", Tools.sumFor(sums, "yt-dlp"));
        String deno = "Algorithm : SHA256\nHash      : CBA84B26E0DF5AE7F74FC66B86A95B866872A86292EBD84FA1E959927EDD93BF\n";
        assertEquals("CBA84B26E0DF5AE7F74FC66B86A95B866872A86292EBD84FA1E959927EDD93BF", Tools.sumFor(deno, null));
    }

    @Test
    void playlistLinksAreReadAndFilledUpToTheDiscsCapacity() {
        String list = "https://www.youtube.com/playlist?list=PL123";
        assertTrue(YtDlp.isPlaylist(list));
        assertTrue(YtDlp.isPlaylist("https://music.youtube.com/playlist?foo=1&list=PL1"));
        assertTrue(YtDlp.isPlaylist("https://www.youtube.com/watch?v=iT318d30d2c&list=PLUQKJP1sVuNPQxernpjwSwQV8KCnbOf5N"));
        assertTrue(YtDlp.isPlaylist("https://www.youtube.com/watch?v=r48R3iD3lqk&list=RDGMEMYH9CUrFO7CfLJpaD7UR85w"
                + "&start_radio=1&rv=dOP7G5J755s"), "a mix");
        assertTrue(YtDlp.isPlaylist("https://youtu.be/a?list=PL1"));
        assertFalse(YtDlp.isPlaylist("https://www.youtube.com/watch?v=a"));
        assertFalse(YtDlp.isPlaylist("https://www.youtube.com/watch?v=a&playlist=PL1"));
        assertFalse(YtDlp.isPlaylist("https://www.youtube.com/watch?v=a&list="));
        assertFalse(YtDlp.isPlaylist("https://evil.test/playlist?list=PL1"));
        assertFalse(YtDlp.isPlaylist("not a url"));
        var videos = java.util.Map.of(list, java.util.List.of("v1", "v2", "v3", "v4"));
        assertEquals(java.util.List.of("a", "v1", "v2", "b"),
                YtDlp.fill(java.util.List.of(" a ", list, "", "b"), videos, 4), "room is kept for the other rows");
        assertEquals(java.util.List.of("a", "b"), YtDlp.fill(java.util.List.of("a", list, "b"), videos, 2));
    }

    @Test
    void aPlaylistsVideosTitlesAreKept() {
        String a = "https://www.youtube.com/watch?v=titled", b = "https://www.youtube.com/watch?v=untitled";
        assertEquals(java.util.List.of(a, b), YtDlp.videos(a + "\tFirst video\n" + b + "\tNA\n\n"));
        assertEquals("First video", org.haruyq.createmediasystems.stream.Streams.title(a).getString());
        // No API here: one without a title is asked about, and shows as its link meanwhile.
        assertEquals(null, org.haruyq.createmediasystems.stream.Streams.title(b));
    }
}
