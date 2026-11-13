package org.haruyq.createmediasystems.blocks.train;


import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.observer.TrackObserver;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Draws the detector's marker on its track, like Create's Train Observer. */
public final class TrainDetectorRenderer extends SmartBlockEntityRenderer<TrainDetectorBlock.Entity> {
    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(TrainFeature.TRAIN_DETECTOR_ENTITY.get(), TrainDetectorRenderer::new));
    }

    TrainDetectorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(TrainDetectorBlock.Entity detector, float partialTicks, PoseStack poseStack,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(detector, partialTicks, poseStack, buffer, light, overlay);
        TrackTargetingBehaviour<TrackObserver> target = detector.edgePoint;
        BlockPos track = target.getGlobalPosition();
        if (!(detector.getLevel().getBlockState(track).getBlock() instanceof ITrackBlock)) {
            return;
        }
        poseStack.pushPose();
        TransformStack.of(poseStack).translate(track.subtract(detector.getBlockPos()));
        TrackTargetingBehaviour.render(detector.getLevel(), track, target.getTargetDirection(), target.getTargetBezier(),
                poseStack, buffer, light, overlay, RenderedTrackOverlayType.OBSERVER, 1);
        poseStack.popPose();
    }
}
