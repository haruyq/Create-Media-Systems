package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ControlDrag;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.instrument.InstrumentFeature;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock.Knob;

final class MetalZoneClient implements BlockEntityRenderer<MetalZoneBlock.Entity> {
    private static final ModelResourceLocation TREAD = part("tread");
    private static final ModelResourceLocation FULL = part("knob_full");
    private static final ModelResourceLocation OUTER = part("knob_outer");
    private static final ModelResourceLocation INNER = part("knob_inner");
    private static final ModelResourceLocation CHECK_ON = part("check_on");
    private static final ModelResourceLocation CHECK_OFF = part("check_off");
    private static final List<ModelResourceLocation> PARTS = List.of(TREAD, FULL, OUTER, INNER, CHECK_ON, CHECK_OFF);
    private static final Knob[] KNOBS = Knob.values();
    private static final Map<MetalZoneBlock.Entity, KnobPose> POSES = new WeakHashMap<>();
    private static final float MODEL_UNIT = 1 / 16f;

    private static ModelResourceLocation part(String name) {
        return ModelResourceLocation.standalone(CreateMediaSystems.id("block/metal_zone/" + name));
    }

    static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(InstrumentFeature.METAL_ZONE_ENTITY.get(), context -> new MetalZoneClient()));
        bus.addListener((ModelEvent.RegisterAdditional event) -> PARTS.forEach(event::register));
        ControlDrag.onUse(MetalZoneClient::grab);
        WorldEvents.onLevelChanged(POSES::clear);
    }

    @Override
    public void render(MetalZoneBlock.Entity pedal, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        if (pedal.getLevel() == null) return;
        var state = pedal.getBlockState();
        int above = LevelRenderer.getLightColor(pedal.getLevel(), pedal.getBlockPos().above());
        long now = System.nanoTime();
        var controls = POSES.computeIfAbsent(pedal, KnobPose::new);
        controls.advance(pedal, now);
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-state.getValue(MetalZoneBlock.FACING).toYRot()));
        pose.translate(-.5, 0, -.5);

        pose.pushPose();
        pose.translate(0, 3.58 * MODEL_UNIT, 5.48 * MODEL_UNIT);
        pose.mulPose(Axis.XP.rotationDegrees(pressAngle(pedal.pressedAt(), now)));
        pose.translate(0, -3.58 * MODEL_UNIT, -5.48 * MODEL_UNIT);
        renderPart(TREAD, pose, buffers, above, overlay);
        pose.popPose();

        for (Knob knob : KNOBS) {
            var cap = switch (knob.cap) {
                case FULL -> FULL;
                case OUTER -> OUTER;
                case INNER -> INNER;
            };
            pose.pushPose();
            pose.translate(knob.x * MODEL_UNIT, 0, MetalZoneBlock.KNOB_Z * MODEL_UNIT);
            pose.mulPose(Axis.YP.rotationDegrees(135 - 2.7f * controls.positions[knob.ordinal()]));
            pose.translate(-.5, 0, -.5);
            renderPart(cap, pose, buffers, above, overlay);
            pose.popPose();
        }
        boolean active = state.getValue(BlockStateProperties.LIT);
        renderPart(active ? CHECK_ON : CHECK_OFF, pose, buffers, active ? Mesh.FULL_BRIGHT : above, overlay);
        pose.popPose();
    }

    private static void renderPart(ModelResourceLocation id, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var mc = Minecraft.getInstance();
        mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()),
                null, mc.getModelManager().getModel(id), 1, 1, 1, light, overlay, ModelData.EMPTY, null);
    }

    private static float pressAngle(long pressed, long now) {
        if (pressed == 0) return 0;
        double elapsed = (now - pressed) / 1e9;
        float travel = (float) Math.min(elapsed / .030, (0.158 - elapsed) / .128);
        return 2.75f * InstrumentAnimation.smooth(travel);
    }

    private static boolean grab() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || mc.player.isSpectator()
                || !mc.player.getMainHandItem().isEmpty() || !mc.player.getOffhandItem().isEmpty()
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof MetalZoneBlock.Entity pedal)) return false;
        var look = ControlDrag.look(hit.getBlockPos());
        var state = pedal.getBlockState();
        var aimed = state.getShape(mc.level, hit.getBlockPos()).clip(look.eye(),
                look.eye().add(look.along().scale(mc.player.blockInteractionRange() + .1)), hit.getBlockPos());
        if (aimed == null) return false;
        var local = MetalZoneBlock.modelPoint(hit.getBlockPos(), state.getValue(MetalZoneBlock.FACING), aimed.getLocation());
        var knob = MetalZoneBlock.knobAt(local);
        if (knob == null) return false;
        if (mc.options.keyShift.isDown()) knob = knob.outer();
        var drag = new Turn(pedal, knob, pedal.settings().value(knob), heading(look));
        ControlDrag.hold(drag);
        drag.readout();
        return true;
    }

    private static float heading(ControlDrag.Look look) {
        var direction = look.along();
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    private record Turn(MetalZoneBlock.Entity pedal, Knob knob, int initial, float heading) implements ControlDrag.Held {
        @Override public BlockPos pos() {
            return pedal.getBlockPos();
        }

        @Override public void drag(ControlDrag.Look look) {
            var mc = Minecraft.getInstance();
            if (mc.level.getBlockEntity(pos()) != pedal) return;
            int position = Math.round(initial + 2.3f * Mth.wrapDegrees(MetalZoneClient.heading(look) - heading));
            pedal.turn(knob, position);
            readout();
        }

        void readout() {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(knob.label + "  " + pedal.settings().readout(knob)), true);
        }

        @Override public void release(boolean gone) {
            var mc = Minecraft.getInstance();
            int position = pedal.settings().value(knob);
            if (!gone && position != initial && mc.level.getBlockEntity(pos()) == pedal) {
                PacketDistributor.sendToServer(new MetalZoneBlock.SetKnob(pos(), knob.ordinal(), position));
            }
        }
    }

    private static final class KnobPose {
        private final float[] positions = new float[KNOBS.length];
        private long time = System.nanoTime();

        KnobPose(MetalZoneBlock.Entity pedal) {
            for (Knob knob : KNOBS) positions[knob.ordinal()] = pedal.settings().value(knob);
        }

        void advance(MetalZoneBlock.Entity pedal, long now) {
            float blend = (float) -Math.expm1(-Math.max(0, now - time) / 28_000_000.0);
            time = now;
            for (Knob knob : KNOBS) {
                int i = knob.ordinal();
                positions[i] += (pedal.settings().value(knob) - positions[i]) * blend;
            }
        }
    }
}
