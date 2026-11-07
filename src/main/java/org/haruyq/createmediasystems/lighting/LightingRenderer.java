package org.haruyq.createmediasystems.lighting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.display.ScreenLight;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.companion.SableCompanion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class LightingRenderer implements BlockEntityRenderer<StageLightBlock.Entity> {
    private static final ResourceLocation METAL = CreateMediaSystems.id("textures/block/blu_ray_player_side.png");
    static final RenderType SOLID = RenderType.entityCutoutNoCull(METAL);
    static final RenderType CONSOLE_FRAME = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/lighting_console_frame.png"));
    static final RenderType CONSOLE_PLATE = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/lighting_console_plate.png"));
    private static final RenderType LIGHT = RenderType.create("create_media_systems_stage_light",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    static final RenderType SCREEN = RenderType.create("create_media_systems_lighting_controls",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final Map<StageLightBlock.Entity, Trace> TRACES = new WeakHashMap<>();
    private static final Map<StageLightBlock.Entity, LightingMotion> MOTION = new WeakHashMap<>();
    private static final Map<RenderType, MultiBufferSource.BufferSource> EFFECT_BUFFERS = new LinkedHashMap<>();
    private static final MultiBufferSource EFFECTS = LightingRenderer::effectBuffer;
    private static boolean effectsPending;
    private static final MethodHandle SHADOW_PASS = shadowMethod();
    private static boolean shaderApiFailed;
    private static final int SIDES = 16;
    private static final int RAYS = 9;

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> LightingIllumination.forgetAll());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                effects(event);
            }
        });
        // Before displays draw their pictures again over a shader pack's frame (DisplayRenderer.overdraw), which adds
        // this light back onto them: drawn after, it would be added twice.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                effects(event);
                LightingIllumination.endFrame();
            }
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerBlockEntityRenderer(CreateMediaSystems.STAGE_LIGHT_ENTITY.get(), LightingRenderer::new);
            event.registerBlockEntityRenderer(CreateMediaSystems.LIGHTING_CONSOLE_ENTITY.get(), Console::new);
        });
        modBus.addListener((RegisterShadersEvent event) -> {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("spot_light"),
                        LightingIllumination.FORMAT), LightingIllumination::setShader);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void effects(RenderLevelStageEvent event) {
        boolean entities = event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (!effectsPending && !LightingIllumination.pending()
                || !entities && event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || shadowPass()) {
            return;
        }
        // Finish the last video before light, while keeping light before translucent terrain and shader compositing.
        if (entities) {
            Minecraft.getInstance().renderBuffers().bufferSource().endLastBatch();
        } else {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            EFFECT_BUFFERS.values().forEach(MultiBufferSource.BufferSource::endBatch);
            effectsPending = false;
            LightingIllumination.drawSurfaces();
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static VertexConsumer effectBuffer(RenderType type) {
        effectsPending = true;
        return ScreenLight.alsoOnScreens(type, EFFECT_BUFFERS.computeIfAbsent(type,
                ignored -> MultiBufferSource.immediate(new ByteBufferBuilder(65536))).getBuffer(type));
    }

    LightingRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * For other mods' fixtures: where to draw beams (in {@link #beamType()}) this frame. In the client's world, with
     * the stage lights' beams and lit faces, after block entities; elsewhere (a Ponder scene), {@code buffers}.
     */
    public static MultiBufferSource beamBuffers(Level level, MultiBufferSource buffers) {
        return level == Minecraft.getInstance().level ? EFFECTS : buffers;
    }

    /** Additive, unlit, position and colour: a stage light's beams. */
    public static RenderType beamType() {
        return LIGHT;
    }

    /** Opaque, unlit, position and colour: a stage light's lens. */
    public static RenderType lensType() {
        return SCREEN;
    }

    @Override
    public void render(StageLightBlock.Entity lamp, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        var level = lamp.getLevel();
        if (level == null) {
            return;
        }
        var console = lamp.controller();
        LightingSettings.Group group = console == null ? new LightingSettings.Group() : console.settings.groups[lamp.group];
        boolean enabled = console != null && console.formed && console.settings.enabled;
        double seconds = LightingMotion.seconds(level.getGameTime(), partialTick);
        if (console != null) {
            seconds = org.haruyq.createmediasystems.playback.AnalysisPlayback.apply(console, seconds);
        }
        LightingMotion motion = MOTION.computeIfAbsent(lamp, ignored -> new LightingMotion());
        float pan = group.pan;
        float tilt = group.tilt;
        double phase = (group.audio && console != null && console.audioSource != null ? console.motionPhase : seconds)
                * group.speed + lamp.order * 0.35;
        if (group.motion == 1) {
            pan += (float) Math.sin(phase) * 55;
        } else if (group.motion == 2) {
            pan += (float) Math.sin(phase) * 48;
            tilt += (float) Math.cos(phase) * 22;
        }
        boolean hanging = lamp.getBlockState().getValue(StageLightBlock.HANGING);
        float facing = (float) Math.toRadians(180 - lamp.getBlockState().getValue(StageLightBlock.FACING).toYRot());
        Matrix4f base = new Matrix4f().translate(0.5f, hanging ? 1 : 0, 0.5f).rotateY(facing);
        if (hanging) {
            base.rotateZ((float) Math.PI);
        }
        boolean holdAim = group.motion == 3;
        if (group.motion == 3 && group.target != null) {
            Player target = level.getPlayerByUUID(group.target);
            if (target != null) {
                holdAim = false;
                Vec3 targetCenter = SableCompanion.INSTANCE.getEyePositionInterpolated(target, partialTick)
                        .add(0, target.getBbHeight() * 0.5 - target.getEyeHeight(), 0);
                Vec3 at = SableCompat.toRenderBlockSpace(level, lamp.getBlockPos(), targetCenter)
                        .subtract(Vec3.atLowerCornerOf(lamp.getBlockPos()));
                Vector3f local = new Matrix4f().translate(0.5f, hanging ? 0.44f : 0.56f, 0.5f).rotateY(facing)
                        .invert().transformPosition(new Vector3f((float) at.x, (float) at.y, (float) at.z));
                pan = (float) Math.toDegrees(Math.atan2(local.x, -local.z));
                tilt = (float) Math.toDegrees(Math.atan2(-local.y, Math.sqrt(local.x * local.x + local.z * local.z)));
            }
        }
        LightingMotion.Angles angles = motion.update(seconds, pan, Mth.clamp(tilt, -85, 85), holdAim);
        pan = angles.pan();
        tilt = angles.tilt();
        if (hanging) {
            pan = -pan;
            tilt = -tilt;
        }
        Matrix4f yoke = new Matrix4f(base).rotateY((float) Math.toRadians(-pan));
        Matrix4f head = new Matrix4f(yoke).translate(0, 0.56f, 0).rotateX((float) Math.toRadians(-tilt));
        Matrix4f model = new Matrix4f(pose.last().pose());
        VertexConsumer metal = new Output(buffers, SOLID);
        box(metal, new Matrix4f(model).mul(yoke), -0.39f, 0.13f, -0.13f, -0.29f, 0.66f, 0.13f, 0x676A76, light);
        box(metal, new Matrix4f(model).mul(yoke), 0.29f, 0.13f, -0.13f, 0.39f, 0.66f, 0.13f, 0x676A76, light);
        box(metal, new Matrix4f(model).mul(yoke), -0.35f, 0.13f, -0.13f, 0.35f, 0.22f, 0.13f, 0x737785, light);
        box(metal, new Matrix4f(model).mul(head), -0.3f, -0.24f, -0.31f, 0.3f, 0.24f, 0.29f, 0x999DA9, light);
        box(metal, new Matrix4f(model).mul(head), -0.335f, -0.265f, -0.34f, 0.335f, 0.265f, -0.285f, 0xADB3C2, light);
        box(metal, new Matrix4f(model).mul(yoke), -0.42f, 0.47f, -0.1f, -0.375f, 0.65f, 0.1f, 0xE5BC55, light);
        box(metal, new Matrix4f(model).mul(yoke), 0.375f, 0.47f, -0.1f, 0.42f, 0.65f, 0.1f, 0xE5BC55, light);
        if (shadowPass()) {
            return;
        }
        float strength = enabled ? console.settings.master * group.brightness : 0;
        if (group.audio && console != null && console.hasAudio && level.getGameTime() - console.signalAt < 30) {
            float energy = switch (group.audioBand) {
                case 1 -> console.bass;
                case 2 -> console.mid;
                case 3 -> console.high;
                default -> console.audioLevel;
            };
            float floor = group.motion == 3 && !lamp.laser() ? 0.65f : 0.12f;
            strength *= Mth.clamp(floor + energy * 1.5f + console.pulse * 0.35f, 0, 1);
        }
        int count = console == null ? 1 : Math.max(1, console.groupSize(lamp.group));
        boolean video = console != null && console.hasVideo && level.getGameTime() - console.signalAt < 30;
        int color = LightingSettings.color(group, lamp.order, count, seconds,
                console == null ? 0 : console.videoLeft, console == null ? 0 : console.videoRight,
                video);
        if (group.mapping == 3 && !(group.colorMode == 1 && video)) {
            color = group.colors[Math.floorMod(lamp.individualColor, group.colorCount)];
        }
        VertexConsumer screen = new Output(buffers, SCREEN);
        float lensWidth = lamp.laser() ? 0.18f : 0.255f;
        float lensHeight = lamp.laser() ? 0.075f : 0.205f;
        quad(screen, new Matrix4f(model).mul(head), new Vec3(-lensWidth, -lensHeight, -0.342),
                new Vec3(lensWidth, -lensHeight, -0.342), new Vec3(lensWidth, lensHeight, -0.342),
                new Vec3(-lensWidth, lensHeight, -0.342), strength > 0 ? color : 0x163340, 255);
        if (strength <= 0.001f) {
            return;
        }
        Vec3 origin = point(head, new Vec3(0, 0, -0.347));
        Vec3 direction = vector(head, new Vec3(0, 0, -1)).normalize();
        Vec3 right = vector(head, new Vec3(1, 0, 0)).normalize();
        Vec3 up = vector(head, new Vec3(0, 1, 0)).normalize();
        int pattern = lamp.laser() ? Math.max(2, group.pattern) : Math.min(1, group.pattern);
        BeamFrame frame = new BeamFrame(lamp, origin);
        Trace trace = TRACES.computeIfAbsent(lamp, ignored -> new Trace());
        trace.update(frame, direction, right, up, pattern, group.range, group.spread);
        MultiBufferSource effects = level == Minecraft.getInstance().level ? EFFECTS : buffers;
        VertexConsumer beam = effects == EFFECTS ? effects.getBuffer(LIGHT) : new Output(buffers, LIGHT);
        if (pattern < 2) {
            LightingIllumination.render(lamp, origin, direction,
                    Math.toRadians(pattern == 1 ? Math.min(group.spread, 2) : group.spread) / 2,
                    group.range, strength, color, trace.intersections[SIDES], frame.rays, model, effects);
            cone(beam, model, frame, trace, color, strength, pattern == 1);
        } else {
            boolean fixtureColor = pattern == 2 || group.mapping == 3;
            for (int i = 0; i < trace.ends.length; i++) {
                int rayColor = fixtureColor ? color : LightingSettings.color(group, i, trace.ends.length, seconds,
                        console.videoLeft, console.videoRight, video);
                if (pattern == 4 && i + 1 < trace.ends.length) {
                    int nextColor = fixtureColor ? color : LightingSettings.color(group, i + 1, trace.ends.length, seconds,
                            console.videoLeft, console.videoRight, video);
                    sheet(beam, model, origin, trace.ends[i], trace.ends[i + 1], rayColor, nextColor, strength,
                            trace.planes[i], trace.planes[i + 1], trace.middle[i]);
                }
                if (pattern != 4 || i == 0 || i == trace.ends.length - 1) {
                    ray(beam, model, frame, trace.ends[i], rayColor, strength, 0.012f, trace.planes[i]);
                }
            }
        }
    }

    private static MethodHandle shadowMethod() {
        if (!ModList.get().isLoaded("iris")) {
            return null;
        }
        try {
            Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api.getClass(), "isRenderingShadowPass",
                    MethodType.methodType(boolean.class)).bindTo(api);
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Iris's shadow-pass API changed; stage lighting draws without its shadow-pass guard", e);
            return null;
        }
    }

    /** Whether a shader pack is drawing its shadow map: fixtures then draw their bodies only, no light. */
    public static boolean shadowPass() {
        if (SHADOW_PASS == null || shaderApiFailed) {
            return false;
        }
        try {
            return (boolean) SHADOW_PASS.invoke();
        } catch (Throwable e) {
            shaderApiFailed = true;
            LogUtils.getLogger().warn("Iris's shadow-pass API failed; stage lighting draws without its shadow-pass guard", e);
            return false;
        }
    }

    @Override
    public boolean shouldRenderOffScreen(StageLightBlock.Entity lamp) {
        return true;
    }

    @Override
    public boolean shouldRender(StageLightBlock.Entity lamp, Vec3 camera) {
        return lamp.getLevel() != null && SableCompat.toRenderWorld(lamp.getLevel(), lamp.getBlockPos(),
                lamp.getBlockPos().getCenter()).distanceToSqr(worldCamera(lamp.getLevel(), lamp.getBlockPos(), camera))
                <= (double) getViewDistance() * getViewDistance();
    }

    @Override
    public int getViewDistance() {
        return LightingSettings.MAX_RANGE + 32;
    }

    @Override
    public AABB getRenderBoundingBox(StageLightBlock.Entity lamp) {
        return renderBounds(lamp.getLevel(), lamp.getBlockPos(), LightingSettings.MAX_RANGE + 1);
    }

    private static AABB renderBounds(Level level, BlockPos anchor, double margin) {
        AABB local = new AABB(anchor).inflate(margin);
        if (level == null) {
            return local;
        }
        AABB result = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = new Vec3((corner & 1) == 0 ? local.minX : local.maxX,
                    (corner & 2) == 0 ? local.minY : local.maxY, (corner & 4) == 0 ? local.minZ : local.maxZ);
            Vec3 world = SableCompat.toRenderWorld(level, anchor, point);
            result = result == null ? new AABB(world, world) : result.minmax(new AABB(world, world));
        }
        return result;
    }

    private static Vec3 worldCamera(Level level, BlockPos anchor, Vec3 camera) {
        return SableCompanion.INSTANCE.isInPlotGrid(level, camera)
                ? SableCompat.toRenderWorld(level, anchor, camera) : camera;
    }

    private static final class BeamFrame {
        final StageLightBlock.Entity lamp;
        final Vec3 anchor;
        final Vec3 origin;
        final Vec3 basisX;
        final Vec3 basisY;
        final Vec3 basisZ;
        final LightingRaycast rays;

        BeamFrame(StageLightBlock.Entity lamp, Vec3 origin) {
            this.lamp = lamp;
            this.anchor = Vec3.atLowerCornerOf(lamp.getBlockPos());
            this.origin = origin;
            this.rays = new LightingRaycast(lamp.getLevel(), lamp.getBlockPos(), true);
            Vec3 zero = world(Vec3.ZERO);
            basisX = world(new Vec3(1, 0, 0)).subtract(zero);
            basisY = world(new Vec3(0, 1, 0)).subtract(zero);
            basisZ = world(new Vec3(0, 0, 1)).subtract(zero);
        }

        Vec3 world(Vec3 local) {
            return SableCompat.toRenderWorld(lamp.getLevel(), lamp.getBlockPos(), local.add(anchor));
        }

        Vec3 local(Vec3 world) {
            return SableCompat.toRenderBlockSpace(lamp.getLevel(), lamp.getBlockPos(), world).subtract(anchor);
        }

        LightingRaycast.Hit clip(Vec3 from, Vec3 to) {
            return rays.clip(world(from), world(to));
        }

        LightingGeometry.Plane plane(LightingRaycast.Hit hit) {
            if (hit.block().getType() != HitResult.Type.BLOCK) {
                return null;
            }
            Vec3 normal = LightingGeometry.normalToLocal(hit.normal(), basisX, basisY, basisZ);
            return LightingGeometry.Plane.toward(local(hit.point()), normal, origin);
        }
    }

    private static final class Trace {
        Vec3[] ends = new Vec3[0];
        Vec3[] directions = new Vec3[0];
        LightingRaycast.Hit[] intersections = new LightingRaycast.Hit[0];
        LightingGeometry.Plane[] planes = new LightingGeometry.Plane[0];
        LightingGeometry.Plane[] middle = new LightingGeometry.Plane[0];
        /** The frame last traced, and from where: a light held still traces again only when a block changes. */
        long traced = -2;
        Vec3 lastOrigin;
        Vec3 lastForward;
        Vec3 lastRight;
        int lastPattern;
        float lastRange;
        float lastSpread;
        boolean lastMoving;

        void update(BeamFrame frame, Vec3 forward, Vec3 right, Vec3 up,
                    int pattern, float range, float spread) {
            long now = LightingIllumination.frame();
            Vec3 world = frame.world(frame.origin);
            double scale = Math.max(frame.basisX.length(), Math.max(frame.basisY.length(), frame.basisZ.length()));
            boolean moving = SableCompanion.INSTANCE.getContaining(frame.lamp) != null
                    || frame.rays.hasSubLevels(new AABB(world, world).inflate(range * scale));
            // Off the world (a Ponder scene), or with sub-levels that move under it, every frame.
            boolean still = now - traced <= 1 && frame.lamp.getLevel() == Minecraft.getInstance().level
                    && !moving && !lastMoving && !LightingIllumination.blocksChanged()
                    && pattern == lastPattern && range == lastRange && spread == lastSpread
                    && frame.origin.equals(lastOrigin) && forward.equals(lastForward) && right.equals(lastRight);
            traced = now;
            lastMoving = moving;
            if (still) {
                return;
            }
            lastOrigin = frame.origin;
            lastForward = forward;
            lastRight = right;
            lastPattern = pattern;
            lastRange = range;
            lastSpread = spread;
            int count = pattern < 2 ? SIDES + 1 : pattern == 2 ? 1 : RAYS;
            if (ends.length != count) {
                ends = new Vec3[count];
                directions = new Vec3[count];
                intersections = new LightingRaycast.Hit[count];
                planes = new LightingGeometry.Plane[count];
            }
            int sectors = pattern < 2 ? SIDES : pattern == 4 ? RAYS - 1 : 0;
            if (middle.length != sectors) {
                middle = new LightingGeometry.Plane[sectors];
            }
            double tangent = Math.tan(Math.toRadians(pattern == 1 ? Math.min(spread, 2) : spread) / 2);
            for (int i = 0; i < count; i++) {
                Vec3 aim = forward;
                if (pattern < 2 && i < SIDES) {
                    double angle = Math.PI * 2 * i / SIDES;
                    aim = forward.add(right.scale(Math.cos(angle) * tangent)).add(up.scale(Math.sin(angle) * tangent)).normalize();
                } else if (pattern >= 3) {
                    double angle = Math.toRadians(Math.max(8, spread)) * (i / (double) (count - 1) - 0.5);
                    aim = forward.scale(Math.cos(angle)).add(right.scale(Math.sin(angle))).normalize();
                }
                directions[i] = aim;
                Vec3 end = frame.origin.add(aim.scale(range));
                LightingRaycast.Hit hit = frame.clip(frame.origin, end);
                intersections[i] = hit;
                ends[i] = hit.block().getType() == HitResult.Type.BLOCK ? frame.local(hit.point()) : end;
                planes[i] = frame.plane(hit);
            }
            for (int i = 0; i < sectors; i++) {
                int next = pattern < 2 ? (i + 1) % SIDES : i + 1;
                Vec3 aim = directions[i].add(directions[next]).normalize();
                middle[i] = frame.plane(frame.clip(frame.origin, frame.origin.add(aim.scale(range))));
            }
        }
    }

    private static void cone(VertexConsumer out, Matrix4f matrix, BeamFrame frame, Trace trace, int color, float strength,
                             boolean narrow) {
        Vec3 origin = frame.origin;
        Vec3 center = trace.ends[SIDES];
        // Each edge's points from the lamp out, shared by the two strips either side of it.
        Vec3[][] along = new Vec3[SIDES][9];
        for (int i = 0; i < SIDES; i++) {
            for (int step = 0; step <= 8; step++) {
                along[i][step] = origin.lerp(trace.ends[i], step / 8.0);
            }
        }
        for (int i = 0; i < SIDES; i++) {
            Vec3[] edgeA = along[i];
            Vec3[] edgeB = along[(i + 1) % SIDES];
            for (int step = 0; step < 8; step++) {
                double t0 = step / 8.0;
                double t1 = (step + 1) / 8.0;
                int alpha0 = (int) (strength * (narrow ? 38 : 17) * (1 - t0 * 0.75));
                int alpha1 = (int) (strength * (narrow ? 38 : 17) * (1 - t1 * 0.75));
                clippedQuad(out, matrix, edgeA[step], edgeB[step], edgeB[step + 1], edgeA[step + 1],
                        color, color, alpha0, alpha0, alpha1, alpha1,
                        trace.planes[i], trace.planes[(i + 1) % SIDES], trace.middle[i]);
            }
        }
        // A camera-facing inner shaft keeps the beam visible while looking along its cone.
        ray(out, matrix, frame, center, color, strength * 0.22f, narrow ? 0.025f : 0.055f, trace.planes[SIDES]);
    }

    private static void sheet(VertexConsumer out, Matrix4f matrix, Vec3 origin, Vec3 a, Vec3 b,
                              int colorA, int colorB, float strength, LightingGeometry.Plane planeA,
                              LightingGeometry.Plane planeB, LightingGeometry.Plane middle) {
        for (int step = 0; step < 8; step++) {
            double t0 = step / 8.0;
            double t1 = (step + 1) / 8.0;
            int alpha = Math.round(strength * 26 * (1 - step / 11f));
            clippedQuad(out, matrix, origin.lerp(a, t0), origin.lerp(b, t0), origin.lerp(b, t1), origin.lerp(a, t1),
                    colorA, colorB, alpha, alpha, Math.max(1, alpha - 3), Math.max(1, alpha - 3), planeA, planeB, middle);
        }
    }

    private static void ray(VertexConsumer out, Matrix4f matrix, BeamFrame frame, Vec3 end, int color, float strength,
                            float width, LightingGeometry.Plane center) {
        Vec3 origin = frame.origin;
        if (origin.distanceToSqr(end) < 0.000001) {
            return;
        }
        Vector3f cameraLocal = new Matrix4f(matrix).invert().transformPosition(new Vector3f());
        Vec3 along = end.subtract(origin).normalize();
        Vec3 side = along.cross(new Vec3(cameraLocal.x, cameraLocal.y, cameraLocal.z).subtract(origin)).normalize();
        if (side.lengthSqr() < 0.01) {
            side = along.cross(new Vec3(0, 1, 0)).normalize();
        }
        if (side.lengthSqr() < 0.01) {
            side = along.cross(new Vec3(1, 0, 0)).normalize();
        }
        Vec3 widest = side.scale(width * 5);
        LightingGeometry.Plane left = frame.plane(frame.clip(origin.subtract(widest), end.subtract(widest)));
        LightingGeometry.Plane right = frame.plane(frame.clip(origin.add(widest), end.add(widest)));
        for (int layer = 2; layer >= 0; layer--) {
            Vec3 edge = side.scale(width * (1 + layer * 2));
            int alpha = Math.round(strength * (layer == 0 ? 180 : layer == 1 ? 28 : 9));
            clippedQuad(out, matrix, origin.subtract(edge), origin.add(edge), end.add(edge), end.subtract(edge),
                    color, color, alpha, alpha, alpha, alpha, center, left, right);
        }
    }

    private static void clippedQuad(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                                    int colorA, int colorB, int aa, int ab, int ac, int ad, LightingGeometry.Plane first,
                                    LightingGeometry.Plane second, LightingGeometry.Plane third) {
        int outsideFirst = LightingGeometry.outsideCorners(first, a, b, c, d);
        if (outsideFirst == 4) {
            return;
        }
        int outsideSecond = LightingGeometry.outsideCorners(second, a, b, c, d);
        if (outsideSecond == 4) {
            return;
        }
        int outsideThird = LightingGeometry.outsideCorners(third, a, b, c, d);
        if (outsideThird == 4) {
            return;
        }
        if (outsideFirst == 0 && outsideSecond == 0 && outsideThird == 0) {
            coloredVertex(out, matrix, a, colorA, aa);
            coloredVertex(out, matrix, b, colorB, ab);
            coloredVertex(out, matrix, c, colorB, ac);
            coloredVertex(out, matrix, d, colorA, ad);
            return;
        }
        List<LightingGeometry.Vertex> polygon = new ArrayList<>(4);
        polygon.add(new LightingGeometry.Vertex(a, colorA, aa));
        polygon.add(new LightingGeometry.Vertex(b, colorB, ab));
        polygon.add(new LightingGeometry.Vertex(c, colorB, ac));
        polygon.add(new LightingGeometry.Vertex(d, colorA, ad));
        polygon = LightingGeometry.clip(LightingGeometry.clip(LightingGeometry.clip(polygon, first), second), third);
        for (int i = 1; i + 1 < polygon.size(); i++) {
            geometryVertex(out, matrix, polygon.getFirst());
            geometryVertex(out, matrix, polygon.get(i));
            geometryVertex(out, matrix, polygon.get(i + 1));
            geometryVertex(out, matrix, polygon.get(i + 1));
        }
    }

    private static void geometryVertex(VertexConsumer out, Matrix4f matrix, LightingGeometry.Vertex vertex) {
        Vec3 at = vertex.point();
        out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(Math.round(vertex.red()),
                Math.round(vertex.green()), Math.round(vertex.blue()), Mth.clamp(Math.round(vertex.alpha()), 0, 255));
    }

    private static Vec3 point(Matrix4f matrix, Vec3 point) {
        Vector3f result = matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(result.x, result.y, result.z);
    }

    private static Vec3 vector(Matrix4f matrix, Vec3 vector) {
        Vector3f result = matrix.transformDirection(new Vector3f((float) vector.x, (float) vector.y, (float) vector.z));
        return new Vec3(result.x, result.y, result.z);
    }

    private static void coloredVertex(VertexConsumer out, Matrix4f matrix, Vec3 at, int color, int alpha) {
        out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z)
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, Mth.clamp(alpha, 0, 255));
    }

    static void quad(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, int alpha) {
        quad(out, matrix, a, b, c, d, color, alpha, alpha, alpha, alpha);
    }

    static void quad(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color,
                             int aa, int ab, int ac, int ad) {
        coloredVertex(out, matrix, a, color, aa);
        coloredVertex(out, matrix, b, color, ab);
        coloredVertex(out, matrix, c, color, ac);
        coloredVertex(out, matrix, d, color, ad);
    }

    static void box(VertexConsumer out, Matrix4f matrix, float x0, float y0, float z0, float x1, float y1, float z1,
                            int color, int light) {
        if (out instanceof Output output) {
            out = output.buffers.getBuffer(output.type);
        }
        Vec3 a = new Vec3(x0, y0, z0), b = new Vec3(x1, y0, z0), c = new Vec3(x1, y1, z0), d = new Vec3(x0, y1, z0);
        Vec3 e = new Vec3(x0, y0, z1), f = new Vec3(x1, y0, z1), g = new Vec3(x1, y1, z1), h = new Vec3(x0, y1, z1);
        metalQuad(out, matrix, a, b, c, d, color, light, Direction.NORTH);
        metalQuad(out, matrix, f, e, h, g, color, light, Direction.SOUTH);
        metalQuad(out, matrix, e, a, d, h, color, light, Direction.WEST);
        metalQuad(out, matrix, b, f, g, c, color, light, Direction.EAST);
        metalQuad(out, matrix, d, c, g, h, color, light, Direction.UP);
        metalQuad(out, matrix, e, f, b, a, color, light, Direction.DOWN);
    }

    static void metalQuad(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, int light,
                                  Direction normal) {
        Vec3[] vertices = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            Vec3 at = vertices[i];
            out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z)
                    .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255)
                    .setUv(i == 0 || i == 3 ? 0 : 1, i < 2 ? 1 : 0).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light).setNormal(normal.getStepX(), normal.getStepY(), normal.getStepZ());
        }
    }

    /**
     * The buffer for {@code type} asked for again each quad: another type asked for between (text, another part)
     * may have ended its batch. Everything here is drawn in whole quads, 4 vertices together.
     */
    static final class Output implements VertexConsumer {
        private final MultiBufferSource buffers;
        private final RenderType type;
        private VertexConsumer current;
        private int vertices;

        Output(MultiBufferSource buffers, RenderType type) {
            this.buffers = buffers;
            this.type = type;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if ((vertices++ & 3) == 0) {
                current = buffers.getBuffer(type);
            }
            current.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            current.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            current.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            current.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            current.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            current.setNormal(x, y, z);
            return this;
        }
    }

    public static final class Console implements BlockEntityRenderer<LightingConsoleBlock.Entity> {
        Console(BlockEntityRendererProvider.Context context) {
        }

        @Override
        public void render(LightingConsoleBlock.Entity console, float partialTick, PoseStack pose, MultiBufferSource buffers,
                           int light, int overlay) {
            if (!console.formed || console.controller() != console || shadowPass()) {
                return;
            }
            org.haruyq.createmediasystems.playback.AnalysisPlayback.apply(console, 0);
            Matrix4f matrix = new Matrix4f(pose.last().pose()).translate(0.5f, 0, 0.5f)
                    .rotateY((float) Math.toRadians(180 - console.getBlockState().getValue(LightingConsoleBlock.FACING).toYRot()))
                    .scale(-1, 1, 1)
                    .translate(-0.5f, 0, -0.5f);
            VertexConsumer metal = new Output(buffers, CONSOLE_FRAME);
            VertexConsumer plate = new Output(buffers, CONSOLE_PLATE);
            VertexConsumer ink = new Output(buffers, SCREEN);
            var settings = console.settings;
            var selected = settings.groups[settings.selectedGroup];
            var hovered = LightingControls.hovered(console);
            var activated = LightingControls.activated(console);
            for (int bay = 0; bay < 4; bay++) {
                deck(plate, metal, matrix, bay, light);
                monitor(ink, matrix, bay, 0.055f, 0.945f, 1.12f, 1.86f, 0x0B101C, 0.006f);
                monitor(ink, matrix, bay, 0.43f, 0.57f, 1.9f, 1.93f, settings.enabled ? LightingText.ON : LightingText.OFF);
            }
            box(metal, matrix, -0.035f, 0.76f, 0, 0.04f, 1.08f, 2, 0xB1B3BE, light);
            box(metal, matrix, 3.96f, 0.76f, 0, 4.035f, 1.08f, 2, 0xB1B3BE, light);
            for (int i = 0; i < 6; i++) {
                float x = 0.12f + i * 0.145f;
                panel(ink, matrix, x - 0.023f, 0.23f, x + 0.023f, 1.23f, 0x0B0D14);
                float z = 0.25f + settings.groups[i].brightness * 0.95f;
                int color = settings.groups[i].colors[0];
                box(metal, matrix, x - 0.055f, height(z) + 0.015f, z - 0.065f,
                        x + 0.055f, height(z) + 0.11f, z + 0.065f, color, light);
                faderCap(ink, matrix, x, z, color);
                topText(buffers, matrix, x - 0.035f, 1.46f, Integer.toString(i + 1), LightingText.groupColor(settings, i), 0.07f, 0);
                if (i == settings.selectedGroup) {
                    panelBorder(ink, matrix, x - 0.065f, 0.16f, x + 0.065f, 1.3f, 0.012f, 0xDDF8FF);
                }
            }
            for (int i = 0; i < LightingSettings.GROUPS; i++) {
                float top = 1.86f - i * (0.74f / 6);
                monitor(ink, matrix, 0, 0.065f, 0.935f, top - 0.117f, top,
                        i == settings.selectedGroup ? 0x234255 : 0x111B29, 0.003f);
                monitor(ink, matrix, 0, 0.08f, 0.115f, top - 0.095f, top - 0.02f, settings.groups[i].colors[0]);
                text(buffers, matrix, 0.14f, top - 0.015f,
                        LightingText.group(settings, i).append("  ")
                                .append(LightingText.number(Math.round(settings.groups[i].brightness * 100) + "%")), 0.76f);
            }
            for (int bay = 0; bay < 4; bay++) {
                panel(ink, matrix, bay + 0.05f, 0.015f, bay + 0.95f, 0.135f, 0x0B101C);
            }
            topText(buffers, matrix, 0.08f, 0.12f, Component.translatable("create_media_systems.lighting.group", settings.selectedGroup + 1).getString(),
                    LightingText.groupColor(settings, settings.selectedGroup), 0.84f, 0);
            // The left bay is every page's: the right three are another mod's page while one is active.
            LightingConsolePage page = LightingConsolePage.showing(console);
            if (page != null) {
                page.render(console, new LightingConsoleSurface(buffers, matrix, light));
            } else {
                for (int i = 0; i < 6; i++) {
                    float x = 1.15f + i % 3 * 0.27f;
                    float z = 0.35f + i / 3 * 0.5f;
                    int[] cueColors = {0x36C9EE, 0x9F66EF, 0xFFC75C, 0x32D999, 0xEE6C9E, 0x5C8AFF};
                    boolean active = activated != null && activated.control() == Control.PRESET && activated.index() == i;
                    boolean saved = LightingControls.customPresets(console) && console.presets[i] != null;
                    float depth = active ? -0.018f : 0;
                    int color = active ? 0xFFE3A0 : LightingControls.customPresets(console) ? saved ? 0x91D9E9 : 0x34465C : cueColors[i];
                    box(metal, matrix, x - 0.105f, height(z), z - 0.11f, x + 0.105f, height(z) + 0.05f + depth, z + 0.11f,
                            0xAFB2C1, light);
                    panel(ink, matrix, x - 0.1f, z - 0.105f, x + 0.1f, z + 0.105f, color, depth);
                    topText(buffers, matrix, x - 0.09f, z + 0.04f, LightingControls.presetName(console, i).getString(),
                            LightingControls.customPresets(console) && !saved && !active ? 0xE6F1FF : 0x101923, 0.18f, depth);
                    if (LightingControls.saving(console)) {
                        panelBorder(ink, matrix, x - 0.105f, z - 0.11f, x + 0.105f, z + 0.11f, 0.01f, 0xFFC75C);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    float x = 1.04f + i * 0.26f;
                    boolean selectedBank = i < 2 && LightingControls.customPresets(console) == (i == 1);
                    boolean saving = i == 2 && LightingControls.saving(console);
                    panel(ink, matrix, x, 1.22f, x + 0.24f, 1.48f, saving ? 0xFFC75C : selectedBank ? 0x91D9E9 : 0x24374C);
                    String key = i == 2 ? saving ? "preset_cancel" : "preset_save" : "preset_bank." + i;
                    topText(buffers, matrix, x + 0.015f, 1.39f, Component.translatable("create_media_systems.lighting." + key).getString(),
                            saving || selectedBank ? 0x101923 : 0xE6F1FF, 0.21f, 0);
                }
                panel(ink, matrix, 1.85f, 0.23f, 1.91f, 1.23f, 0x0B0D14);
                float masterZ = 0.25f + settings.master * 0.95f;
                box(metal, matrix, 1.82f, height(masterZ), masterZ - 0.065f, 1.94f, height(masterZ) + 0.11f,
                        masterZ + 0.065f, 0xE0E5F1, light);
                faderCap(ink, matrix, 1.88f, masterZ, 0xF0F4FF);
                topText(buffers, matrix, 1.82f, 1.46f, Component.translatable("create_media_systems.lighting.master_short").getString(),
                        0xE6F1FF, 0.12f, 0);
                int nearest = nearest(selected.colors[Math.min(settings.selectedColor, selected.colors.length - 1)]);
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 8; col++) {
                        float x0 = 2.08f + col * 0.105f;
                        float z0 = 0.15f + (7 - row) * 0.125f;
                        int color = LightingSettings.palette(col, row);
                        panel(ink, matrix, x0 + 0.004f, z0 + 0.004f, x0 + 0.101f, z0 + 0.121f, color);
                        if (row * 8 + col == nearest) {
                            panelBorder(ink, matrix, x0, z0, x0 + 0.105f, z0 + 0.125f, 0.008f, 0xFFFFFF);
                        }
                    }
                }
                int[] accents = {0xED525A, 0x56D76A, 0x4A89FF};
                float[] values = {(selected.range - 2) / (LightingSettings.MAX_RANGE - 2), (selected.spread - 1) / 69,
                        (selected.speed - 0.05f) / 3.95f};
                String[] knobs = {"range", "spread", "speed"};
                for (int i = 0; i < 3; i++) {
                    knob(metal, ink, matrix, 2.2f + i * 0.3f, 1.35f, accents[i], light,
                            (selected.colors[settings.selectedColor] >> ((2 - i) * 8) & 255) / 255f);
                    knob(metal, ink, matrix, 3.2f + i * 0.3f, 1.35f, 0xB6C3D9, light, values[i]);
                    topText(buffers, matrix, 2.17f + i * 0.3f, 1.2f, new String[]{"R", "G", "B"}[i], accents[i], 0.06f, 0);
                    topText(buffers, matrix, 3.09f + i * 0.3f, 1.2f, Component.translatable("create_media_systems.lighting.label." + knobs[i]).getString(),
                            0xE6F1FF, 0.22f, 0);
                }
                int current = selected.colors[Math.min(settings.selectedColor, selected.colors.length - 1)];
                for (int i = 0; i < LightingSettings.COLORS; i++) {
                    float x = 0.08f + i * 0.105f;
                    monitor(ink, matrix, 2, x + 0.007f, x + 0.098f, 1.71f, 1.84f,
                            i < selected.colorCount ? selected.colors[i] : LightingSettings.mix(selected.colors[i], 0, 0.8f));
                    if (i == settings.selectedColor) {
                        monitorBorder(ink, matrix, 2, x, x + 0.105f, 1.70f, 1.85f, 0xFFFFFF);
                    }
                }
                panel(ink, matrix, 3.1f, 0.15f, 3.9f, 1.1f, 0x102532);
                panelBorder(ink, matrix, 3.1f, 0.15f, 3.9f, 1.1f, 0.012f, 0x63DAEE);
                float aimX = 3.5f + Mth.clamp(selected.pan / 180, -1, 1) * 0.4f;
                float aimZ = 0.625f + Mth.clamp(selected.tilt / 85, -1, 1) * 0.475f;
                panel(ink, matrix, aimX - 0.07f, aimZ - 0.009f, aimX + 0.07f, aimZ + 0.009f, 0x7DEBFF);
                panel(ink, matrix, aimX - 0.009f, aimZ - 0.07f, aimX + 0.009f, aimZ + 0.07f, 0x7DEBFF);
                Component[] show = {
                        LightingText.label("enabled", LightingText.on(settings.enabled)), LightingText.label("audio", LightingText.on(selected.audio)),
                        LightingText.label("band", LightingText.mode("band", selected.audioBand)), LightingText.label("cycle", LightingText.on(selected.colorCycle)),
                        LightingText.label("pattern", LightingText.mode("pattern", selected.pattern)),
                        LightingText.label("sources", LightingText.text("A ", LightingText.TEXT)
                                .append(LightingText.source(console.audioSource != null, console.hasAudio)).append("  V ")
                                .append(LightingText.source(console.videoSource != null, console.hasVideo)))
                };
                for (int i = 0; i < show.length; i++) {
                    text(buffers, matrix, 1.08f, 1.845f - i * (0.74f / 6), show[i], 0.84f);
                }
                text(buffers, matrix, 2.08f, 1.665f, LightingText.label("count", LightingText.number(selected.colorCount)), 0.84f);
                text(buffers, matrix, 2.08f, 1.505f, LightingText.label("mapping", LightingText.mode("mapping", selected.mapping)), 0.84f);
                text(buffers, matrix, 2.08f, 1.345f, LightingText.label("video", LightingText.on(selected.colorMode == 1)), 0.84f);
                monitor(ink, matrix, 2, 0.08f, 0.3f, 1.14f, 1.21f, current);
                text(buffers, matrix, 2.36f, 1.215f, LightingText.text(String.format("#%06X", current), LightingText.readable(current)), 0.55f);
                text(buffers, matrix, 3.08f, 1.82f, LightingText.label("motion", LightingText.mode("motion", selected.motion)), 0.84f);
                Player target = selected.target == null ? null : console.getLevel().getPlayerByUUID(selected.target);
                text(buffers, matrix, 3.08f, 1.68f, LightingText.label("target", LightingText.text(target == null ? "—" : target.getName(),
                        target == null ? LightingText.MUTED : LightingText.MODE)), 0.84f);
                text(buffers, matrix, 3.08f, 1.49f, LightingText.label("range", LightingText.number(Math.round(selected.range) + " m")), 0.84f);
                text(buffers, matrix, 3.08f, 1.37f, LightingText.label("spread", LightingText.number(Math.round(selected.spread) + "°")), 0.84f);
                text(buffers, matrix, 3.08f, 1.25f, LightingText.label("speed", LightingText.number(String.format(java.util.Locale.ROOT, "%.2f", selected.speed))), 0.84f);
                topText(buffers, matrix, 1.08f, 0.12f, Component.translatable("create_media_systems.lighting."
                        + (LightingControls.saving(console) ? "preset_destination" : "label.preset")).getString(),
                        LightingControls.saving(console) ? LightingText.WARNING : LightingText.MODE, 0.84f, 0);
                topText(buffers, matrix, 2.08f, 0.12f, Component.translatable("create_media_systems.lighting.color_short", settings.selectedColor + 1).getString(),
                        LightingText.readable(current), 0.84f, 0);
                topText(buffers, matrix, 3.08f, 0.12f, Component.translatable("create_media_systems.lighting.aim_short").getString(), LightingText.NUMBER, 0.84f, 0);
            }
            highlight(ink, matrix, hovered, 0x8AEAFF);
            highlight(ink, matrix, activated, 0xFFD074);
        }

        static void highlight(VertexConsumer ink, Matrix4f matrix, LightingControls.Hit hit, int color) {
            if (hit == null) {
                return;
            }
            var area = LightingControls.area(hit);
            if (hit.monitor()) {
                monitorBorder(ink, matrix, 0, area.left(), area.right(), area.bottom(), area.top(), color);
            } else {
                panelBorder(ink, matrix, area.left(), area.bottom(), area.right(), area.top(), 0.016f, color);
            }
        }

        static void deck(VertexConsumer plate, VertexConsumer frame, Matrix4f matrix, int bay, int light) {
            float left = bay + 0.015f;
            float right = bay + 0.985f;
            Vec3 a = new Vec3(left, height(0), 0), b = new Vec3(right, height(0), 0);
            Vec3 c = new Vec3(right, height(2), 2), d = new Vec3(left, height(2), 2);
            metalQuad(plate, matrix, a, b, c, d, 0xFFFFFF, light, Direction.UP);
            metalQuad(frame, matrix, new Vec3(left, 0.74, 0), new Vec3(right, 0.74, 0), b, a, 0xCDD1D7, light, Direction.NORTH);
            metalQuad(frame, matrix, new Vec3(right, 0.74, 2), new Vec3(left, 0.74, 2), d, c, 0xCDD1D7, light, Direction.SOUTH);
            metalQuad(frame, matrix, new Vec3(left, 0.74, 2), new Vec3(left, 0.74, 0), a, d, 0xCDD1D7, light, Direction.WEST);
            metalQuad(frame, matrix, new Vec3(right, 0.74, 0), new Vec3(right, 0.74, 2), c, b, 0xCDD1D7, light, Direction.EAST);
        }

        static void text(MultiBufferSource buffers, Matrix4f matrix, float x, float y, Component text, float width) {
            Font font = Minecraft.getInstance().font;
            float scale = Math.min(0.0105f, width / Math.max(1, font.width(text)));
            Matrix4f at = new Matrix4f(matrix).translate(x, y, (float) screenZ(y) - 0.006f)
                    .rotateX((float) Math.toRadians(22.5)).scale(scale, -scale, scale);
            font.drawInBatch(text.getVisualOrderText(), 0, 0, 0xFFFFFFFF, false, at, buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }

        static void topText(MultiBufferSource buffers, Matrix4f matrix, float x, float z, String text, int color,
                                    float width, float depth) {
            Font font = Minecraft.getInstance().font;
            float scale = Math.min(0.0085f, width / Math.max(1, font.width(text)));
            Matrix4f at = new Matrix4f(matrix).translate(x, height(z) + 0.08f + depth, z)
                    .rotateX((float) (-Math.PI / 2 - Math.atan(0.12))).scale(scale);
            font.drawInBatch(text, 0, 0, color | 0xFF000000, false, at, buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }

        private static int distance(int a, int b) {
            int r = (a >> 16 & 255) - (b >> 16 & 255);
            int g = (a >> 8 & 255) - (b >> 8 & 255);
            int blue = (a & 255) - (b & 255);
            return r * r + g * g + blue * blue;
        }

        private static int nearest(int color) {
            int result = 0;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < 64; i++) {
                int distance = distance(color, LightingSettings.palette(i % 8, i / 8));
                if (distance < best) {
                    best = distance;
                    result = i;
                }
            }
            return result;
        }

        static float height(float z) {
            return 0.78f + z * 0.12f;
        }

        static void faderCap(VertexConsumer out, Matrix4f matrix, float x, float z, int color) {
            float y = height(z) + 0.112f;
            quad(out, matrix, new Vec3(x - 0.05f, y, z - 0.055f), new Vec3(x + 0.05f, y, z - 0.055f),
                    new Vec3(x + 0.05f, y, z + 0.055f), new Vec3(x - 0.05f, y, z + 0.055f), color, 255);
        }

        static void panel(VertexConsumer out, Matrix4f matrix, float x0, float z0, float x1, float z1, int color) {
            panel(out, matrix, x0, z0, x1, z1, color, 0);
        }

        static void panel(VertexConsumer out, Matrix4f matrix, float x0, float z0, float x1, float z1, int color,
                                  float depth) {
            quad(out, matrix, new Vec3(x0, height(z0) + 0.075 + depth, z0), new Vec3(x1, height(z0) + 0.075 + depth, z0),
                    new Vec3(x1, height(z1) + 0.075 + depth, z1), new Vec3(x0, height(z1) + 0.075 + depth, z1), color, 255);
        }

        static void panelBorder(VertexConsumer out, Matrix4f matrix, float x0, float z0, float x1, float z1,
                                        float width, int color) {
            panel(out, matrix, x0, z0, x1, z0 + width, color, 0.002f);
            panel(out, matrix, x0, z1 - width, x1, z1, color, 0.002f);
            panel(out, matrix, x0, z0, x0 + width, z1, color, 0.002f);
            panel(out, matrix, x1 - width, z0, x1, z1, color, 0.002f);
        }

        static void knob(VertexConsumer metal, VertexConsumer ink, Matrix4f matrix, float x, float z, int accent,
                                 int light, float value) {
            float y = height(z);
            box(metal, matrix, x - 0.075f, y, z - 0.06f, x + 0.075f, y + 0.14f, z + 0.06f, 0xD4D8E3, light);
            box(metal, matrix, x - 0.06f, y, z - 0.075f, x + 0.06f, y + 0.14f, z + 0.075f, 0xB9BFCE, light);
            Matrix4f pointer = new Matrix4f(matrix).translate(x, y, z)
                    .rotateY((Mth.clamp(value, 0, 1) - 0.5f) * (float) Math.PI * 1.5f).translate(-x, -y, -z);
            quad(ink, pointer, new Vec3(x - 0.009, y + 0.141, z - 0.065), new Vec3(x + 0.009, y + 0.141, z - 0.065),
                    new Vec3(x + 0.009, y + 0.141, z + 0.015), new Vec3(x - 0.009, y + 0.141, z + 0.015), accent, 255);
        }

        static void monitor(VertexConsumer out, Matrix4f matrix, int bay, float x0, float x1, float y0, float y1,
                                    int color) {
            monitor(out, matrix, bay, x0, x1, y0, y1, color, 0);
        }

        static void monitor(VertexConsumer out, Matrix4f matrix, int bay, float x0, float x1, float y0, float y1,
                                    int color, float depth) {
            quad(out, matrix, new Vec3(bay + x0, y0, screenZ(y0) + depth), new Vec3(bay + x1, y0, screenZ(y0) + depth),
                    new Vec3(bay + x1, y1, screenZ(y1) + depth), new Vec3(bay + x0, y1, screenZ(y1) + depth), color, 255);
        }

        static double screenZ(float y) {
            return 1.455 + (y - 1) * 0.4142;
        }

        static void monitorBorder(VertexConsumer out, Matrix4f matrix, int bay, float x0, float x1, float y0,
                                          float y1, int color) {
            monitor(out, matrix, bay, x0, x1, y0, y0 + 0.01f, color, -0.002f);
            monitor(out, matrix, bay, x0, x1, y1 - 0.01f, y1, color, -0.002f);
            monitor(out, matrix, bay, x0, x0 + 0.01f, y0, y1, color, -0.002f);
            monitor(out, matrix, bay, x1 - 0.01f, x1, y0, y1, color, -0.002f);
        }

        @Override
        public boolean shouldRenderOffScreen(LightingConsoleBlock.Entity console) {
            return true;
        }

        @Override
        public boolean shouldRender(LightingConsoleBlock.Entity console, Vec3 camera) {
            return console.getLevel() != null && SableCompat.toRenderWorld(console.getLevel(), console.getBlockPos(),
                    console.getBlockPos().getCenter()).distanceToSqr(worldCamera(console.getLevel(), console.getBlockPos(), camera))
                    <= (double) getViewDistance() * getViewDistance();
        }

        @Override
        public AABB getRenderBoundingBox(LightingConsoleBlock.Entity console) {
            return renderBounds(console.getLevel(), console.getBlockPos(), 6);
        }

        @Override
        public int getViewDistance() {
            return 96;
        }
    }
}
