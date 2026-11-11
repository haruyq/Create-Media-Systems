package org.haruyq.createmediasystems.train.mixin;

import org.haruyq.createmediasystems.train.TrainFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEditPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Create's schedule screen saves only into its own schedule item; let it save announcement schedules too. */
@Mixin(ScheduleEditPacket.class)
public abstract class ScheduleEditPacketMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void create_media_systems$saveAnnouncementSchedule(ServerPlayer sender, CallbackInfo ci) {
        ItemStack held = sender.getMainHandItem();
        if (!held.is(TrainFeature.ANNOUNCEMENT_SCHEDULE.get())) {
            return;
        }
        Schedule schedule = ((ScheduleEditPacket) (Object) this).schedule();
        if (schedule.entries.isEmpty()) {
            held.remove(AllDataComponents.TRAIN_SCHEDULE);
        } else {
            held.set(AllDataComponents.TRAIN_SCHEDULE, schedule.write(sender.registryAccess()));
        }
        ci.cancel();
    }
}
