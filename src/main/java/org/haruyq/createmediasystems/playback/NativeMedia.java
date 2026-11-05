package org.haruyq.createmediasystems.playback;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Local manifests refer directly to the origin; they never contain API media/proxy URLs. */
public final class NativeMedia {
    private NativeMedia() {}

    public static String text(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    public static URI origin(JsonObject track) {
        URI uri = URI.create(text(track, "url", ""));
        if (uri.getHost() == null || uri.getRawUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Invalid origin media URL");
        return uri;
    }

    public static List<JsonObject> tracks(JsonObject response) {
        List<JsonObject> result = new ArrayList<>();
        if (response.has("video") && !response.get("video").isJsonNull()) result.add(response.getAsJsonObject("video"));
        if (response.has("audio")) for (JsonElement audio : response.getAsJsonArray("audio")) result.add(audio.getAsJsonObject());
        return result;
    }

    /** Whether the origin header {@code name} goes along: VLC can send only User-Agent and Referer. */
    static boolean forwarded(String name) {
        return name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Referer");
    }

    public static List<String> httpOptions(List<JsonObject> tracks) {
        List<String> options = new ArrayList<>();
        httpHeaders(tracks).forEach((name,value) -> options.add(":" + (name.equals("referer") ? "http-referrer" : "http-user-agent") + "=" + value));
        return options;
    }

    /** The User-Agent and Referer the tracks need (lower-case names), checked as for {@link #httpOptions}. */
    public static Map<String,String> httpHeaders(List<JsonObject> tracks) {
        Map<String,String> common = new LinkedHashMap<>();
        for (JsonObject track : tracks) {
            if (!track.has("headers")) continue;
            for (var entry : track.getAsJsonObject("headers").entrySet()) {
                String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                String value = entry.getValue().getAsString();
                if (value.contains("\r") || value.contains("\n")) throw new IllegalArgumentException("Invalid playback header");
                if (forwarded(name)) {
                    String previous = common.putIfAbsent(name, value);
                    if (previous != null && !previous.equals(value))
                        throw new IllegalArgumentException("Origin tracks require incompatible playback headers");
                }
                if (name.equals("authorization") || name.equals("cookie"))
                    throw new IllegalArgumentException("Shared authentication must not be returned to clients");
            }
        }
        return common;
    }

    public static String dash(JsonObject response) {
        double duration = response.has("duration") && !response.get("duration").isJsonNull() ? response.get("duration").getAsDouble() : 0;
        if (!Double.isFinite(duration) || duration <= 0) throw new IllegalArgumentException("Native DASH requires a finite duration");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1S\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"><Period start=\"PT0S\" duration=\"PT").append(duration).append("S\">");
        int index = 0;
        for (JsonObject track : tracks(response)) {
            String kind = text(track, "kind", "video");
            if (kind.equals("muxed")) throw new IllegalArgumentException("Muxed tracks must be opened directly");
            String range = text(track, "index_range", "");
            String init = text(track, "init_range", "");
            if (!range.matches("[0-9]+-[0-9]+") || !init.matches("[0-9]+-[0-9]+"))
                throw new IllegalArgumentException("Missing native DASH segment index");
            xml.append("<AdaptationSet segmentAlignment=\"true\" contentType=\"").append(kind.equals("audio") ? "audio" : "video").append("\" mimeType=\"")
                    .append(escape(text(track,"mime",kind.equals("audio") ? "audio/mp4" : "video/mp4"))).append("\"");
            String language = text(track,"language","");
            if (!language.isBlank()) xml.append(" lang=\"").append(escape(language)).append("\"");
            xml.append("><Representation id=\"").append(++index).append("\" bandwidth=\"").append(text(track,"bandwidth","1000000")).append("\"");
            String codec = text(track,"codec","");
            if (!codec.isBlank()) xml.append(" codecs=\"").append(escape(codec)).append("\"");
            for (String dimension : List.of("width", "height")) {
                String value = text(track,dimension,"");
                if (value.matches("[0-9]+") && !value.equals("0")) xml.append(" ").append(dimension).append("=\"").append(value).append("\"");
            }
            JsonObject list = track.getAsJsonObject("segment_list");
            if (list == null) throw new IllegalArgumentException("Native VLC requires explicit DASH segments");
            xml.append("><BaseURL>").append(escape(origin(track).toASCIIString())).append("</BaseURL><SegmentList timescale=\"")
                    .append(list.get("timescale").getAsLong()).append("\"><Initialization range=\"").append(init).append("\"/><SegmentTimeline>");
            for (JsonElement item : list.getAsJsonArray("segments")) {
                JsonObject segment = item.getAsJsonObject();
                xml.append("<S t=\"").append(segment.get("time").getAsLong()).append("\" d=\"").append(segment.get("duration").getAsLong()).append("\"/>");
            }
            xml.append("</SegmentTimeline>");
            for (JsonElement item : list.getAsJsonArray("segments")) {
                JsonObject segment = item.getAsJsonObject();
                xml.append("<SegmentURL mediaRange=\"").append(segment.get("offset").getAsLong()).append('-')
                        .append(Math.addExact(segment.get("offset").getAsLong(), segment.get("size").getAsLong()) - 1).append("\"/>");
            }
            xml.append("</SegmentList></Representation></AdaptationSet>");
        }
        return xml.append("</Period></MPD>\n").toString();
    }

    /** Materialize SIDX references: VLC 3 cannot bootstrap media segments from SegmentBase alone. */
    public static JsonObject segmentList(byte[] data, long boxStart, long boxEnd) {
        ByteBuffer bytes = ByteBuffer.wrap(data);
        bytes.position(Math.toIntExact(boxStart));
        long size = Integer.toUnsignedLong(bytes.getInt()); bytes.getInt();
        if (size == 1) bytes.getLong();
        int version = Byte.toUnsignedInt(bytes.get()); bytes.position(bytes.position() + 3);
        bytes.getInt(); long timescale = Integer.toUnsignedLong(bytes.getInt());
        if (timescale == 0 || version > 1) throw new IllegalArgumentException("Invalid SIDX timescale or version");
        long time = version == 0 ? Integer.toUnsignedLong(bytes.getInt()) : bytes.getLong();
        long firstOffset = version == 0 ? Integer.toUnsignedLong(bytes.getInt()) : bytes.getLong();
        bytes.getShort(); int count = Short.toUnsignedInt(bytes.getShort());
        if (count == 0 || bytes.position() + (long)count * 12 > boxEnd + 1) throw new IllegalArgumentException("Truncated SIDX references");
        long offset = Math.addExact(boxEnd + 1, firstOffset);
        if (time < 0 || firstOffset < 0) throw new IllegalArgumentException("SIDX offset overflow");
        JsonArray segments = new JsonArray();
        for (int i = 0; i < count; i++) {
            long reference = Integer.toUnsignedLong(bytes.getInt());
            long duration = Integer.toUnsignedLong(bytes.getInt()); bytes.getInt();
            if ((reference & 0x80000000L) != 0 || reference == 0 || duration == 0)
                throw new IllegalArgumentException("Unsupported hierarchical or empty SIDX reference");
            JsonObject segment = new JsonObject();
            segment.addProperty("time", time); segment.addProperty("duration", duration);
            segment.addProperty("offset", offset); segment.addProperty("size", reference); segments.add(segment);
            offset = Math.addExact(offset, reference); time = Math.addExact(time, duration);
        }
        JsonObject result = new JsonObject(); result.addProperty("timescale", timescale); result.add("segments", segments);
        return result;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Bilibili's caption JSON as WebVTT, converted here rather than fetched through the API. */
    public static String bilibiliVtt(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        int count = 0;
        for (JsonElement value : root.getAsJsonArray("body")) {
            var cue = value.getAsJsonObject();
            double start = cue.get("from").getAsDouble(), end = cue.get("to").getAsDouble();
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start) continue;
            String text = cue.get("content").getAsString().replace("\r", "").replace("-->", "→");
            out.append(++count).append('\n').append(vttTime(start)).append(" --> ").append(vttTime(end)).append('\n').append(text).append("\n\n");
        }
        return out.toString();
    }
    private static String vttTime(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
