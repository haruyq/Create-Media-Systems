package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.display.DisplayLight;
import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Tracks block changes through the entry points Sodium also uses. Light-only section rebuilds leave shapes intact.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void create_media_systems$blockChanged(BlockPos pos, boolean reRenderOnMainThread, CallbackInfo ci) {
        LightingIllumination.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        DisplayLight.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void create_media_systems$blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                    CallbackInfo ci) {
        LightingIllumination.blocksChanged(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
        DisplayLight.blocksChanged(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void create_media_systems$sectionsChanged(int x, int y, int z, CallbackInfo ci) {
        LightingIllumination.blocksChanged((x - 1) << 4, (y - 1) << 4, (z - 1) << 4,
                (x + 1) << 4 | 15, (y + 1) << 4 | 15, (z + 1) << 4 | 15);
        DisplayLight.blocksChanged((x - 1) << 4, (y - 1) << 4, (z - 1) << 4,
                (x + 1) << 4 | 15, (y + 1) << 4 | 15, (z + 1) << 4 | 15);
    }

    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void create_media_systems$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        LightingIllumination.chunkChanged(pos.x, pos.z);
        DisplayLight.chunkChanged(pos.x, pos.z);
    }
}
