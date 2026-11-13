package org.haruyq.createmediasystems.blocks.train.mixin;

import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.blocks.train.TrainFeature;
import org.haruyq.createmediasystems.blocks.train.AnnounceCondition;
import org.haruyq.createmediasystems.blocks.train.AnnouncementTypes;
import org.haruyq.createmediasystems.blocks.train.StationInstruction;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.schedule.IScheduleInput;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleMenu;
import com.simibubi.create.content.trains.schedule.ScheduleScreen;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;

import net.createmod.catnip.data.Pair;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Create's schedule screen, edited for announcement schedules: only CMS's station and announcement types are
 * offered (and new entries start as them), while train schedules no longer offer CMS's types.
 */
@Mixin(ScheduleScreen.class)
public abstract class ScheduleScreenMixin {
    @Shadow
    private Schedule schedule;

    @Unique
    private boolean create_media_systems$announcement() {
        return ((AbstractContainerScreen<?>) (Object) this).getMenu() instanceof ScheduleMenu menu
                && menu.contentHolder.is(TrainFeature.ANNOUNCEMENT_SCHEDULE.get());
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/simibubi/create/content/trains/schedule/Schedule;INSTRUCTION_TYPES:Ljava/util/List;"))
    private List<Pair<ResourceLocation, Supplier<? extends ScheduleInstruction>>> create_media_systems$instructionTypes() {
        return AnnouncementTypes.filter(Schedule.INSTRUCTION_TYPES, create_media_systems$announcement());
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/simibubi/create/content/trains/schedule/Schedule;CONDITION_TYPES:Ljava/util/List;"))
    private List<Pair<ResourceLocation, Supplier<? extends ScheduleWaitCondition>>> create_media_systems$conditionTypes() {
        return AnnouncementTypes.filter(Schedule.CONDITION_TYPES, create_media_systems$announcement());
    }

    @Redirect(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/schedule/Schedule;getTypeOptions(Ljava/util/List;)Ljava/util/List;"))
    private List<? extends Component> create_media_systems$typeOptions(List<? extends Pair<ResourceLocation, ?>> types) {
        return AnnouncementTypes.labels(types);
    }

    /** New entries and conditions start as Create's defaults (travel to station, wait); make them ours. */
    @ModifyVariable(method = "startEditing", at = @At("HEAD"), argsOnly = true)
    private IScheduleInput create_media_systems$announcementDefaults(IScheduleInput field) {
        if (!create_media_systems$announcement()) {
            return field;
        }
        if (field.getClass() == DestinationInstruction.class) {
            return new StationInstruction();
        }
        return field.getClass() == ScheduledDelay.class ? new AnnounceCondition(AnnounceCondition.Trigger.ARRIVAL) : field;
    }

    /** A new entry also gets a hard-coded wait condition when confirmed; swap it for an announcement too. */
    @Inject(method = "stopEditing", at = @At("TAIL"))
    private void create_media_systems$replaceDefaultWaits(CallbackInfo ci) {
        if (!create_media_systems$announcement()) {
            return;
        }
        for (ScheduleEntry entry : schedule.entries) {
            for (List<ScheduleWaitCondition> column : entry.conditions) {
                column.replaceAll(c -> c.getClass() == ScheduledDelay.class ? new AnnounceCondition(AnnounceCondition.Trigger.ARRIVAL) : c);
            }
        }
    }
}
