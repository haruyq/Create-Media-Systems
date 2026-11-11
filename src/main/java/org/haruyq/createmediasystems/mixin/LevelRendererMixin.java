package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Tells WorldEvents of the client's world changing, through the entry points Sodium also uses. Light-only section
 * rebuilds leave shapes intact.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void create_media_systems$blockChanged(BlockPos pos, boolean reRenderOnMainThread, CallbackInfo ci) {
        WorldEvents.blockChanged(pos);
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void create_media_systems$blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                    CallbackInfo ci) {
        WorldEvents.blocksChanged(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void create_media_systems$sectionsChanged(int x, int y, int z, CallbackInfo ci) {
        WorldEvents.sectionsChanged(x, y, z);
    }

    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void create_media_systems$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        WorldEvents.chunkLoaded(pos.x, pos.z);
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void create_media_systems$levelChanged(ClientLevel level, CallbackInfo ci) {
        WorldEvents.levelChanged();
    }
}
