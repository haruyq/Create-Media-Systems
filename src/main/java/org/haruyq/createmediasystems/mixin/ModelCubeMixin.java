package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.blocks.display.DisplayLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelPart;

/** A model's box drawn for a display's light is kept as a box for its shadow (DisplayLight.Shape), not drawn. */
@Mixin(ModelPart.Cube.class)
abstract class ModelCubeMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void create_media_systems$shape(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay,
                                            int colour, CallbackInfo ci) {
        if (buffer instanceof DisplayLight.Shape shape) {
            ModelPart.Cube cube = (ModelPart.Cube) (Object) this;
            shape.add(pose.pose(), cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ);
            ci.cancel();
        }
    }
}
