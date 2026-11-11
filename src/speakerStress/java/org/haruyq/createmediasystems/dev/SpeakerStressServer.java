package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Dedicated-server side of the speaker stress test (-PspeakerStress only): a jukebox, a disc reader and a Blu-ray
 * player, each linked to a wall of 99 speakers (33 each of mix, left and right), playing a local stereo file whose
 * left is 440 Hz and right 660 Hz. Writes what it relays to whom every second; a file "stop-server" stops it.
 */
@EventBusSubscriber(modid = "create_media_systems")
public final class SpeakerStressServer {
    public static final int MEDIA_PORT = 25591;
    static final int Y = -60;
    static final BlockPos JUKEBOX = new BlockPos(0, Y, -19);
    static final BlockPos DISC_READER = new BlockPos(19, Y, 0);
    static final BlockPos BLU_RAY = new BlockPos(-19, Y, 0);
    static final int SPEAKERS = 99;
    private static HttpServer media;
    private static BufferedWriter out;

    private SpeakerStressServer() {
    }

    private static String url(String source) {
        return "http://127.0.0.1:" + MEDIA_PORT + "/stress.mp4?src=" + source;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) throws IOException, ReflectiveOperationException {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        startMedia();
        ServerConfig.API_URL.set("http://127.0.0.1:" + MEDIA_PORT);
        ServerConfig.ALLOWED_HOSTS.set(List.of("127.0.0.1"));
        ServerLevel level = event.getServer().overworld();
        level.setDefaultSpawnPos(new BlockPos(0, Y, 0), 0);
        if (!(level.getBlockEntity(JUKEBOX) instanceof JukeboxBlockEntity)) {
            build(level);
            LogUtils.getLogger().info("Speaker stress scene built");
        }
        LogUtils.getLogger().info("Speaker stress server ready");
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) throws IOException {
        if (media != null) {
            media.stop(0);
            media = null;
        }
        if (out != null) {
            out.close();
            out = null;
        }
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Always the same place, among the three walls, within all of their ranges.
            player.teleportTo(player.serverLevel(), 0.5, Y, 2.5, 180, 0);
        }
    }

    private static void build(ServerLevel level) throws ReflectiveOperationException {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.setDayTime(6000);
        level.setBlock(JUKEBOX, Blocks.JUKEBOX.defaultBlockState(), 3);
        level.setBlock(DISC_READER, AudioFeature.DISC_READER.get().defaultBlockState(), 3);
        level.setBlock(BLU_RAY, VideoFeature.VIDEO_PLAYER.get().defaultBlockState(), 3);
        wall(level, JUKEBOX, i -> new BlockPos(i % 11 - 5, Y + i / 11, -22), Direction.SOUTH);
        wall(level, DISC_READER, i -> new BlockPos(22, Y + i / 11, i % 11 - 5), Direction.WEST);
        wall(level, BLU_RAY, i -> new BlockPos(-22, Y + i / 11, i % 11 - 5), Direction.EAST);

        ItemStack jukeboxDisc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        jukeboxDisc.set(AudioFeature.URL.get(), url("jukebox"));
        ((JukeboxBlockEntity) level.getBlockEntity(JUKEBOX)).setTheItem(jukeboxDisc);

        ItemStack readerDisc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        readerDisc.set(AudioFeature.URL.get(), url("reader"));
        ((DiscReaderBlock.Entity) level.getBlockEntity(DISC_READER)).insert(readerDisc, null);

        ItemStack bluRay = new ItemStack(VideoFeature.BLU_RAY.get());
        bluRay.set(VideoFeature.PLAYLIST.get(), List.of(url("bluray")));
        ((VideoPlayerBlock.Entity) level.getBlockEntity(BLU_RAY)).insert(bluRay, null);
    }

    private static void wall(ServerLevel level, BlockPos source, java.util.function.IntFunction<BlockPos> at, Direction facing) {
        for (int i = 0; i < SPEAKERS; i++) {
            BlockPos pos = at.apply(i);
            level.setBlock(pos, AudioFeature.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, facing), 3);
            ((SpeakerBlock.Entity) level.getBlockEntity(pos)).configure(level, source, 1, Streams.Channel.values()[i % 3]);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws IOException, ReflectiveOperationException {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer() || server.getTickCount() % 20 != 0) {
            return;
        }
        Path dir = server.getServerDirectory();
        if (Files.deleteIfExists(dir.resolve("stop-server"))) {
            LogUtils.getLogger().info("Speaker stress: stopping the server as asked");
            server.halt(false);
            return;
        }
        if (out == null) {
            out = Files.newBufferedWriter(dir.resolve("stress-server.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        ServerLevel level = server.overworld();
        // Per source and channel: how many emitters relay it, and to how many of them each player listens.
        Map<String, int[]> sides = new HashMap<>();
        for (Streams.RelayView relay : Streams.relays()) {
            Streams.Sound sound = relay.sound();
            int[] counts = sides.computeIfAbsent(name(sound.source()) + "/" + sound.channel(), k -> new int[2]);
            counts[0]++;
            counts[1] += relay.listeners();
        }
        StringBuilder json = new StringBuilder("{\"wallMillis\":").append(System.currentTimeMillis())
                .append(",\"tick\":").append(server.getTickCount()).append(",\"players\":")
                .append(server.getPlayerCount()).append(",\"sources\":{");
        boolean first = true;
        for (BlockPos source : List.of(JUKEBOX, DISC_READER, BLU_RAY)) {
            Streams.Source playing = Streams.source(level, source);
            json.append(first ? "" : ",").append('"').append(name(source)).append("\":")
                    .append(playing == null ? -1 : playing.ticks());
            first = false;
        }
        json.append("},\"sides\":{");
        first = true;
        for (var side : sides.entrySet()) {
            json.append(first ? "" : ",").append('"').append(side.getKey()).append("\":[")
                    .append(side.getValue()[0]).append(',').append(side.getValue()[1]).append(']');
            first = false;
        }
        out.write(json.append("}}\n").toString());
        out.flush();
    }

    static String name(BlockPos source) {
        return source.equals(JUKEBOX) ? "jukebox" : source.equals(DISC_READER) ? "reader"
                : source.equals(BLU_RAY) ? "bluray" : source.toShortString().replace(", ", "_");
    }


    private static void startMedia() throws IOException {
        if (media != null) {
            return;
        }
        media = HttpServer.create(new InetSocketAddress("127.0.0.1", MEDIA_PORT), 0);
        media.createContext("/stress.mp4", SpeakerStressServer::serve);
        media.createContext("/info", exchange -> json(exchange, "{\"duration\":3600,\"title\":\"Speaker stress\"}"));
        media.createContext("/extension/patterns", exchange -> json(exchange, "{\"urls\":[]}"));
        media.createContext("/diagnostics", exchange -> json(exchange, "{}"));
        media.setExecutor(command -> Thread.ofPlatform().daemon().name("cms-stress-media").start(command));
        media.start();
    }

    private static void serve(HttpExchange exchange) throws IOException {
        Path file = Path.of("media", "stress.mp4");
        long length = Files.size(file);
        long start = 0;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            start = Long.parseLong(range.substring(6).split("-", 2)[0]);
        }
        start = Math.max(0, Math.min(start, length - 1));
        exchange.getResponseHeaders().set("Content-Type", "video/mp4");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
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
        } catch (IOException closed) {
            // VLC drops the connection when it seeks or stops.
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
}
