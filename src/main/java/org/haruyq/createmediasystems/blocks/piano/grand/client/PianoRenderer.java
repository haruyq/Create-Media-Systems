package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.GrandPianoBlock;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoBlockEntity;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoFeature;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

final class PianoRenderer implements BlockEntityRenderer<PianoBlockEntity> {
    private static final float OPEN_ANGLE = 43;
    private static final PianoState REST = new PianoState();
    private final Map<PianoBlockEntity, Lid> lids = new WeakHashMap<>();

    static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(PianoFeature.PIANO_ENTITY.get(), context -> new PianoRenderer()));
        bus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    var mc = Minecraft.getInstance();
                    renderer = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                        @Override
                        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                MultiBufferSource buffers, int light, int overlay) {
                            float lid = context == ItemDisplayContext.GUI ? 32 : 0;
                            pose.pushPose();
                            pose.translate(.19, .26, .025);
                            pose.scale(.34f, .34f, .34f);
                            new PianoMesh(pose, buffers, light).draw(REST, lid);
                            pose.popPose();
                        }
                    };
                }
                return renderer;
            }
        }, PianoFeature.PIANO_ITEM.get()));
    }

    @Override
    public void render(PianoBlockEntity piano, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var state = piano.renderedState();
        float target = state.getValue(GrandPianoBlock.OPEN) ? OPEN_ANGLE : 0;
        float lid = lids.computeIfAbsent(piano, key -> new Lid(target)).move(target);
        pose.pushPose();
        var right = GrandPianoBlock.right(state);
        var up = GrandPianoBlock.up(state);
        var back = state.getValue(GrandPianoBlock.FACING).getOpposite();
        pose.translate(.5, .5, .5);
        pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(
                right.getStepX(), right.getStepY(), right.getStepZ(),
                up.getStepX(), up.getStepY(), up.getStepZ(),
                back.getStepX(), back.getStepY(), back.getStepZ())));
        pose.translate(-.5, -.5, -.5);
        if (state.getValue(GrandPianoBlock.FULL_SIZE)) {
            var origin = GrandPianoBlock.modelToLocal(Vec3.ZERO, true);
            pose.translate(origin.x, origin.y, origin.z);
            float scale = GrandPianoBlock.FULL_SCALE;
            pose.scale(scale, scale, scale);
        }
        new PianoMesh(pose, buffers, light).draw(PianoClient.keys(piano.target()), lid, PianoClient.motion(piano.target()));
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(PianoBlockEntity piano) {
        var pos = piano.getBlockPos();
        var state = piano.renderedState();
        var bounds = new AABB(pos);
        for (var part : GrandPianoBlock.parts(state)) bounds = bounds.minmax(new AABB(part.at(pos, state)));
        return bounds.inflate(state.getValue(GrandPianoBlock.FULL_SIZE) ? 2.2 : 1.1);
    }

    private static final class Lid {
        private float angle;
        private long lastRender = System.nanoTime();

        Lid(float angle) { this.angle = angle; }

        float move(float target) {
            long now = System.nanoTime();
            float step = Math.min(.4f, (now - lastRender) * 1e-9f) * 130;
            angle = Mth.approach(angle, target, step);
            lastRender = now;
            return angle;
        }
    }
}
