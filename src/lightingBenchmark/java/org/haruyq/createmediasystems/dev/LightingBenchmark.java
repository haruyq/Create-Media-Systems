package org.haruyq.createmediasystems.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
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
    private static final Vec3 CAMERA = new Vec3(0.5, FLOOR + 12.5, 24.5);
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
        player.control(VideoPlayerBlock.Action.SELECT, 1);
        level.players().forEach(LightingBenchmark::camera);
        active = new Phase(mode, System.nanoTime());
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
        for (int x = -18; x <= 22; x++) {
            for (int z = -25; z <= 27; z++) {
                level.setBlock(pos.set(x, FLOOR, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 3);
                for (int y = FLOOR + 1; y <= FLOOR + 14; y++) {
                    pos.set(x, y, z);
                    if (x == 22) {
                        level.setBlock(pos, Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
                    } else if (!level.isEmptyBlock(pos)) {
                        level.removeBlock(pos, false);
                    }
                }
            }
        }
        level.removeBlock(new BlockPos(-5, 67, 5), false);
        level.setBlockAndUpdate(DISPLAY, CreateMediaSystems.DISPLAY.get().defaultBlockState()
                .setValue(DisplayBlock.FACING, Direction.SOUTH));
        if (level.getBlockEntity(DISPLAY) instanceof DisplayBlock.Entity display) {
            var link = DisplayBlock.Entity.class.getDeclaredMethod("link", BlockPos.class);
            link.setAccessible(true);
            link.invoke(display, PLAYER);
            display.resize(12 * DisplayBlock.UNIT, 108, DisplayBlock.Ratio.WIDE);
        }
        player.getBehaviour(ScrollValueBehaviour.TYPE).setValue(0);
    }

    private static void configure(ServerLevel level, LightingConsoleBlock.Entity console, Mode mode) {
        var kind = mode.laser ? CreateMediaSystems.LASER_PROJECTOR.get() : CreateMediaSystems.STAGE_LIGHT.get();
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
        console.settings.enabled = mode != Mode.OFF;
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

    private static void camera(ServerPlayer player) {
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(player.serverLevel(), CAMERA.x, CAMERA.y, CAMERA.z, CAMERA_YAW, CAMERA_PITCH);
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
        LASER_DEPTH_FRONT(true, false), OFF(false, false), STOP(false, false);

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
            return this == LASER_MOVE || this == SPOT_MOVE ? 1 : 0;
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

        private Frames() {
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
            if (!CAMERA.equals(player.position()) || player.getYRot() != CAMERA_YAW || player.getXRot() != CAMERA_PITCH) {
                player.moveTo(CAMERA.x, CAMERA.y, CAMERA.z, CAMERA_YAW, CAMERA_PITCH);
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
                        || console.fixtures.size() != FIXTURES || console.settings.enabled != (current.mode != Mode.OFF)
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
            try {
                if (output == null) {
                    output = Files.newBufferedWriter(FMLPaths.GAMEDIR.get().resolve("lighting-benchmark.jsonl"),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                output.write("{\"wallMillis\":" + System.currentTimeMillis() + ",\"phase\":\"" + phase.mode.key
                        + "\",\"phaseStartNs\":" + phase.started + ",\"frameNs\":" + frameNs + ",\"renderNs\":"
                        + (now - started) + "}\n");
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
