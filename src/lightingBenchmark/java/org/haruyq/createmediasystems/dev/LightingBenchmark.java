package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.display.DisplayFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = CreateMediaSystems.MOD_ID)
public final class LightingBenchmark {
    private static final int FLOOR = 120;
    private static final int FIXTURES = 24;
    private static final boolean DENSE = Boolean.getBoolean("cms.lightingDense");
    private static final Vec3 CAMERA = new Vec3(0.5, FLOOR + (DENSE ? 22.5 : 12.5), DENSE ? 64.5 : 24.5);
    private static final float CAMERA_YAW = 180;
    private static final float CAMERA_PITCH = 12;
    private static final BlockPos CONSOLE = new BlockPos(-1, 65, 5);
    private static final BlockPos PLAYER = new BlockPos(-8, 65, 5);
    private static final BlockPos DISPLAY = new BlockPos(6, FLOOR + 4, -6);
    private static volatile Phase active;

    private LightingBenchmark() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws ReflectiveOperationException {
        if (!enabled() || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        if (level.players().isEmpty() || !event.getServer().getWorldData().getLevelName().equals(LightingDemoBootstrap.LEVEL_NAME)
                || !(level.getBlockEntity(CONSOLE) instanceof LightingConsoleBlock.Entity console)
                || !console.formed || !(level.getBlockEntity(PLAYER) instanceof VideoPlayerBlock.Entity player)) {
            return;
        }
        Mode mode = mode();
        if (mode == Mode.STOP) {
            return;
        }
        Phase previous = active;
        if (previous == null) {
            build(level, console, player);
        } else if (previous.mode == mode) {
            return;
        }
        configure(level, console, mode);
        var lighting = VideoPlayerBlock.Entity.class.getDeclaredField("lighting");
        lighting.setAccessible(true);
        ((ScrollValueBehaviour) lighting.get(player)).setValue(mode.displayLighting());
        player.control(Remotable.Action.SELECT, 1, null);
        active = new Phase(mode, System.nanoTime());
        level.players().forEach(LightingBenchmark::camera);
        LogUtils.getLogger().info("Lighting benchmark phase {}: {} fixtures", mode.key, console.fixtures.size());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        active = null;
    }

    private static boolean enabled() {
        return Boolean.getBoolean("cms.lightingBenchmark")
                && FMLPaths.GAMEDIR.get().getFileName().toString().equals("run-lighting-benchmark");
    }

    private static Mode mode() {
        Path control = FMLPaths.GAMEDIR.get().resolve("bench-mode");
        if (Files.isRegularFile(control)) {
            try {
                return Mode.valueOf(Files.readString(control).replace("\uFEFF", "").strip().toUpperCase(Locale.ROOT));
            } catch (IOException | IllegalArgumentException ignored) {
                return active == null ? Mode.LASER_HIT : active.mode;
            }
        }
        return Mode.LASER_HIT;
    }

    private static void build(ServerLevel level, LightingConsoleBlock.Entity console, VideoPlayerBlock.Entity player)
            throws ReflectiveOperationException {
        for (BlockPos pos : List.copyOf(console.fixtures)) {
            if (level.getBlockEntity(pos) instanceof StageLightBlock.Entity) {
                level.removeBlock(pos, false);
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int right = DENSE ? 36 : 22;
        for (int x = DENSE ? -36 : -18; x <= right; x++) {
            for (int z = DENSE ? -40 : -25; z <= (DENSE ? 70 : 27); z++) {
                level.setBlock(pos.set(x, FLOOR, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 3);
                for (int y = FLOOR + 1; y <= FLOOR + 14; y++) {
                    pos.set(x, y, z);
                    if (x == right) {
                        level.setBlock(pos, Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
                    } else if (!level.isEmptyBlock(pos)) {
                        level.removeBlock(pos, false);
                    }
                }
            }
        }
        if (DENSE) {
            for (int x = -30; x <= 30; x += 2) {
                for (int z = 4; z <= 54; z += 2) {
                    level.setBlock(new BlockPos(x, FLOOR + 1 + z / 8, z),
                            Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), 3);
                }
            }
        }
        level.removeBlock(new BlockPos(-5, 67, 5), false);
        List<BlockPos> displays = DENSE
                ? List.of(new BlockPos(-16, FLOOR + 4, -28), new BlockPos(-34, FLOOR + 4, -4),
                        new BlockPos(34, FLOOR + 4, 16), new BlockPos(-16, FLOOR + 24, -28),
                        new BlockPos(-34, FLOOR + 24, -4))
                : List.of(DISPLAY, DISPLAY.offset(-14, 0, 0), DISPLAY.above(8),
                        DISPLAY.offset(-14, 8, 0), new BlockPos(21, FLOOR + 4, 7));
        int count = Math.clamp(Integer.getInteger("cms.lightingDisplays", 1), 1, displays.size());
        for (int i = 0; i < count; i++) {
            BlockPos at = displays.get(i);
            Direction facing = DENSE ? i == 1 || i == 4 ? Direction.EAST : i == 2 ? Direction.WEST : Direction.SOUTH
                    : i == 4 ? Direction.WEST : Direction.SOUTH;
            level.setBlockAndUpdate(at, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, facing));
            if (level.getBlockEntity(at) instanceof DisplayBlock.Entity display) {
                var link = DisplayBlock.Entity.class.getDeclaredMethod("link", BlockPos.class);
                link.setAccessible(true);
                link.invoke(display, PLAYER);
                display.resize((DENSE ? 32 : 12) * DisplayBlock.UNIT, DENSE ? 288 : 108, DisplayBlock.Ratio.WIDE);
            }
        }
        player.getBehaviour(ScrollValueBehaviour.TYPE).setValue(0);
    }

    private static void configure(ServerLevel level, LightingConsoleBlock.Entity console, Mode mode) {
        var kind = mode.laser ? LightingFeature.LASER_PROJECTOR.get() : LightingFeature.STAGE_LIGHT.get();
        for (int i = 0; i < FIXTURES; i++) {
            int group = i < 11 ? 0 : i < 22 ? 1 : i - 20;
            BlockPos pos = i < 22 ? new BlockPos(-13 + group * 2, FLOOR + 5 + group * 3, -22 + i % 11 * 2)
                    : new BlockPos(-13, FLOOR + (i == 22 ? 6 : 10), i == 22 ? -13 : 8);
            if (!level.getBlockState(pos).is(kind)) {
                level.setBlockAndUpdate(pos, kind.defaultBlockState().setValue(StageLightBlock.FACING, Direction.EAST));
            }
            if (level.getBlockEntity(pos) instanceof StageLightBlock.Entity lamp && lamp.controller() != console) {
                console.bind(lamp, group);
            }
        }
        console.settings.enabled = mode.fixtures();
        console.settings.master = 1;
        for (int i = 0; i < console.settings.groups.length; i++) {
            var group = console.settings.groups[i];
            group.pattern = mode.laser ? 3 : 0;
            group.range = 64;
            group.spread = mode.laser && !mode.depth() ? 34 : 24;
            group.brightness = mode.brightness(i);
            group.motion = mode.motion();
            group.speed = 1;
            group.audio = false;
            group.colorCycle = false;
            group.colorMode = 0;
            group.colorCount = 1;
            group.colors[0] = i == 2 ? 0xff3040 : i == 3 ? 0x30eaff : 0xbcb8ff;
            group.pan = 0;
            group.tilt = mode.tilt(i);
        }
        console.changed();
    }

    /** Where the camera looks: at the stage, or (spot_away) away from it, with every light behind. */
    private static float yaw() {
        Phase current = active;
        return current != null && current.mode == Mode.SPOT_AWAY ? 0 : CAMERA_YAW;
    }

    private static void camera(ServerPlayer player) {
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(player.serverLevel(), CAMERA.x, CAMERA.y, CAMERA.z, yaw(), CAMERA_PITCH);
        player.setDeltaMovement(Vec3.ZERO);
        selectEmpty(player.getInventory());
    }

    private static void selectEmpty(Inventory inventory) {
        if (!inventory.getSelected().isEmpty()) {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.selected = slot;
                    return;
                }
            }
        }
    }

    private enum Mode {
        LASER_HIT(true, true), LASER_CLEAR(true, false), SPOT_HIT(false, true), SPOT_CLEAR(false, false),
        LASER_MOVE(true, true), SPOT_MOVE(false, true), LASER_DEPTH(true, false), LASER_DEPTH_BACK(true, false),
        LASER_DEPTH_FRONT(true, false), OFF(false, false), STOP(false, false),
        DISPLAY_OFF(false, true), DISPLAY_LOW(false, true), DISPLAY_HIGH(false, true), SPOT_ONLY(false, true),
        SPOT_AWAY(false, true);

        final boolean laser;
        final boolean hit;
        final String key = name().toLowerCase(Locale.ROOT);

        Mode(boolean laser, boolean hit) {
            this.laser = laser;
            this.hit = hit;
        }

        float tilt(int group) {
            return depth() ? 0 : hit ? (group == 0 ? 36 : 6) : -48;
        }

        boolean depth() {
            return this == LASER_DEPTH || this == LASER_DEPTH_BACK || this == LASER_DEPTH_FRONT;
        }

        float brightness(int group) {
            return switch (this) {
                case LASER_DEPTH -> group == 2 || group == 3 ? 1 : 0;
                case LASER_DEPTH_BACK -> group == 2 ? 1 : 0;
                case LASER_DEPTH_FRONT -> group == 3 ? 1 : 0;
                default -> 1;
            };
        }

        int motion() {
            return this == LASER_MOVE || this == SPOT_MOVE || this == SPOT_ONLY || this == SPOT_AWAY ? 1 : 0;
        }

        boolean fixtures() {
            return this != OFF && this != DISPLAY_OFF && this != DISPLAY_LOW && this != DISPLAY_HIGH;
        }

        int displayLighting() {
            return this == DISPLAY_OFF || this == SPOT_ONLY ? 0 : this == DISPLAY_LOW ? 1 : 100;
        }
    }

    private record Phase(Mode mode, long started) {
    }

    @EventBusSubscriber(modid = CreateMediaSystems.MOD_ID, value = Dist.CLIENT)
    public static final class Frames {
        private static Phase phase;
        private static long previous;
        private static long started;
        private static long frameNs;
        private static long nextFlush;
        private static long nextStopCheck;
        private static BufferedWriter output;
        private static boolean failed;
        private static Phase captured;
        private static final int[][] queries = new int[8][2];
        private static final Phase[] queryPhases = new Phase[8];
        private static int queryHead;
        private static int queryCount;
        private static int queryFrame;
        private static boolean queryActive;
        private static long gpuFrameNs;

        private Frames() {
        }

        /** The GPU's time for a whole frame (every 8th), the stage lights' light and beams included. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void beforeFrame(RenderFrameEvent.Pre event) {
            if (phase == null || phase != active
                    || !GL.getCapabilities().OpenGL33 || ++queryFrame % 8 != 0 || queryCount == queries.length) {
                return;
            }
            int slot = (queryHead + queryCount) % queries.length;
            if (queries[slot][0] == 0) {
                queries[slot][0] = GL15.glGenQueries();
                queries[slot][1] = GL15.glGenQueries();
            }
            queryPhases[slot] = phase;
            GL33.glQueryCounter(queries[slot][0], GL33.GL_TIMESTAMP);
            queryActive = true;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void afterFrame(RenderFrameEvent.Post event) {
            if (queryActive) {
                GL33.glQueryCounter(queries[(queryHead + queryCount) % queries.length][1], GL33.GL_TIMESTAMP);
                queryCount++;
                queryActive = false;
            }
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (!enabled()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            long now = System.nanoTime();
            if (now - nextStopCheck >= 0) {
                nextStopCheck = now + 500_000_000L;
                if (mode() == Mode.STOP) {
                    minecraft.stop();
                    return;
                }
            }
            if (active != null && minecraft.player != null) {
                fixCamera(minecraft);
                minecraft.player.setDeltaMovement(Vec3.ZERO);
                selectEmpty(minecraft.player.getInventory());
            }
        }

        private static void fixCamera(Minecraft minecraft) {
            var player = minecraft.player;
            if (!CAMERA.equals(player.position()) || player.getYRot() != yaw() || player.getXRot() != CAMERA_PITCH) {
                player.moveTo(CAMERA.x, CAMERA.y, CAMERA.z, yaw(), CAMERA_PITCH);
            }
            if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            }
        }

        @SubscribeEvent
        public static void pre(RenderFrameEvent.Pre event) {
            Phase current = active;
            if (current == null || failed) {
                return;
            }
            gpuFrameNs = -1;
            while (queryCount > 0 && GL15.glGetQueryObjecti(queries[queryHead][1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                if (queryPhases[queryHead] == current) {
                    gpuFrameNs = GL33.glGetQueryObjectui64(queries[queryHead][1], GL15.GL_QUERY_RESULT)
                            - GL33.glGetQueryObjectui64(queries[queryHead][0], GL15.GL_QUERY_RESULT);
                }
                queryHead = (queryHead + 1) % queries.length;
                queryCount--;
            }
            Minecraft minecraft = Minecraft.getInstance();
            long now = System.nanoTime();
            if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
                    || now - current.started < 3_000_000_000L) {
                previous = 0;
                started = 0;
                return;
            }
            fixCamera(minecraft);
            if (phase != current) {
                previous = 0;
                started = 0;
                var video = NativeAudio.screen(PLAYER);
                if (!(minecraft.level.getBlockEntity(CONSOLE) instanceof LightingConsoleBlock.Entity console)
                        || console.fixtures.size() != FIXTURES || console.settings.enabled != current.mode.fixtures()
                        || console.settings.groups[0].pattern != (current.mode.laser ? 3 : 0)
                        || console.settings.groups[0].tilt != current.mode.tilt(0)
                        || console.settings.groups[2].brightness != current.mode.brightness(2)
                        || console.settings.groups[3].brightness != current.mode.brightness(3)
                        || console.settings.groups[0].motion != current.mode.motion() || video == null || video.shown() == null) {
                    return;
                }
                phase = current;
            }
            started = now;
            frameNs = previous == 0 ? 0 : now - previous;
            previous = now;
        }

        @SubscribeEvent
        public static void post(RenderFrameEvent.Post event) {
            if (started == 0 || frameNs == 0 || phase != active || failed) {
                return;
            }
            long now = System.nanoTime();
            if (Boolean.getBoolean("cms.benchmarkCapture") && captured != phase && now - phase.started > 15_000_000_000L) {
                captured = phase;
                Minecraft minecraft = Minecraft.getInstance();
                Screenshot.grab(minecraft.gameDirectory, "benchmark-" + phase.mode.key + ".png",
                        minecraft.getMainRenderTarget(), message -> LogUtils.getLogger().info(message.getString()));
            }
            try {
                if (output == null) {
                    output = Files.newBufferedWriter(FMLPaths.GAMEDIR.get().resolve("lighting-benchmark.jsonl"),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                output.write("{\"wallMillis\":" + System.currentTimeMillis() + ",\"phase\":\"" + phase.mode.key
                        + "\",\"phaseStartNs\":" + phase.started + ",\"frameNs\":" + frameNs + ",\"renderNs\":"
                        + (now - started) + ",\"gpuFrameNs\":" + gpuFrameNs + "}\n");
                if (now - nextFlush >= 0) {
                    output.flush();
                    nextFlush = now + 1_000_000_000L;
                }
            } catch (IOException error) {
                failed = true;
                LogUtils.getLogger().error("Could not record lighting benchmark frames", error);
            }
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            started = 0;
            previous = 0;
            for (int[] query : queries) {
                if (query[0] != 0) {
                    GL15.glDeleteQueries(query[0]);
                    GL15.glDeleteQueries(query[1]);
                    query[0] = 0;
                    query[1] = 0;
                }
            }
            queryCount = 0;
            queryHead = 0;
            queryActive = false;
            if (output != null) {
                try {
                    output.close();
                } catch (IOException error) {
                    LogUtils.getLogger().warn("Could not close lighting benchmark frame log", error);
                }
                output = null;
            }
        }
    }
}
