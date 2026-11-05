package org.haruyq.createmediasystems.train;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.simibubi.create.content.trains.schedule.Schedule;

import net.createmod.catnip.data.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The schedule entry types of announcement schedules. They live in Create's global type lists (which is how
 * schedules are read back), but the schedule screen shows only them when editing an announcement schedule, and
 * hides them from train schedules (see ScheduleScreenMixin).
 */
public final class AnnouncementTypes {
    private AnnouncementTypes() {
    }

    public static void register() {
        // Each also under its id from before the rename, so schedules saved then read back; never offered.
        for (ResourceLocation id : List.of(StationInstruction.ID, CreateMediaSystems.legacyId(StationInstruction.ID.getPath()))) {
            Schedule.INSTRUCTION_TYPES.add(Pair.of(id, StationInstruction::new));
        }
        for (AnnounceCondition.Trigger trigger : AnnounceCondition.Trigger.values()) {
            for (ResourceLocation id : List.of(trigger.id, CreateMediaSystems.legacyId(trigger.id.getPath()))) {
                Schedule.CONDITION_TYPES.add(Pair.of(id, () -> new AnnounceCondition(trigger)));
            }
        }
    }

    /** The entries of {@code all} an announcement schedule offers, or a train schedule does. */
    public static <T> List<Pair<ResourceLocation, T>> filter(List<Pair<ResourceLocation, T>> all, boolean announcement) {
        return all.stream().filter(pair -> !CreateMediaSystems.LEGACY_ID.equals(pair.getFirst().getNamespace())
                && CreateMediaSystems.MOD_ID.equals(pair.getFirst().getNamespace()) == announcement).toList();
    }

    /**
     * Option labels for a filtered list, like {@link Schedule#getTypeOptions}, which picks "instruction" or
     * "condition" by comparing with the unfiltered list and so mislabels a filtered one.
     */
    public static List<Component> labels(List<? extends Pair<ResourceLocation, ?>> types) {
        return types.stream().map(pair -> {
            ResourceLocation id = pair.getFirst();
            String section = Schedule.INSTRUCTION_TYPES.contains(pair) ? "instruction." : "condition.";
            return (Component) Component.translatable(id.getNamespace() + ".schedule." + section + id.getPath());
        }).toList();
    }
}
