package org.haruyq.createmediasystems.dev;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.lighting.ConsoleLayout;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.impl.none.StaticPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "create_media_systems")
public final class SableLightingDemo {
    private static final BlockPos DESK = LightingLab.CONSOLE;
    private static final BlockPos MARKER = new BlockPos(26, 64, 14);
    private static final BlockPos WALL = new BlockPos(-16, 65, -6);
    private static final BlockPos PLATFORM = new BlockPos(16, 65, -6);
    private static final BlockPos LAMP = new BlockPos(-24, 65, 0);
    private static final String[] NAMES = {"CMS Lighting / Wall", "CMS Lighting / Console", "CMS Lighting / Lamp"};
    private static final List<Body> bodies = new ArrayList<>();
    private static boolean moving = true;
    private static boolean mediaReady;
    private static boolean verified;
    private static double phase;

    private SableLightingDemo() {
    }

    private record Body(ServerSubLevel ship, int role, Vector3d position, Quaterniond orientation) {
    }

    private static boolean lab(ServerLevel level) {
        return LightingLab.SABLE && level.dimension() == ServerLevel.OVERWORLD && LightingLab.runs(level.getServer());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppingEvent event) {
        bodies.clear();
        mediaReady = verified = false;
        moving = true;
        phase = 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && lab(player.serverLevel())) {
            overview(player);
            player.displayClientMessage(Component.literal("Sable照明テスト: /cms_sable_demo desk・ship・motion・view"), false);
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cms_sable_demo")
                .requires(source -> source.hasPermission(2) && lab(source.getLevel()))
                .then(Commands.literal("motion").executes(context -> {
                    moving = !moving;
                    context.getSource().sendSuccess(() -> Component.literal(moving ? "構造物の移動を再開しました" : "構造物の移動を停止しました"), false);
                    return 1;
                }))
                .then(Commands.literal("view").executes(context -> {
                    overview(context.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("desk").executes(context -> {
                    context.getSource().getPlayerOrException().teleportTo(context.getSource().getLevel(), 0.5, 65, 8.5, 180, 20);
                    return 1;
                }))
                .then(Commands.literal("ship").executes(context -> {
                    for (Body body : bodies) {
                        if (body.role == 1) {
                            BlockPos plot = body.ship.getPlot().getCenterBlock();
                            Vec3 at = SableCompat.toWorld(context.getSource().getLevel(), plot, plot.getCenter().add(1, 1, 4));
                            context.getSource().getPlayerOrException().teleportTo(context.getSource().getLevel(), at.x, at.y, at.z, 180, 25);
                            return 1;
                        }
                    }
                    return 0;
                })));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        ServerLevel level = event.getServer().overworld();
        if (!lab(level) || level.players().isEmpty() || event.getServer().getTickCount() < 40) {
            return;
        }
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
        if (physics == null || !(level.getBlockEntity(DESK) instanceof LightingConsoleBlock.Entity console)) {
            return;
        }
        if (!level.getBlockState(MARKER).is(Blocks.LODESTONE)) {
            build(level, console);
        }
        if (bodies.isEmpty() || level.getGameTime() % 20 == 0) {
            discover(level);
        }
        if (physics.getPipeline() instanceof StaticPhysicsPipeline) {
            physics.setPaused(true);
        }
        if (moving) {
            phase += 0.05;
        }
        for (Body body : bodies) {
            if (body.ship.isRemoved()) {
                continue;
            }
            Vector3d position = new Vector3d(body.position);
            Quaterniond orientation = new Quaterniond(body.orientation);
            if (body.role == 0) {
                position.x += Math.sin(phase * 0.5) * 1.5;
                orientation.rotateY(Math.sin(phase * 0.35) * 0.22);
            } else if (body.role == 1) {
                position.x += Math.sin(phase * 0.3) * 1.25;
                orientation.rotateY(Math.sin(phase * 0.4) * 0.16).rotateZ(Math.sin(phase * 0.3) * 0.035);
            } else {
                position.z += Math.sin(phase * 0.35) * 1.5;
                orientation.rotateY(Math.sin(phase * 0.45) * 0.14);
            }
            body.ship.updateLastPose();
            physics.getPipeline().teleport(body.ship, position, orientation);
            physics.getPipeline().resetVelocity(body.ship);
            physics.updatePose(body.ship);
            body.ship.forceUpdateGlobalBounds();
        }
        if (!mediaReady && bodies.size() == 3) {
            mediaReady = media(level, console);
        }
        if (!verified && mediaReady && level.getGameTime() % 100 == 0) {
            for (Body body : bodies) {
                if (body.role == 1 && level.getBlockEntity(body.ship.getPlot().getCenterBlock().offset(0, 1, 2))
                        instanceof LightingConsoleBlock.Entity aboard && aboard.hasAudio() && aboard.hasVideo() && console.hasVideo()) {
                    verified = true;
                    LogUtils.getLogger().info("Sable lighting demo ready: ships={}, groundFixtures={}, onboardFixtures={}, groundVideo={}, onboardAudio={}, onboardVideo={}",
                            bodies.size(), console.fixtures().size(), aboard.fixtures().size(), console.hasVideo(), aboard.hasAudio(), aboard.hasVideo());
                }
            }
        }
    }

    private static void build(ServerLevel level, LightingConsoleBlock.Entity console) {
        boolean drops = level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false, level.getServer());
        for (BlockPos pos : console.fixtures()) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        for (int x = -28; x <= 28; x++) {
            for (int z = -14; z <= 16; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 64, z), Blocks.BLACK_CONCRETE.defaultBlockState());
                if (Math.abs(x) > 14 || z < -12 || z > 12) {
                    for (int y = 65; y <= 78; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        for (int x = 10; x <= 18; x++) {
            for (int y = 65; y <= 72; y++) {
                level.setBlockAndUpdate(new BlockPos(x, y, 6), Blocks.WHITE_CONCRETE.defaultBlockState());
            }
        }
        List<BlockPos> wall = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int y = 0; y <= 6; y++) {
                BlockPos pos = WALL.offset(x, y, 0);
                level.setBlockAndUpdate(pos, Blocks.WHITE_CONCRETE.defaultBlockState());
                wall.add(pos);
            }
        }
        light(level, console, new BlockPos(-16, 65, 6), 0, Direction.NORTH);
        List<BlockPos> platform = new ArrayList<>();
        for (int x = -3; x <= 4; x++) {
            for (int z = -1; z <= 3; z++) {
                BlockPos pos = PLATFORM.offset(x, 0, z);
                level.setBlockAndUpdate(pos, Blocks.IRON_BLOCK.defaultBlockState());
                platform.add(pos);
            }
        }
        BlockPos board = PLATFORM.offset(0, 1, 2);
        platform.addAll(ConsoleLayout.place(level, board, Direction.SOUTH, Block.UPDATE_ALL));
        LightingConsoleBlock.Entity aboard = (LightingConsoleBlock.Entity) level.getBlockEntity(board);
        aboard.updateStructure();
        light(level, aboard, PLATFORM.offset(-2, 1, 0), 0, Direction.SOUTH);
        platform.add(PLATFORM.offset(-2, 1, 0));
        level.setBlockAndUpdate(PLATFORM.offset(3, 1, 0), VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        platform.add(PLATFORM.offset(3, 1, 0));
        level.setBlockAndUpdate(PLATFORM.offset(3, 1, 3), AudioFeature.SPEAKER.get().defaultBlockState());
        platform.add(PLATFORM.offset(3, 1, 3));
        aboard.setAudioSource(PLATFORM.offset(3, 1, 0));
        aboard.setVideoSource(PLATFORM.offset(3, 1, 0));
        console.setVideoSource(PLATFORM.offset(3, 1, 0));
        List<BlockPos> lamp = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = LAMP.offset(x, 0, z);
                level.setBlockAndUpdate(pos, Blocks.IRON_BLOCK.defaultBlockState());
                lamp.add(pos);
            }
        }
        light(level, console, LAMP.above(), 2, Direction.NORTH);
        lamp.add(LAMP.above());
        light(level, console, new BlockPos(6, 65, 0), 5, Direction.NORTH);
        configure(console, aboard);
        assemble(level, WALL, wall, 0, new BoundingBox3i(WALL.getX() - 4, 65, WALL.getZ(), WALL.getX() + 4, 71, WALL.getZ()));
        assemble(level, PLATFORM, platform, 1, new BoundingBox3i(13, 65, -7, 20, 67, -3));
        assemble(level, LAMP, lamp, 2, new BoundingBox3i(LAMP.getX() - 1, 65, -1, LAMP.getX() + 1, 66, 1));
        console.refreshLinks();
        sign(level, new BlockPos(-18, 65, 10), "地上 → 移動壁", "G1 / 左の白い壁");
        sign(level, new BlockPos(-25, 65, 5), "別船 → 別船", "G3 / 斜めのビーム");
        sign(level, new BlockPos(16, 65, 10), "移動灯具 → 地上壁", "船上の卓で操作");
        sign(level, new BlockPos(5, 65, 8), "Sable照明テスト", "/cms_sable_demo motion");
        level.setBlockAndUpdate(MARKER, Blocks.LODESTONE.defaultBlockState());
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(drops, level.getServer());
        LogUtils.getLogger().info("Created isolated Sable lighting demo: three scripted sub-levels, shipboard console, ground/ship/cross-ship lighting");
    }

    private static void configure(LightingConsoleBlock.Entity console, LightingConsoleBlock.Entity aboard) {
        for (int group : new int[]{0, 2, 5}) {
            var settings = console.settings.groups[group];
            settings.audio = false;
            settings.colorCycle = false;
            settings.motion = group == 5 ? LightingSettings.Motion.TRACK : LightingSettings.Motion.FIXED;
            settings.pattern = group == 2 ? LightingSettings.Pattern.BEAM : LightingSettings.Pattern.SPOT;
            settings.pan = group == 2 ? 50 : 0;
            settings.tilt = 0;
            settings.range = 40;
            settings.spread = group == 2 ? 5 : 12;
            settings.colorMode = group == 2 ? LightingSettings.ColorMode.VIDEO : LightingSettings.ColorMode.OWN;
        }
        aboard.settings.groups[0].motion = LightingSettings.Motion.FIXED;
        aboard.settings.groups[0].pattern = LightingSettings.Pattern.BEAM;
        aboard.settings.groups[0].tilt = 0;
        aboard.settings.groups[0].range = 40;
        aboard.settings.groups[0].colorMode = LightingSettings.ColorMode.VIDEO;
        console.changed();
        aboard.changed();
    }

    private static void light(ServerLevel level, LightingConsoleBlock.Entity console, BlockPos pos, int group, Direction facing) {
        level.setBlockAndUpdate(pos, LightingFeature.STAGE_LIGHT.get().defaultBlockState().setValue(StageLightBlock.FACING, facing));
        console.bind((StageLightBlock.Entity) level.getBlockEntity(pos), group);
    }

    private static void assemble(ServerLevel level, BlockPos origin, List<BlockPos> blocks, int role, BoundingBox3i bounds) {
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks, bounds);
        ship.setName(NAMES[role]);
        CompoundTag data = new CompoundTag();
        data.putInt("Role", role);
        data.putDouble("X", ship.logicalPose().position().x);
        data.putDouble("Y", ship.logicalPose().position().y);
        data.putDouble("Z", ship.logicalPose().position().z);
        data.putDouble("QX", ship.logicalPose().orientation().x);
        data.putDouble("QY", ship.logicalPose().orientation().y);
        data.putDouble("QZ", ship.logicalPose().orientation().z);
        data.putDouble("QW", ship.logicalPose().orientation().w);
        CompoundTag tag = ship.getUserDataTag();
        if (tag == null) {
            tag = new CompoundTag();
        }
        tag.put("CmsLightingDemo", data);
        ship.setUserDataTag(tag);
    }

    private static void discover(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        bodies.clear();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            CompoundTag tag = ship.getUserDataTag();
            if (tag != null && tag.contains("CmsLightingDemo")) {
                CompoundTag data = tag.getCompound("CmsLightingDemo");
                bodies.add(new Body(ship, data.getInt("Role"), new Vector3d(data.getDouble("X"), data.getDouble("Y"), data.getDouble("Z")),
                        new Quaterniond(data.getDouble("QX"), data.getDouble("QY"), data.getDouble("QZ"), data.getDouble("QW"))));
            }
        }
    }

    private static boolean media(ServerLevel level, LightingConsoleBlock.Entity console) {
        if (!(level.getBlockEntity(LightingLab.PLAYER) instanceof VideoPlayerBlock.Entity ground) || ground.playlist().isEmpty()) {
            return false;
        }
        for (Body body : bodies) {
            if (body.role != 1) {
                continue;
            }
            BlockPos plot = body.ship.getPlot().getCenterBlock();
            BlockPos playerPos = plot.offset(3, 1, 0);
            if (level.getBlockEntity(playerPos) instanceof VideoPlayerBlock.Entity player
                    && level.getBlockEntity(plot.offset(3, 1, 3)) instanceof SpeakerBlock.Entity speaker
                    && level.getBlockEntity(plot.offset(0, 1, 2)) instanceof LightingConsoleBlock.Entity aboard) {
                ItemStack disc = new ItemStack(DiscFeature.BLU_RAY.get());
                disc.set(DiscFeature.PLAYLIST.get(), List.copyOf(ground.playlist()));
                player.insert(disc, null);
                speaker.configure(level, playerPos, 1, Streams.Channel.MIX);
                aboard.setAudioSource(playerPos);
                aboard.setVideoSource(playerPos);
                console.setVideoSource(playerPos);
                aboard.refreshLinks();
                aboard.changed();
                console.refreshLinks();
                console.changed();
                return true;
            }
        }
        return false;
    }

    private static void sign(ServerLevel level, BlockPos pos, String first, String second) {
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 0));
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            sign.setText(sign.getFrontText().setMessage(0, Component.literal(first)).setMessage(1, Component.literal(second)), true);
            sign.setChanged();
        }
    }

    private static void overview(ServerPlayer player) {
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(player.serverLevel(), 0.5, 74, 30.5, 180, 18);
    }
}
