package org.haruyq.createmediasystems.lighting.mixin;

import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
    @ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
    private int create_media_systems$stageLight(int original, Entity entity, float partialTick) {
        return LightingIllumination.light(entity, partialTick, original);
    }
}
