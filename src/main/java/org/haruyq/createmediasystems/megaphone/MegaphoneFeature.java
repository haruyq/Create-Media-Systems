package org.haruyq.createmediasystems.megaphone;

import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.MENUS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;

/** Megaphones, white and black: held, they play their disc through a horn on their own battery. */
public final class MegaphoneFeature implements Feature {
    public static final Supplier<DataComponentType<CompoundTag>> MEGAPHONE_STATE = COMPONENTS.registerComponentType(
            "megaphone_state", b -> b.persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG));
    public static final DeferredItem<MegaphoneItem> MEGAPHONE_WHITE = ITEMS.register("megaphone_white",
            () -> new MegaphoneItem(new Item.Properties().rarity(Rarity.UNCOMMON), false));
    public static final DeferredItem<MegaphoneItem> MEGAPHONE_BLACK = ITEMS.register("megaphone_black",
            () -> new MegaphoneItem(new Item.Properties().rarity(Rarity.UNCOMMON), true));
    public static final Supplier<MenuType<MegaphoneMenu>> MEGAPHONE_MENU =
            MENUS.register("megaphone", () -> IMenuTypeExtension.create(MegaphoneMenu::new));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        MegaphoneAction.register(registrar);
        MegaphoneLength.register(registrar);
    }
}
