package org.haruyq.createmediasystems.items.power;

import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredItem;

public final class PowerFeature implements Feature {
    /** The FE a {@link ChargeableItem} holds. */
    public static final Supplier<DataComponentType<Integer>> ENERGY = COMPONENTS.registerComponentType(
            "cell_energy", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredItem<BatteryItem> BATTERY = ITEMS.register("partybox_battery",
            () -> new BatteryItem(new Item.Properties()));

    @Override
    public void register(IEventBus modBus) {
        modBus.addListener(PowerFeature::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) -> ChargeableItem.storage(stack),
                BuiltInRegistries.ITEM.stream().filter(item -> item instanceof ChargeableItem).toArray(Item[]::new));
    }
}
