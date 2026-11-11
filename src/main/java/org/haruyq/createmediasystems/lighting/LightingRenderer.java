package org.haruyq.createmediasystems.lighting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.client.SurfaceLight;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.display.ScreenLight;
import org.haruyq.createmediasystems.lighting.LightingConsolePage.Area;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.haruyq.createmediasystems.lighting.LightingSettings.Motion;
import org.haruyq.createmediasystems.lighting.LightingSettings.Pattern;

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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class LightingRenderer implements BlockEntityRenderer<StageLightBlock.Entity> {
    static final RenderType CONSOLE_FRAME = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/lighting_console_frame.png"));
    static final RenderType CONSOLE_PLATE = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/lighting_console_plate.png"));
    static final RenderType CONSOLE_CASING = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/lighting_console_casing.png"));
    private static final RenderType LIGHT = RenderType.create("create_media_systems_stage_light",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    public static final RenderType SCREEN = RenderType.create("create_media_systems_lighting_controls",
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
    /** How often (in frames) a beam out of view is traced again, to see whether it has swung into view. */
    private static final int OFF_SCREEN_FRAMES = 8;
    /** Blocks round a beam's ends its light may still reach, for whether it is in view. */
    private static final double OFF_SCREEN_REACH = 6;
    private static final int SIDES = 16;
    private static final int RAYS = 9;

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            LightingIllumination.forgetAll();
            LightingBeams.close();
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                effects(event);
            }
        });
        // Beams given after block entities. Under a shader pack this only queues them, to be added after displays
        // draw their pictures again (see effects).
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                effects(event);
                LightingIllumination.endFrame();
            }
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerBlockEntityRenderer(LightingFeature.STAGE_LIGHT_ENTITY.get(), LightingRenderer::new);
            event.registerBlockEntityRenderer(LightingFeature.LIGHTING_CONSOLE_ENTITY.get(), Console::new);
        });
        modBus.addListener((RegisterShadersEvent event) -> {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("spot_light"),
                        SurfaceLight.FORMAT), LightingIllumination::setShader);
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("stage_beam"),
                        LightingBeams.FORMAT), LightingBeams::setShader);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void effects(RenderLevelStageEvent event) {
        boolean entities = event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (!effectsPending && !LightingIllumination.pending() && !LightingBeams.pending()
                || !entities && event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || IrisCompat.shadowPass()) {
            return;
        }
        // Under a shader pack, beams are added once it has finished the frame and displays have their pictures back,
        // as the light they cast on surfaces is: the same light over all of it, a display's picture included. Through
        // the pack they would be graded as nothing drawn after it is, so brighter over a display than beside it.
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        if (ScreenLight.afterPack(() -> drawEffects(projection, modelView))) {
            return;
        }
        // Finish the last video before light, while keeping light before translucent terrain.
        if (entities) {
            Minecraft.getInstance().renderBuffers().bufferSource().endLastBatch();
        } else {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
        drawEffects(projection, modelView);
    }

    private static void drawEffects(Matrix4f projection, Matrix4f modelView) {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(modelView);
        RenderSystem.applyModelViewMatrix();
        try {
            EFFECT_BUFFERS.values().forEach(MultiBufferSource.BufferSource::endBatch);
            effectsPending = false;
            LightingBeams.draw();
            LightingIllumination.drawSurfaces();
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static VertexConsumer effectBuffer(RenderType type) {
        effectsPending = true;
        return EFFECT_BUFFERS.computeIfAbsent(type,
                ignored -> MultiBufferSource.immediate(new ByteBufferBuilder(65536))).getBuffer(type);
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
        StageLightBlock.Spec spec = lamp.spec();
        LightingSettings.Group group = console == null ? new LightingSettings.Group() : console.settings.groups[lamp.group()];
        boolean enabled = console != null && console.formed && console.settings.enabled;
        double seconds = LightingMotion.seconds(level.getGameTime(), partialTick);
        if (console != null) {
            seconds = AnalysisPlayback.apply(console, seconds);
        }
        LightingMotion motion = MOTION.computeIfAbsent(lamp, ignored -> new LightingMotion());
        float pan = group.pan;
        float tilt = group.tilt;
        double phase = (group.audio && console != null && console.audioSource() != null ? console.motionPhase : seconds)
                * group.speed + lamp.order() * 0.35;
        if (group.motion == Motion.SWEEP) {
            pan += (float) Math.sin(phase) * 55;
        } else if (group.motion == Motion.CIRCLE) {
            pan += (float) Math.sin(phase) * 48;
            tilt += (float) Math.cos(phase) * 22;
        }
        boolean hanging = lamp.getBlockState().getValue(StageLightBlock.HANGING);
        float facing = (float) Math.toRadians(180 - lamp.getBlockState().getValue(StageLightBlock.FACING).toYRot());
        Matrix4f base = new Matrix4f().translate(0.5f, hanging ? 1 : 0, 0.5f).rotateY(facing);
        if (hanging) {
            base.rotateZ((float) Math.PI);
        }
        boolean holdAim = group.motion == Motion.TRACK;
        if (holdAim && group.target != null) {
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
        LightingMotion.Angles angles = motion.update(seconds, pan, LightingSettings.TILT.bounded(tilt), holdAim);
        pan = angles.pan();
        tilt = angles.tilt();
        if (hanging) {
            pan = -pan;
            tilt = -tilt;
        }
        Matrix4f yoke = new Matrix4f(base).rotateY((float) Math.toRadians(-pan));
        Matrix4f head = new Matrix4f(yoke).translate(0, 0.56f, 0).rotateX((float) Math.toRadians(-tilt));
        Matrix4f model = new Matrix4f(pose.last().pose());
        // The console's own brushed frame and riveted casing, so fixtures and console read as one set.
        VertexConsumer metal = new Output(buffers, CONSOLE_FRAME);
        VertexConsumer casing = new Output(buffers, CONSOLE_CASING);
        box(metal, new Matrix4f(model).mul(yoke), -0.39f, 0.13f, -0.13f, -0.29f, 0.66f, 0.13f, 0x9A9CA8, light);
        box(metal, new Matrix4f(model).mul(yoke), 0.29f, 0.13f, -0.13f, 0.39f, 0.66f, 0.13f, 0x9A9CA8, light);
        box(metal, new Matrix4f(model).mul(yoke), -0.35f, 0.13f, -0.13f, 0.35f, 0.22f, 0.13f, 0x9A9CA8, light);
        box(casing, new Matrix4f(model).mul(head), -0.3f, -0.24f, -0.31f, 0.3f, 0.24f, 0.29f, 0xFFFFFF, light);
        box(metal, new Matrix4f(model).mul(head), -0.335f, -0.265f, -0.34f, 0.335f, 0.265f, -0.285f, 0xB1B3BE, light);
        box(metal, new Matrix4f(model).mul(yoke), -0.42f, 0.47f, -0.1f, -0.375f, 0.65f, 0.1f, 0xE5BC55, light);
        box(metal, new Matrix4f(model).mul(yoke), 0.375f, 0.47f, -0.1f, 0.42f, 0.65f, 0.1f, 0xE5BC55, light);
        // A shader pack drawing its shadow map: the body only, no light.
        if (IrisCompat.shadowPass()) {
            return;
        }
        float strength = enabled ? console.settings.master * group.brightness : 0;
        if (group.audio && console != null && console.hasAudio && level.getGameTime() - console.signalAt < 30) {
            float energy = switch (group.audioBand) {
                case BASS -> console.bass;
                case MID -> console.mid;
                case HIGH -> console.high;
                case ALL -> console.audioLevel;
            };
            float floor = group.motion == Motion.TRACK ? spec.trackFloor() : 0.12f;
            strength *= Mth.clamp(floor + energy * 1.5f + console.pulse * 0.35f, 0, 1);
        }
        int count = console == null ? 1 : Math.max(1, console.groupSize(lamp.group()));
        boolean video = console != null && console.hasVideo && level.getGameTime() - console.signalAt < 30;
        int color = LightingSettings.color(group, lamp.order(), count, seconds,
                console == null ? 0 : console.videoLeft, console == null ? 0 : console.videoRight,
                video);
        if (group.mapping == LightingSettings.Mapping.PER_FIXTURE && !group.videoColors(video)) {
            color = group.colors[Math.floorMod(lamp.individualColor, group.colorCount)];
        }
        VertexConsumer screen = new Output(buffers, SCREEN);
        quad(screen, new Matrix4f(model).mul(head), new Vec3(-spec.lensWidth(), -spec.lensHeight(), -0.342),
                new Vec3(spec.lensWidth(), -spec.lensHeight(), -0.342), new Vec3(spec.lensWidth(), spec.lensHeight(), -0.342),
                new Vec3(-spec.lensWidth(), spec.lensHeight(), -0.342), strength > 0 ? color : 0x163340, 255);
        if (strength <= 0.001f) {
            return;
        }
        Vec3 origin = point(head, new Vec3(0, 0, -0.347));
        Vec3 direction = vector(head, new Vec3(0, 0, -1)).normalize();
        Vec3 right = vector(head, new Vec3(1, 0, 0)).normalize();
        Vec3 up = vector(head, new Vec3(0, 1, 0)).normalize();
        Pattern pattern = spec.pattern(group.pattern);
        BeamFrame frame = new BeamFrame(lamp, origin);
        Trace trace = TRACES.computeIfAbsent(lamp, ignored -> new Trace());
        // A beam out of view (as last traced) is traced again only every few frames, often enough to see it swing
        // back into view; meanwhile neither it nor its light is drawn, nor its light's faces looked at.
        if (level == Minecraft.getInstance().level && !trace.inView(frame)
                && Math.floorMod(LightingIllumination.frame() + lamp.getBlockPos().hashCode(), OFF_SCREEN_FRAMES) != 0) {
            return;
        }
        trace.update(frame, direction, right, up, pattern, group.range, group.spread);
        MultiBufferSource effects = beamBuffers(level, buffers);
        VertexConsumer beam = LightingBeams.available(level) ? null
                : effects == EFFECTS ? effects.getBuffer(LIGHT) : new Output(buffers, LIGHT);
        if (pattern.cone()) {
            LightingIllumination.render(lamp, origin, direction, Math.toRadians(coneSpread(pattern, group.spread)) / 2,
                    group.range, strength, color, trace.intersections[SIDES], frame.rays, model, effects);
            cone(beam, model, frame, trace, color, strength, pattern == Pattern.BEAM);
        } else {
            boolean fixtureColor = pattern == Pattern.LASER_RAY
                    || group.mapping == LightingSettings.Mapping.PER_FIXTURE;
            for (int i = 0; i < trace.ends.length; i++) {
                int rayColor = fixtureColor ? color : LightingSettings.color(group, i, trace.ends.length, seconds,
                        console.videoLeft, console.videoRight, video);
                if (pattern == Pattern.LASER_SHEET && i + 1 < trace.ends.length) {
                    int nextColor = fixtureColor ? color : LightingSettings.color(group, i + 1, trace.ends.length, seconds,
                            console.videoLeft, console.videoRight, video);
                    sheet(beam, model, origin, trace.ends[i], trace.ends[i + 1], rayColor, nextColor, strength,
                            trace.planes[i], trace.planes[i + 1], trace.middle[i]);
                }
                if (pattern != Pattern.LASER_SHEET || i == 0 || i == trace.ends.length - 1) {
                    ray(beam, model, frame, trace.ends[i], rayColor, strength, 0.012f, trace.planes[i]);
                }
            }
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

    /** The spread (degrees) a cone of {@code pattern} opens to: a beam's is held narrow. */
    private static double coneSpread(Pattern pattern, double spread) {
        return pattern == Pattern.BEAM ? Math.min(spread, 2) : spread;
    }

    private static AABB renderBounds(Level level, BlockPos anchor, double margin) {
        AABB local = new AABB(anchor).inflate(margin);
        return level == null ? local : SableCompat.transform(local, point -> SableCompat.toRenderWorld(level, anchor, point));
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

        /**
         * Whether the beam as last traced, from the lamp to where it ends (and the light it casts there, a few blocks
         * round), is in the camera's view; true before it is first traced.
         */
        boolean inView(BeamFrame frame) {
            if (ends.length == 0) {
                return true;
            }
            AABB bounds = new AABB(frame.world(frame.origin), frame.world(frame.origin));
            for (Vec3 end : ends) {
                bounds = bounds.minmax(new AABB(frame.world(end), frame.world(end)));
            }
            return LightingIllumination.inView(bounds.getCenter(),
                    Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize())) / 2 + OFF_SCREEN_REACH);
        }
        Vec3 lastOrigin;
        Vec3 lastForward;
        Vec3 lastRight;
        Pattern lastPattern;
        float lastRange;
        float lastSpread;
        boolean lastMoving;

        void update(BeamFrame frame, Vec3 forward, Vec3 right, Vec3 up,
                    Pattern pattern, float range, float spread) {
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
            boolean cone = pattern.cone();
            int count = cone ? SIDES + 1 : pattern == Pattern.LASER_RAY ? 1 : RAYS;
            if (ends.length != count) {
                ends = new Vec3[count];
                directions = new Vec3[count];
                intersections = new LightingRaycast.Hit[count];
                planes = new LightingGeometry.Plane[count];
            }
            int sectors = cone ? SIDES : pattern == Pattern.LASER_SHEET ? RAYS - 1 : 0;
            if (middle.length != sectors) {
                middle = new LightingGeometry.Plane[sectors];
            }
            double tangent = Math.tan(Math.toRadians(coneSpread(pattern, spread)) / 2);
            for (int i = 0; i < count; i++) {
                Vec3 aim = forward;
                if (cone && i < SIDES) {
                    double angle = Math.PI * 2 * i / SIDES;
                    aim = forward.add(right.scale(Math.cos(angle) * tangent)).add(up.scale(Math.sin(angle) * tangent)).normalize();
                } else if (pattern == Pattern.LASER_FAN || pattern == Pattern.LASER_SHEET) {
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
                int next = cone ? (i + 1) % SIDES : i + 1;
                Vec3 aim = directions[i].add(directions[next]).normalize();
                middle[i] = frame.plane(frame.clip(frame.origin, frame.origin.add(aim.scale(range))));
            }
        }
    }

    private static void cone(VertexConsumer out, Matrix4f matrix, BeamFrame frame, Trace trace, int color, float strength,
                             boolean narrow) {
        Vec3 origin = frame.origin;
        Vec3 center = trace.ends[SIDES];
        if (out == null) {
            for (int i = 0; i < SIDES; i++) {
                int next = (i + 1) % SIDES;
                LightingBeams.strip(matrix, origin, origin, trace.ends[next], trace.ends[i], color, color,
                        LightingBeams.Fade.CONE, strength * (narrow ? 38 : 17), trace.planes[i], trace.planes[next],
                        trace.middle[i]);
            }
            ray(null, matrix, frame, center, color, strength * 0.22f, narrow ? 0.025f : 0.055f, trace.planes[SIDES]);
            return;
        }
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
        if (out == null) {
            LightingBeams.strip(matrix, origin, origin, b, a, colorA, colorB, LightingBeams.Fade.SHEET, strength * 26,
                    planeA, planeB, middle);
            return;
        }
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
            if (out == null) {
                LightingBeams.strip(matrix, origin.subtract(edge), origin.add(edge), end.add(edge), end.subtract(edge),
                        color, color, LightingBeams.Fade.SOLID, alpha, center, left, right);
                continue;
            }
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
    public static final class Output implements VertexConsumer {
        private final MultiBufferSource buffers;
        private final RenderType type;
        private VertexConsumer current;
        private int vertices;

        public Output(MultiBufferSource buffers, RenderType type) {
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
            if (!console.formed || console.controller() != console || IrisCompat.shadowPass()) {
                return;
            }
            AnalysisPlayback.apply(console, 0);
            Matrix4f matrix = new Matrix4f(pose.last().pose()).translate(0.5f, 0, 0.5f)
                    .rotateY((float) Math.toRadians(180 - console.getBlockState().getValue(LightingConsoleBlock.FACING).toYRot()))
                    .scale(-1, 1, 1)
                    .translate(-0.5f, 0, -0.5f);
            VertexConsumer metal = new Output(buffers, CONSOLE_FRAME);
            VertexConsumer plate = new Output(buffers, CONSOLE_PLATE);
            VertexConsumer ink = new Output(buffers, SCREEN);
            var settings = console.settings;
            var selected = settings.selected();
            var hovered = LightingControls.hovered(console);
            var activated = LightingControls.activated(console);
            for (int bay = 0; bay < ConsoleLayout.BAYS; bay++) {
                deck(plate, metal, matrix, bay, light);
                monitor(ink, matrix, bay, 0.055f, 0.945f, ConsoleLayout.MONITOR_BOTTOM, ConsoleLayout.MONITOR_TOP, 0x0B101C, 0.006f);
                monitor(ink, matrix, bay, 0.43f, 0.57f, 1.9f, 1.93f, settings.enabled ? LightingText.ON : LightingText.OFF);
            }
            box(metal, matrix, -0.035f, 0.76f, 0, 0.04f, 1.08f, ConsoleLayout.ROWS, 0xB1B3BE, light);
            box(metal, matrix, ConsoleLayout.BAYS - 0.04f, 0.76f, 0, ConsoleLayout.BAYS + 0.035f, 1.08f, ConsoleLayout.ROWS,
                    0xB1B3BE, light);
            for (int bay = 0; bay < ConsoleLayout.BAYS; bay++) {
                panel(ink, matrix, bay + 0.05f, 0.015f, bay + 0.95f, 0.135f, 0x0B101C);
            }
            // The left bay, group faders and group list, is the console's own, unless a page takes it too: the right
            // three are another mod's page while one is active.
            LightingConsolePage page = LightingConsolePage.showing(console);
            if (page == null || !page.ownsLeftBay()) {
                for (int i = 0; i < LightingSettings.GROUPS; i++) {
                    float x = ConsoleLayout.groupFaderX(i);
                    fader(metal, ink, matrix, x, settings.groups[i].brightness, settings.groups[i].colors[0], light);
                    topText(buffers, matrix, x - 0.035f, 1.46f, Integer.toString(i + 1), LightingText.groupColor(settings, i), 0.07f, 0);
                    if (i == settings.selectedGroup) {
                        panelBorder(ink, matrix, x - 0.065f, 0.16f, x + 0.065f, 1.3f, 0.012f, 0xDDF8FF);
                    }
                    Area row = ConsoleLayout.row(0, i);
                    monitor(ink, matrix, 0, row.left(), row.right(), row.bottom(), row.top(),
                            i == settings.selectedGroup ? 0x234255 : 0x111B29, 0.003f);
                    monitor(ink, matrix, 0, 0.08f, 0.115f, row.top() - 0.095f, row.top() - 0.02f, settings.groups[i].colors[0]);
                    text(buffers, matrix, 0.14f, row.top() - 0.015f, LightingText.group(settings, i).append("  ")
                            .append(LightingPackets.Control.BRIGHTNESS.format(new LightingPackets.View(console, i, false,
                                    false, false))), 0.76f);
                }
                topText(buffers, matrix, 0.08f, 0.12f, Component.translatable("create_media_systems.lighting.group", settings.selectedGroup + 1).getString(),
                        LightingText.groupColor(settings, settings.selectedGroup), 0.84f, 0);
            }
            if (page != null) {
                page.render(console, new LightingConsoleSurface(buffers, matrix, light));
            } else {
                boolean custom = LightingControls.customPresets(console);
                for (int i = 0; i < LightingSettings.PRESETS; i++) {
                    float x = ConsoleLayout.presetX(i);
                    float z = ConsoleLayout.presetZ(i);
                    boolean active = activated != null && activated.control() == Control.PRESET && activated.index() == i;
                    boolean saved = custom && console.presets[i] != null;
                    float depth = active ? -0.018f : 0;
                    int color = active ? 0xFFE3A0 : custom ? saved ? 0x91D9E9 : 0x34465C : LightingSettings.Preset.values()[i].cue;
                    box(metal, matrix, x - 0.105f, ConsoleLayout.deckHeight(z), z - 0.11f, x + 0.105f,
                            ConsoleLayout.deckHeight(z) + 0.05f + depth, z + 0.11f, 0xAFB2C1, light);
                    panel(ink, matrix, x - 0.1f, z - 0.105f, x + 0.1f, z + 0.105f, color, depth);
                    topText(buffers, matrix, x - 0.09f, z + 0.04f, LightingControls.presetName(console, i).getString(),
                            custom && !saved && !active ? 0xE6F1FF : 0x101923, 0.18f, depth);
                    if (LightingControls.saving(console)) {
                        panelBorder(ink, matrix, x - 0.105f, z - 0.11f, x + 0.105f, z + 0.11f, 0.01f, 0xFFC75C);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    float x = ConsoleLayout.bankX(i);
                    boolean selectedBank = i < 2 && custom == (i == 1);
                    boolean saving = i == 2 && LightingControls.saving(console);
                    panel(ink, matrix, x, 1.22f, x + 0.24f, 1.48f, saving ? 0xFFC75C : selectedBank ? 0x91D9E9 : 0x24374C);
                    String key = i == 2 ? saving ? "preset_cancel" : "preset_save" : "preset_bank." + i;
                    topText(buffers, matrix, x + 0.015f, 1.39f, Component.translatable("create_media_systems.lighting." + key).getString(),
                            saving || selectedBank ? 0x101923 : 0xE6F1FF, 0.21f, 0);
                }
                float masterX = ConsoleLayout.MASTER_X;
                panel(ink, matrix, masterX - 0.03f, 0.23f, masterX + 0.03f, 1.23f, 0x0B0D14);
                float masterZ = ConsoleLayout.faderZ(settings.master);
                box(metal, matrix, masterX - 0.06f, ConsoleLayout.deckHeight(masterZ), masterZ - 0.065f, masterX + 0.06f,
                        ConsoleLayout.deckHeight(masterZ) + 0.11f, masterZ + 0.065f, 0xE0E5F1, light);
                faderCap(ink, matrix, masterX, masterZ, 0xF0F4FF);
                topText(buffers, matrix, masterX - 0.06f, 1.46f, Component.translatable("create_media_systems.lighting.master_short").getString(),
                        0xE6F1FF, 0.12f, 0);
                int current = selected.color(settings.selectedColor);
                int nearest = nearest(current);
                for (int row = 0; row < ConsoleLayout.PALETTE_CELLS; row++) {
                    for (int col = 0; col < ConsoleLayout.PALETTE_CELLS; col++) {
                        float x0 = ConsoleLayout.paletteX(col);
                        float z0 = ConsoleLayout.paletteZ(row);
                        int color = LightingSettings.palette(col, row);
                        panel(ink, matrix, x0 + 0.004f, z0 + 0.004f, x0 + 0.101f, z0 + 0.121f, color);
                        if (row * ConsoleLayout.PALETTE_CELLS + col == nearest) {
                            panelBorder(ink, matrix, x0, z0, x0 + ConsoleLayout.CELL_WIDTH, z0 + ConsoleLayout.CELL_DEPTH,
                                    0.008f, 0xFFFFFF);
                        }
                    }
                }
                int[] accents = {0xED525A, 0x56D76A, 0x4A89FF};
                float[] values = {LightingSettings.RANGE.fraction(selected.range),
                        LightingSettings.SPREAD.fraction(selected.spread), LightingSettings.SPEED.fraction(selected.speed)};
                for (int i = 0; i < 3; i++) {
                    float rgb = ConsoleLayout.rgbKnobX(i);
                    float setting = ConsoleLayout.settingKnobX(i);
                    knob(metal, ink, matrix, rgb, ConsoleLayout.KNOB_Z, accents[i], light, (current >> ((2 - i) * 8) & 255) / 255f);
                    knob(metal, ink, matrix, setting, ConsoleLayout.KNOB_Z, 0xB6C3D9, light, values[i]);
                    topText(buffers, matrix, rgb - 0.03f, 1.2f, new String[]{"R", "G", "B"}[i], accents[i], 0.06f, 0);
                    topText(buffers, matrix, setting - 0.11f, 1.2f, Component.translatable("create_media_systems.lighting.label."
                            + ConsoleLayout.SETTING_KNOBS[i].key).getString(), 0xE6F1FF, 0.22f, 0);
                }
                for (int i = 0; i < LightingSettings.COLORS; i++) {
                    Area swatch = ConsoleLayout.slot(Control.SLOT, i).area();
                    monitor(ink, matrix, 0, swatch.left() + 0.007f, swatch.left() + 0.098f, 1.71f, 1.84f,
                            i < selected.colorCount ? selected.colors[i] : LightingSettings.mix(selected.colors[i], 0, 0.8f));
                    if (i == settings.selectedColor) {
                        monitorBorder(ink, matrix, 0, swatch.left(), swatch.right(), swatch.bottom(), swatch.top(), 0xFFFFFF);
                    }
                }
                Area aim = ConsoleLayout.slot(Control.AIM, 0).area();
                panel(ink, matrix, aim.left(), aim.bottom(), aim.right(), aim.top(), 0x102532);
                panelBorder(ink, matrix, aim.left(), aim.bottom(), aim.right(), aim.top(), 0.012f, 0x63DAEE);
                float aimX = ConsoleLayout.aimX(selected.pan);
                float aimZ = ConsoleLayout.aimZ(selected.tilt);
                panel(ink, matrix, aimX - 0.07f, aimZ - 0.009f, aimX + 0.07f, aimZ + 0.009f, 0x7DEBFF);
                panel(ink, matrix, aimX - 0.009f, aimZ - 0.07f, aimX + 0.009f, aimZ + 0.07f, 0x7DEBFF);
                LightingPackets.View shown = LightingPackets.View.shown(console);
                for (ConsoleLayout.Readout readout : ConsoleLayout.READOUTS) {
                    text(buffers, matrix, readout.x(), readout.y(),
                            LightingText.label(readout.control().key, readout.control().readout(shown)), 0.84f);
                }
                monitor(ink, matrix, 2, 0.08f, 0.3f, 1.14f, 1.21f, current);
                text(buffers, matrix, 2.36f, 1.215f, LightingText.text(String.format("#%06X", current), LightingText.readable(current)), 0.55f);
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
            Area area = hit.area();
            if (hit.monitor()) {
                monitorBorder(ink, matrix, 0, area.left(), area.right(), area.bottom(), area.top(), color);
            } else {
                panelBorder(ink, matrix, area.left(), area.bottom(), area.right(), area.top(), 0.016f, color);
            }
        }

        static void deck(VertexConsumer plate, VertexConsumer frame, Matrix4f matrix, int bay, int light) {
            float left = bay + 0.015f;
            float right = bay + 0.985f;
            float back = ConsoleLayout.ROWS;
            Vec3 a = new Vec3(left, ConsoleLayout.deckHeight(0), 0), b = new Vec3(right, ConsoleLayout.deckHeight(0), 0);
            Vec3 c = new Vec3(right, ConsoleLayout.deckHeight(back), back), d = new Vec3(left, ConsoleLayout.deckHeight(back), back);
            metalQuad(plate, matrix, a, b, c, d, 0xFFFFFF, light, Direction.UP);
            metalQuad(frame, matrix, new Vec3(left, 0.74, 0), new Vec3(right, 0.74, 0), b, a, 0xCDD1D7, light, Direction.NORTH);
            metalQuad(frame, matrix, new Vec3(right, 0.74, back), new Vec3(left, 0.74, back), d, c, 0xCDD1D7, light, Direction.SOUTH);
            metalQuad(frame, matrix, new Vec3(left, 0.74, back), new Vec3(left, 0.74, 0), a, d, 0xCDD1D7, light, Direction.WEST);
            metalQuad(frame, matrix, new Vec3(right, 0.74, 0), new Vec3(right, 0.74, back), c, b, 0xCDD1D7, light, Direction.EAST);
        }

        static void text(MultiBufferSource buffers, Matrix4f matrix, float x, float y, Component text, float width) {
            Font font = Minecraft.getInstance().font;
            float scale = Math.min(0.0105f, width / Math.max(1, font.width(text)));
            Matrix4f at = new Matrix4f(matrix).translate(x, y, (float) ConsoleLayout.monitorZ(y) - 0.006f)
                    .rotateX((float) Math.atan(ConsoleLayout.MONITOR_LEAN)).scale(scale, -scale, scale);
            font.drawInBatch(text.getVisualOrderText(), 0, 0, 0xFFFFFFFF, false, at, buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }

        static void topText(MultiBufferSource buffers, Matrix4f matrix, float x, float z, String text, int color,
                                    float width, float depth) {
            Font font = Minecraft.getInstance().font;
            float scale = Math.min(0.0085f, width / Math.max(1, font.width(text)));
            Matrix4f at = new Matrix4f(matrix).translate(x, ConsoleLayout.deckHeight(z) + 0.08f + depth, z)
                    .rotateX((float) (-Math.PI / 2 - ConsoleLayout.deckAngle())).scale(scale);
            font.drawInBatch(text, 0, 0, color | 0xFF000000, false, at, buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }

        private static int distance(int a, int b) {
            int r = (a >> 16 & 255) - (b >> 16 & 255);
            int g = (a >> 8 & 255) - (b >> 8 & 255);
            int blue = (a & 255) - (b & 255);
            return r * r + g * g + blue * blue;
        }

        /** The palette cell (row * PALETTE_CELLS + column) nearest {@code color}. */
        private static int nearest(int color) {
            int cells = ConsoleLayout.PALETTE_CELLS;
            int result = 0;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < cells * cells; i++) {
                int distance = distance(color, LightingSettings.palette(i % cells, i / cells));
                if (distance < best) {
                    best = distance;
                    result = i;
                }
            }
            return result;
        }

        /** A group's fader at {@code x}, its cap at {@code value} in {@code color}. */
        static void fader(VertexConsumer metal, VertexConsumer ink, Matrix4f matrix, float x, float value, int color,
                          int light) {
            panel(ink, matrix, x - 0.023f, 0.23f, x + 0.023f, 1.23f, 0x0B0D14);
            float z = ConsoleLayout.faderZ(value);
            float y = ConsoleLayout.deckHeight(z);
            box(metal, matrix, x - 0.055f, y + 0.015f, z - 0.065f, x + 0.055f, y + 0.11f, z + 0.065f, color, light);
            faderCap(ink, matrix, x, z, color);
        }

        static void faderCap(VertexConsumer out, Matrix4f matrix, float x, float z, int color) {
            float y = ConsoleLayout.deckHeight(z) + 0.112f;
            quad(out, matrix, new Vec3(x - 0.05f, y, z - 0.055f), new Vec3(x + 0.05f, y, z - 0.055f),
                    new Vec3(x + 0.05f, y, z + 0.055f), new Vec3(x - 0.05f, y, z + 0.055f), color, 255);
        }

        static void panel(VertexConsumer out, Matrix4f matrix, float x0, float z0, float x1, float z1, int color) {
            panel(out, matrix, x0, z0, x1, z1, color, 0);
        }

        static void panel(VertexConsumer out, Matrix4f matrix, float x0, float z0, float x1, float z1, int color,
                                  float depth) {
            double near = ConsoleLayout.deckHeight(z0) + ConsoleLayout.PANEL_LIFT + depth;
            double far = ConsoleLayout.deckHeight(z1) + ConsoleLayout.PANEL_LIFT + depth;
            quad(out, matrix, new Vec3(x0, near, z0), new Vec3(x1, near, z0), new Vec3(x1, far, z1), new Vec3(x0, far, z1),
                    color, 255);
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
            float y = ConsoleLayout.deckHeight(z);
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
            double z0 = ConsoleLayout.monitorZ(y0) + depth;
            double z1 = ConsoleLayout.monitorZ(y1) + depth;
            quad(out, matrix, new Vec3(bay + x0, y0, z0), new Vec3(bay + x1, y0, z0),
                    new Vec3(bay + x1, y1, z1), new Vec3(bay + x0, y1, z1), color, 255);
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
