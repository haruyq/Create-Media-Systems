package org.haruyq.createmediasystems.audio.mixin;

import org.haruyq.createmediasystems.audio.Jukeboxes;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin implements MediaSource {
    @Override
    @Nullable
    public Streams.Source nowPlaying() {
        return Jukeboxes.nowPlaying((JukeboxBlockEntity) (Object) this);
    }

    /** Every way a disc enters or leaves a jukebox (hand, hopper, command) ends in setTheItem. */
    @Inject(method = "setTheItem", at = @At("TAIL"))
    private void create_media_systems$restartStream(ItemStack item, CallbackInfo ci) {
        JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level) {
            Jukeboxes.restart(level, self);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private static void create_media_systems$tickStream(Level level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox,
                                          CallbackInfo ci) {
        if (level instanceof ServerLevel server) {
            Jukeboxes.tick(server, jukebox);
        }
    }
}
