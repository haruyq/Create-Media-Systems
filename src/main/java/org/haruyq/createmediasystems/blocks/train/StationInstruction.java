package org.haruyq.createmediasystems.blocks.train;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * "Station" entry of an announcement schedule: the announcements under it are about trips to this station.
 * A destination instruction underneath, so the schedule screen keeps suggesting station names.
 */
public class StationInstruction extends DestinationInstruction {
    public static final ResourceLocation ID = CreateMediaSystems.id("station");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    /** The inherited title looks up Create's lang, which has no entry for this type. */
    @Override
    public List<Component> getTitleAs(String type) {
        return List.of(Component.translatable("create_media_systems.schedule.instruction.station.summary").withStyle(ChatFormatting.GOLD),
                CreateLang.translateDirect("generic.in_quotes", Component.literal(getFilter())));
    }
}
