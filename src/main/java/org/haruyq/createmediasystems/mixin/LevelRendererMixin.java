package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Tells stage lights which blocks changed, so they trace their light again only there (LightingIllumination). Every
 * entry point is hooked, not just setSectionDirty: Sodium overwrites setBlockDirty and the others to schedule its own
 * rebuilds, and never calls setSectionDirty for a block change.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void create_media_systems$sectionChanged(int x, int y, int z, boolean reRenderOnMainThread, CallbackInfo ci) {
        LightingIllumination.sectionChanged(x, y, z);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void create_media_systems$blockChanged(BlockPos pos, boolean reRenderOnMainThread, CallbackInfo ci) {
        LightingIllumination.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void create_media_systems$blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                    CallbackInfo ci) {
        LightingIllumination.blocksChanged(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void create_media_systems$sectionsChanged(int x, int y, int z, CallbackInfo ci) {
        LightingIllumination.blocksChanged((x - 1) << 4, (y - 1) << 4, (z - 1) << 4,
                (x + 1) << 4 | 15, (y + 1) << 4 | 15, (z + 1) << 4 | 15);
    }

    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void create_media_systems$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        LightingIllumination.chunkChanged(pos.x, pos.z);
    }
}
