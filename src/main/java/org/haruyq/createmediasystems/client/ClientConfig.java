package org.haruyq.createmediasystems.client;

import java.nio.file.Path;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

/** This computer's own settings, and where CMS keeps its files here. */
public final class ClientConfig {
    public static final String FILE_NAME = "cms-client.toml";
    /** CMS's folder in the game directory: its tools, VLC and cookies. */
    public static final Path DIR = FMLPaths.GAMEDIR.get().resolve(CreateMediaSystems.MOD_ID);

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue AUDIO_CACHE_DAYS = BUILDER
            .comment("Days a record's audio (YouTube and the like) is kept on this computer after it last played, so it plays again without downloading. 0 keeps none.")
            .defineInRange("audioCacheDays", 7, 0, 365);
    public static final ModConfigSpec.IntValue AUDIO_CACHE_MEGABYTES = BUILDER
            .comment("Most disk space the kept audio may take, in MB; the longest unplayed goes first. 0 keeps none.")
            .defineInRange("audioCacheMegabytes", 512, 0, 1 << 20);
    public static final ModConfigSpec.IntValue VIDEO_CACHE_MEGABYTES = BUILDER
            .comment("Most disk space displays' kept videos may take, in MB; the longest unplayed goes first. 0 keeps none, and fetches no video ahead.")
            .defineInRange("videoCacheMegabytes", 5120, 0, 1 << 22);
    private static final ModConfigSpec.IntValue MAX_VIDEO_HEIGHT = BUILDER
            .comment("Tallest video (in pixels) displays pick when a page offers several, so this computer can decode it in time (4K AV1 can't be, in software, on most). 0 picks the best there is.")
            .defineInRange("maxVideoHeight", 1080, 0, 4320);
    public static final ModConfigSpec.BooleanValue YOUTUBE_COOKIES = BUILDER
            .comment("Take YouTube cookies from the VRCVideoCacher Cookies Exporter browser extension (127.0.0.1:9696), so yt-dlp plays displays' YouTube videos signed in. A throwaway account is safer: YouTube may restrict accounts used this way.")
            .define("youtubeCookies", true);
    public static final ModConfigSpec.BooleanValue SEND_DIAGNOSTICS = BUILDER
            .comment("Send how long each display's video took to start here to the server's API, to find why it starts slowly for some: the times, this config, the Create: Media Systems and yt-dlp versions, whether YouTube cookies are set (never the cookies) and the end of logs/latest.log without chat.")
            .define("sendDiagnostics", true);
    public static final ModConfigSpec.ConfigValue<String> SPEEDTEST_PATH = BUILDER
            .comment("Ookla's Speedtest CLI (speedtest.net/apps/cli), a path or a name on PATH, which /cms_p2p speedtest runs to size direct P2P sessions. If the default isn't on PATH, an installed extension such as CMS Disc Extension may download it. Either way, accept its license yourself by running it once in a terminal: CMS never accepts it for you.")
            .define("speedtestPath", org.haruyq.createmediasystems.p2p.Speedtest.DEFAULT_PATH);
    public static final ModConfigSpec.ConfigValue<String> DISC_FFMPEG_PATH = BUILDER
            .comment("FFmpeg for disc sharing. The default 'ffmpeg' or an empty value downloads a managed Windows x64 build on first use. A custom executable path overrides it; needs dvdvideo, H.264 and AC-3 support.")
            .define("discFfmpegPath", "ffmpeg");
    public static final ModConfigSpec.ConfigValue<String> DISC_FFPROBE_PATH = BUILDER
            .comment("FFprobe for disc titles. The default 'ffprobe' or an empty value uses the managed download. For a custom FFmpeg, set the FFprobe path from the same installation.")
            .define("discFfprobePath", "ffprobe");
    private static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, FILE_NAME);
    }

    /** The tallest picture to play: {@code disc}'s, or this computer's limit (maxVideoHeight) if lower. */
    public static int maxHeight(int disc) {
        int own = MAX_VIDEO_HEIGHT.get();
        return own <= 0 ? disc : disc <= 0 ? own : Math.min(own, disc);
    }
}
