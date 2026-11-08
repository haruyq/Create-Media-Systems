package org.haruyq.createmediasystems;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/** A value from 0 to 100%, set with a wrench like any Create value: a Blu-ray player's volume and lighting, a train disc reader's volume. */
public final class PercentSetting {
    /** Where a block entity's one default-type setting is saved. */
    public static final String DEFAULT_TAG = "ScrollValue";

    private PercentSetting() {
    }

    /** The setting saved under {@code tag} in {@code data}: 100 if none is. */
    public static int saved(CompoundTag data, String tag) {
        return data.contains(tag) ? data.getInt(tag) : 100;
    }

    /** A value from 0 to 100%, 100 at first, set with a wrench on {@code box}. */
    public static ScrollValueBehaviour create(SmartBlockEntity entity, String key, CenteredSideValueBoxTransform box) {
        return create(entity, key, box, ScrollValueBehaviour.TYPE, DEFAULT_TAG);
    }

    /** The same, as behaviour {@code type}, saved under {@code tag}; one per type and tag on a block entity. */
    public static ScrollValueBehaviour create(SmartBlockEntity entity, String key, CenteredSideValueBoxTransform box,
                                        BehaviourType<ScrollValueBehaviour> type, String tag) {
        Component label = Component.translatable(key);
        ScrollValueBehaviour value = new ScrollValueBehaviour(label, entity, box) {
            @Override
            public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
                return new ValueSettingsBoard(label, 100, 10, List.of(label),
                        new ValueSettingsFormatter(settings -> Component.literal(settings.value() + "%")));
            }

            @Override
            public BehaviourType<?> getType() {
                return type;
            }

            /** Create's wrench packet sets the first value box with this id: volume and lighting need their own. */
            @Override
            public int netId() {
                return tag.hashCode();
            }

            @Override
            public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
                nbt.putInt(tag, value);
            }

            @Override
            public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
                value = saved(nbt, tag);
            }
        };
        value.between(0, 100);
        value.value = 100;
        value.requiresWrench();
        value.withFormatter(percent -> percent + "%");
        return value;
    }
}
