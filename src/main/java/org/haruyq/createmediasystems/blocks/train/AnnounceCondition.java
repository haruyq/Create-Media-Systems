package org.haruyq.createmediasystems.blocks.train;

import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.createmod.catnip.data.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One announcement in an announcement schedule: when to play ({@link Trigger}) and what (a URL). It sits under a
 * {@link StationInstruction}; the trip it refers to is the one towards that station. In a regular train schedule
 * (where the schedule screen no longer offers it) it would be a no-op that completes immediately.
 */
public class AnnounceCondition extends ScheduleWaitCondition {
    /** When an announcement plays, relative to the trip towards its station. */
    public enum Trigger {
        ARRIVAL(Unit.NONE), DEPARTURE(Unit.NONE), AFTER_DEPARTURE(Unit.SECONDS), PROGRESS(Unit.PERCENT),
        BEFORE_ARRIVAL(Unit.SECONDS);

        public final ResourceLocation id = CreateMediaSystems.id("announce_" + name().toLowerCase(Locale.ROOT));
        public final Unit unit;

        Trigger(Unit unit) {
            this.unit = unit;
        }

        public boolean ready(int value, Trip trip) {
            return switch (this) {
                case ARRIVAL -> trip.arrived();
                case DEPARTURE -> trip.underway();
                case AFTER_DEPARTURE -> trip.underway() && trip.elapsedSeconds() >= value;
                case PROGRESS -> trip.underway() && trip.progress() * 100 >= value;
                case BEFORE_ARRIVAL -> trip.underway() && trip.secondsToArrival() <= value;
            };
        }
    }

    /** What a trigger's number counts, and where it starts. */
    public enum Unit {
        NONE(0), SECONDS(30), PERCENT(50);

        public final int initial;

        Unit(int initial) {
            this.initial = initial;
        }
    }

    /**
     * Where a train is on its trip towards a station: {@code underway} while heading there, {@code arrived} on
     * the tick it stops there. {@code progress} is 0..1 of the route; {@code secondsToArrival} is at the current
     * speed (infinite when stopped).
     */
    public record Trip(boolean underway, boolean arrived, double elapsedSeconds, double progress, double secondsToArrival) {
    }

    private final Trigger trigger;

    public AnnounceCondition(Trigger trigger) {
        this.trigger = trigger;
        data.putInt("Value", trigger.unit.initial);
    }

    public Trigger trigger() {
        return trigger;
    }

    public String url() {
        return textData("Url");
    }

    public int value() {
        return intData("Value");
    }

    @Override
    public ResourceLocation getId() {
        return trigger.id;
    }

    @Override
    public Pair<ItemStack, Component> getSummary() {
        String when = switch (trigger) {
            case ARRIVAL, DEPARTURE -> "";
            case AFTER_DEPARTURE -> "+" + value() + "s ";
            case PROGRESS -> value() + "% ";
            case BEFORE_ARRIVAL -> "-" + value() + "s ";
        };
        return Pair.of(new ItemStack(AudioFeature.CUSTOM_DISC.get()),
                Component.literal(when + url().replaceFirst("^https?://", "")));
    }

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        return true;
    }

    @Override
    public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag tag) {
        return Component.empty();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        if (trigger.unit == Unit.NONE) {
            builder.addTextInput(0, 121, (box, tooltip) -> box.setMaxLength(UrlPolicy.MAX_LENGTH), "Url");
            return;
        }
        boolean percent = trigger.unit == Unit.PERCENT;
        builder.addScrollInput(0, 31, (input, label) -> {
            input.titled(Component.translatable(percent ? "create_media_systems.schedule.value.percent" : "create_media_systems.schedule.value.seconds"))
                    .withRange(percent ? 1 : 0, percent ? 101 : 601)
                    .withShiftStep(10)
                    .format(v -> Component.literal(v + (percent ? "%" : "s")));
            input.lockedTooltipX = -15;
            input.lockedTooltipY = 35;
        }, "Value");
        builder.addTextInput(36, 85, (box, tooltip) -> box.setMaxLength(UrlPolicy.MAX_LENGTH), "Url");
    }
}
