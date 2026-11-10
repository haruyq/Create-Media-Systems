package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class NativeMediaTest {
    private static JsonObject source() {
        JsonObject source = JsonParser.parseString("""
            {"duration":60,"video":{"kind":"video","url":"https://cdn.test/v.mp4?a=1&b=2","codec":"avc1.640028","width":1920,"height":1080,"index_range":"1000-1200","init_range":"0-999","headers":{"Referer":"https://www.bilibili.com/"}},
             "audio":[{"kind":"audio","url":"https://cdn.test/a.mp4","language":"ja","codec":"mp4a.40.2","index_range":"500-600","init_range":"0-499","headers":{"Referer":"https://www.bilibili.com/"}}]}
            """).getAsJsonObject();
        for (JsonObject track : NativeMedia.tracks(source)) {
            track.add("segment_list", JsonParser.parseString("{\"timescale\":1000,\"segments\":[{\"time\":0,\"duration\":60000,\"offset\":1201,\"size\":123456}]}"));
        }
        return source;
    }
    @Test void localDashHasOriginUrlsFullResolutionAndBothTracks() throws Exception {
        String xml = NativeMedia.dash(source());
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertEquals(2, doc.getElementsByTagNameNS("*", "AdaptationSet").getLength());
        assertEquals("https://cdn.test/v.mp4?a=1&b=2", doc.getElementsByTagNameNS("*", "BaseURL").item(0).getTextContent());
        assertTrue(xml.contains("width=\"1920\" height=\"1080\""));
        assertTrue(xml.contains("lang=\"ja\""));
        assertFalse(xml.contains("/video/provider/"));
        assertTrue(xml.contains("<SegmentList timescale=\"1000\""));
        assertTrue(xml.contains("mediaRange=\"1201-124656\""));
        assertFalse(xml.contains("<SegmentBase"), "VLC 3 cannot bootstrap SegmentBase-only SIDX media");
        assertEquals(List.of(":http-referrer=https://www.bilibili.com/"), NativeMedia.httpOptions(NativeMedia.tracks(source())));
    }
    @Test void rejectsMissingIndexesAndSharedCredentials() {
        JsonObject source = source();
        source.getAsJsonObject("video").remove("index_range");
        assertThrows(IllegalArgumentException.class, () -> NativeMedia.dash(source));
        source.getAsJsonObject("video").getAsJsonObject("headers").addProperty("Cookie", "private");
        assertThrows(IllegalArgumentException.class, () -> NativeMedia.httpOptions(NativeMedia.tracks(source)));
    }
    @Test void convertsBilibiliCaptionTimesLocally() {
        String result = NativeMedia.bilibiliVtt("{\"body\":[{\"from\":1.25,\"to\":62.5,\"content\":\"字幕\"}]}");
        assertTrue(result.contains("00:00:01.250 --> 00:01:02.500\n字幕"));
        assertTrue(result.startsWith("WEBVTT"));
    }
    @Test void materializesSidxTimelineAndAbsoluteByteRanges() {
        var buffer = java.nio.ByteBuffer.allocate(72);
        buffer.putInt(16).putInt(0x66747970).putLong(0);
        buffer.putInt(56).putInt(0x73696478).putInt(0).putInt(1).putInt(1000).putInt(50).putInt(8)
                .putShort((short)0).putShort((short)2).putInt(500).putInt(2000).putInt(0).putInt(600).putInt(3000).putInt(0);
        var list = NativeMedia.segmentList(buffer.array(),16,71);
        assertEquals(1000,list.get("timescale").getAsInt());
        var segments = list.getAsJsonArray("segments");
        assertEquals(80,segments.get(0).getAsJsonObject().get("offset").getAsInt());
        assertEquals(580,segments.get(1).getAsJsonObject().get("offset").getAsInt());
        assertEquals(2050,segments.get(1).getAsJsonObject().get("time").getAsInt());
    }

    /** VLC has no length for an HLS playlist when it opens: the API's stands in, so a tape or paused disc is sought on. */
    @Test void anHlsTrackKeepsTheApisLengthUnlessLive() throws Exception {
        String hls = "{\"version\":1,\"mode\":\"native\",\"duration\":155,\"live\":false,\"available_at\":0,"
                + "\"manifest\":{\"type\":\"hls\",\"content\":\"#EXTM3U\n\"},"
                + "\"audio\":[{\"kind\":\"audio\",\"url\":\"https://media.example/a.m3u8\",\"protocol\":\"m3u8_native\"}]}";
        JsonObject response = JsonParser.parseString(hls).getAsJsonObject();
        assertEquals(155, NativeMediaResolver.prepare(response, true).duration());
        response.addProperty("live", true);
        assertEquals(0, NativeMediaResolver.prepare(response, true).duration(), "a live stream has no length to seek in");
    }
}
