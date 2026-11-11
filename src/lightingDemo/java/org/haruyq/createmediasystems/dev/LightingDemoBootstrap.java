package org.haruyq.createmediasystems.dev;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.lighting.AnalysisFrame;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.haruyq.createmediasystems.lighting.LightingSettings.Motion;
import org.haruyq.createmediasystems.lighting.LightingSettings.Pattern;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in runClient fixture; only compiled when -PlightingDemo is present. */
@EventBusSubscriber(modid = "create_media_systems")
public final class LightingDemoBootstrap {
    private static final String[] LIGHT_IDS = {"create_media_systems:stage_light", "create_media_systems:laser_projector"};
    private static HttpServer mediaServer;
    private static String mediaUrl;
    private static boolean verified;

    private LightingDemoBootstrap() {
    }

    @SubscribeEvent
    public static void levelLoaded(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !LightingLab.runs(level.getServer())
                || level.dimension() != Level.OVERWORLD) {
            return;
        }
        startMediaServer();
        if (LightingLab.NETWORK) {
            level.getChunk(LightingLab.CONSOLE);
        }
        if (level.getGameTime() <= 5) {
            build(level);
        } else if (LightingLab.console(level) instanceof LightingConsoleBlock.Entity console) {
            media(level, console);
        }
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        if (mediaServer != null) {
            mediaServer.stop(0);
            mediaServer = null;
            mediaUrl = null;
            verified = false;
        }
    }

    @SubscribeEvent
    public static void verify(ServerTickEvent.Post event) {
        if (verified || mediaServer == null || event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        if (LightingLab.console(event.getServer().overworld()) instanceof LightingConsoleBlock.Entity console
                && console.formed && console.hasAudio() && console.hasVideo()) {
            verified = true;
            long now = event.getServer().overworld().getGameTime();
            AnalysisFrame audio = console.audio(now);
            AnalysisFrame video = console.video(now);
            LogUtils.getLogger().info("Lighting demo ready: {} fixtures, audio={}, video={}, level={}, colors={}/{}, master={}, firstColor={}",
                    console.fixtures().size(), console.hasAudio(), console.hasVideo(), audio == null ? 0 : audio.level(),
                    Integer.toHexString(video == null ? 0 : video.left()), Integer.toHexString(video == null ? 0 : video.right()),
                    console.settings.master, Integer.toHexString(console.settings.groups[0].colors[0]));
        }
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)
                || !LightingLab.runs(level.getServer())) {
            return;
        }
        boolean hasLink = serverPlayer.getInventory().items.stream().anyMatch(stack ->
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("create_media_systems:lighting_link"));
        if (!hasLink) {
            serverPlayer.teleportTo(level, 0.5, 65, 8.5, 180, 20);
            give(serverPlayer, "create:wrench");
            give(serverPlayer, "create_media_systems:lighting_link");
            serverPlayer.getInventory().selected = 2;
            if (LightingLab.console(level) instanceof LightingConsoleBlock.Entity console) {
                console.settings.groups[5].target = serverPlayer.getUUID();
                console.changed();
            }
        }
    }

    public static void build(ServerLevel level) {
        BlockPos origin = LightingLab.CONSOLE;
        BlockEntity root = level.getBlockEntity(origin);
        if (root instanceof LightingConsoleBlock.Entity console && console.formed && !console.fixtures().isEmpty()) {
            return;
        }
        level.setDayTime(18000);
        level.setWeatherParameters(600000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false, level.getServer());
        for (int x = -12; x <= 12; x++) {
            for (int z = -10; z <= 12; z++) {
                level.setBlock(new BlockPos(x, 64, z), Blocks.BLACK_CONCRETE.defaultBlockState(), 3);
            }
        }
        for (int x = -14; x <= 14; x++) {
            for (int y = 65; y <= 85; y++) {
                for (int z = -12; z <= 12; z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int x = -10; x <= 10; x++) {
            level.setBlock(new BlockPos(x, 65, -8), Blocks.IRON_BLOCK.defaultBlockState(), 3);
        }
        LightingConsoleBlock.Entity console = LightingLab.buildConsole(level, origin, Direction.SOUTH);
        configure(console);
        media(level, console);
        placeFixtures(level, origin);
        signs(level);
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(true, level.getServer());
    }

    private static void media(ServerLevel level, LightingConsoleBlock.Entity console) {
        if (mediaUrl == null) {
            return;
        }
        BlockPos playerPos = LightingLab.PLAYER;
        BlockPos speakerPos = LightingLab.SPEAKER;
        BlockPos displayPos = LightingLab.DISPLAY;
        place(level, "create_media_systems:blu_ray_player", playerPos, Direction.SOUTH);
        place(level, "create_media_systems:speaker", speakerPos, Direction.SOUTH);
        place(level, "create_media_systems:display", displayPos, Direction.SOUTH);
        if (level.getBlockEntity(playerPos) instanceof VideoPlayerBlock.Entity player) {
            ItemStack disc = new ItemStack(DiscFeature.BLU_RAY.get());
            if (LightingLab.SABLE) {
                disc.set(DiscFeature.PLAYLIST.get(), Collections.nCopies(60, mediaUrl));
            } else {
                String next = mediaUrl + "?next=1";
                if (!LightingLab.NETWORK) {
                    LightingDemoCache.seedNext(next, LightingLab.media(), DiscKind.BLU_RAY.pictureHeight);
                }
                disc.set(DiscFeature.PLAYLIST.get(), List.of(mediaUrl, next));
            }
            player.insert(disc, null);
            console.setVideoSource(playerPos);
            if (level.getBlockEntity(speakerPos) instanceof SpeakerBlock.Entity speaker) {
                speaker.configure(level, playerPos, 1, org.haruyq.createmediasystems.stream.Streams.Channel.MIX);
                console.setAudioSource(playerPos);
            }
            if (level.getBlockEntity(displayPos) instanceof DisplayBlock.Entity display) {
                display.link(playerPos);
                display.resize(64, 32, DisplayBlock.Ratio.WIDE);
            }
            console.changed();
        }
    }

    private static void startMediaServer() {
        if (mediaServer != null) {
            return;
        }
        try {
            Path file = LightingLab.media();
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                try (var input = LightingDemoBootstrap.class.getResourceAsStream("/" + LightingLab.MEDIA)) {
                    if (input == null) {
                        throw new IOException("Missing lighting demo media");
                    }
                    Files.copy(input, file);
                }
            }
            mediaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mediaServer.createContext("/" + LightingLab.MEDIA, LightingDemoBootstrap::serveMedia);
            mediaServer.createContext("/info", exchange -> json(exchange, "{\"duration\":"
                    + Integer.getInteger("cms.demoSeconds", 60) + ",\"title\":\"CMS Lighting Lab\"}"));
            mediaServer.createContext("/extension/patterns", exchange -> json(exchange, "{\"urls\":[]}"));
            mediaServer.createContext("/diagnostics", exchange -> json(exchange, "{}"));
            mediaServer.setExecutor(command -> {
                Thread thread = new Thread(command, "cms-lighting-media");
                thread.setDaemon(true);
                thread.start();
            });
            mediaServer.start();
            mediaUrl = "http://127.0.0.1:" + mediaServer.getAddress().getPort() + "/" + LightingLab.MEDIA;
            ServerConfig.API_URL.set("http://127.0.0.1:" + mediaServer.getAddress().getPort());
            ServerConfig.ALLOWED_HOSTS.set(List.of("127.0.0.1", "localhost"));
            if (LightingLab.NETWORK) {
                net.neoforged.fml.config.ModConfigs.getModConfigs(CreateMediaSystems.MOD_ID).stream()
                        .filter(config -> config.getType() == net.neoforged.fml.config.ModConfig.Type.SERVER)
                        .forEach(config -> ((net.neoforged.neoforge.common.ModConfigSpec) config.getSpec()).save());
            }
        } catch (IOException error) {
            LogUtils.getLogger().error("Could not start lighting demo media", error);
        }
    }

    private static void serveMedia(HttpExchange exchange) throws IOException {
        Path file = LightingLab.media();
        long length = Files.size(file);
        long start = 0;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            start = Long.parseLong(range.substring(6).split("-", 2)[0]);
        }
        start = Math.max(0, Math.min(start, length - 1));
        exchange.getResponseHeaders().set("Content-Type", "video/mp4");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Length", Long.toString(length - start));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        if (start > 0) {
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (length - 1) + "/" + length);
        }
        exchange.sendResponseHeaders(start == 0 ? 200 : 206, length - start);
        try (var output = exchange.getResponseBody(); var input = Files.newInputStream(file)) {
            input.skipNBytes(start);
            input.transferTo(output);
        }
    }

    private static void json(HttpExchange exchange, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void configure(LightingConsoleBlock.Entity console) {
        Pattern[] patterns = {Pattern.SPOT, Pattern.BEAM, Pattern.LASER_RAY, Pattern.LASER_FAN, Pattern.LASER_SHEET,
                Pattern.BEAM};
        int[] palette = LightingSettings.DEFAULT_COLORS;
        int[] colors = {palette[0], palette[1], palette[2], palette[3], palette[5], palette[6]};
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            LightingSettings.Group group = console.settings.groups[i];
            group.pattern = patterns[i];
            group.motion = i == 5 ? Motion.TRACK : i == 0 ? Motion.FIXED : Motion.SWEEP;
            group.audio = i != 0;
            group.audioBand = LightingSettings.Band.values()[i % 4];
            group.colorCount = 2;
            group.colors[0] = colors[i];
            group.colors[1] = colors[(i + 1) % colors.length];
            group.mapping = LightingSettings.Mapping.values()[i % 4];
            group.range = i == 2 ? 48 : 32;
            group.spread = i == 4 ? 4 : 18;
            if (i >= 2 && i <= 4) {
                group.motion = Motion.SWEEP;
                group.pattern = patterns[i];
                group.spread = 40;
                group.tilt = -25;
            }
            group.colorCycle = i == 5;
            group.colorMode = i == 5 ? LightingSettings.ColorMode.VIDEO : LightingSettings.ColorMode.OWN;
        }
        console.changed();
    }

    private static void placeFixtures(ServerLevel level, BlockPos origin) {
        int index = 0;
        for (int x = -10; x <= 10; x += 4) {
            BlockPos pos = new BlockPos(x, 65, -5);
            place(level, LIGHT_IDS[1], pos, Direction.SOUTH);
            bind(level, pos, 2 + index % 3, origin);
            index++;
        }
        int[] groups = {0, 1, 5, 0, 1, 5};
        index = 0;
        for (int x = -10; x <= 10; x += 4) {
            BlockPos pos = new BlockPos(x, 72, -8);
            level.setBlock(pos.above(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
            level.setBlock(pos, LightingFeature.STAGE_LIGHT.get().defaultBlockState()
                    .setValue(StageLightBlock.FACING, Direction.SOUTH).setValue(StageLightBlock.HANGING, true), 3);
            bind(level, pos, groups[index++], origin);
        }
    }

    private static void bind(ServerLevel level, BlockPos pos, int group, BlockPos origin) {
        if (level.getBlockEntity(origin) instanceof LightingConsoleBlock.Entity console
                && level.getBlockEntity(pos) instanceof StageLightBlock.Entity light) {
            console.bind(light, group);
        }
    }

    private static void signs(ServerLevel level) {
        String[] labels = {"照明制御卓", "G1 スポット", "G2 ビーム", "G3 レーザー", "RGB 2色ミックス", "音声 / 映像リンク"};
        for (int i = 0; i < labels.length; i++) {
            BlockPos pos = new BlockPos(-5 + i * 2, 65, 3);
            level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 0), 3);
            if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                sign.setText(sign.getFrontText().setMessage(0, Component.literal(labels[i])), true);
                sign.setChanged();
            }
        }
    }

    private static void place(ServerLevel level, String id, BlockPos pos, Direction facing) {
        BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).ifPresent(block -> {
            BlockState state = block.defaultBlockState();
            if (state.hasProperty(LightingConsoleBlock.FACING)) {
                state = state.setValue(LightingConsoleBlock.FACING, facing);
            }
            level.setBlock(pos, state, 3);
        });
    }

    public static void give(ServerPlayer player, String id) {
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).ifPresent(item ->
                player.getInventory().add(new ItemStack(item)));
    }
}
