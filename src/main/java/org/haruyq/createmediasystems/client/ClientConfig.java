package org.haruyq.createmediasystems.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** This computer's own settings. */
public final class ClientConfig {
    public static final String FILE_NAME = "cms-client.toml";

    /** Features add their own settings to it while they register (see ClientFeature), before the file is built. */
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue AUDIO_CACHE_DAYS = BUILDER
            .comment("Days a record's audio (YouTube and the like) is kept on this computer after it last played, so it plays again without downloading. 0 keeps none.")
            .defineInRange("audioCacheDays", 7, 0, 365);
    public static final ModConfigSpec.IntValue AUDIO_CACHE_MEGABYTES = BUILDER
            .comment("Most disk space the kept audio may take, in MB; the longest unplayed goes first. 0 keeps none.")
            .defineInRange("audioCacheMegabytes", 512, 0, 1 << 20);
    public static final ModConfigSpec.IntValue VIDEO_CACHE_MEGABYTES = BUILDER
            .comment("Most disk space displays' kept videos may take, in MB; the longest unplayed goes first. 0 keeps none, and fetches no video ahead.")
            .defineInRange("videoCacheMegabytes", 5120, 0, 1 << 22);
    public static final ModConfigSpec.IntValue MAX_AUDIBLE_DISC_PROJECTIONS = BUILDER
            .comment("Nearest portable music sources (launched CDs, PartyBoxes, megaphones) played at once on this computer, all kinds together. Every source's server playback clock keeps running, including outside this voice budget. Returning sources seek to their own current position.")
            .defineInRange("maxAudibleDiscProjections", 12, 1, 64);
    private static final ModConfigSpec.IntValue MAX_VIDEO_HEIGHT = BUILDER
            .comment("Tallest video (in pixels) displays pick when a page offers several, so this computer can decode it in time (4K AV1 can't be, in software, on most). 0 picks the best there is.")
            .defineInRange("maxVideoHeight", 1080, 0, 4320);
    public static final ModConfigSpec.BooleanValue YOUTUBE_COOKIES = BUILDER
            .comment("Take YouTube cookies from the VRCVideoCacher Cookies Exporter browser extension (127.0.0.1:9696), so yt-dlp plays displays' YouTube videos signed in. A throwaway account is safer: YouTube may restrict accounts used this way.")
            .define("youtubeCookies", true);
    public static final ModConfigSpec.BooleanValue SEND_DIAGNOSTICS = BUILDER
            .comment("Send how long each display's video took to start here to the server's API, to find why it starts slowly for some: the times, this config, the Create: Media Systems and yt-dlp versions, whether YouTube cookies are set (never the cookies) and the end of logs/latest.log without chat.")
            .define("sendDiagnostics", true);
    public static final ModConfigSpec.ConfigValue<String> DISC_FFMPEG_PATH = BUILDER
            .comment("FFmpeg for disc sharing. The default 'ffmpeg' or an empty value downloads a managed Windows x64 build on first use. A custom executable path overrides it; needs dvdvideo, H.264 and AC-3 support.")
            .define("discFfmpegPath", "ffmpeg");
    public static final ModConfigSpec.ConfigValue<String> DISC_FFPROBE_PATH = BUILDER
            .comment("FFprobe for disc titles. The default 'ffprobe' or an empty value uses the managed download. For a custom FFmpeg, set the FFprobe path from the same installation.")
            .define("discFfprobePath", "ffprobe");
    public static final ModConfigSpec.ConfigValue<String> DISC_DRIVE = BUILDER
            .comment("The real drive a disc drive emulator shows, like 'E:/'; a folder holding BDMV, VIDEO_TS or a CD image (.bin) works too. Empty takes the first optical drive of this computer with a disc in it.")
            .define("discDrive", "");

    private ClientConfig() {
    }

    /** After every feature has added its settings. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, BUILDER.build(), FILE_NAME);
    }

    /** The tallest picture to play: {@code disc}'s, or this computer's limit (maxVideoHeight) if lower. */
    public static int maxHeight(int disc) {
        int own = MAX_VIDEO_HEIGHT.get();
        return own <= 0 ? disc : disc <= 0 ? own : Math.min(own, disc);
    }
}
