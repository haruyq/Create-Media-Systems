package org.haruyq.createmediasystems.partybox;

import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.MENUS;
import static org.haruyq.createmediasystems.CreateMediaSystems.MOD_ID;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class PartyBoxFeature implements Feature {
    public static final Supplier<DataComponentType<CompoundTag>> PARTYBOX_STATE = COMPONENTS.registerComponentType(
            "partybox_state", b -> b.persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG));
    public static final DeferredItem<PartyBoxItem> PARTYBOX = ITEMS.register("partybox_stage_320",
            () -> new PartyBoxItem(new Item.Properties().rarity(Rarity.RARE)));
    public static final DeferredHolder<EntityType<?>, EntityType<PartyBoxEntity>> PARTYBOX_ENTITY =
            ENTITIES.register("partybox_stage_320", () -> EntityType.Builder.<PartyBoxEntity>of(
                    PartyBoxEntity::new, MobCategory.MISC).sized(.40f, .69f)
                    .clientTrackingRange(10).updateInterval(1).build(MOD_ID + ":partybox_stage_320"));
    public static final Supplier<MenuType<PartyBoxMenu>> PARTYBOX_MENU =
            MENUS.register("partybox", () -> IMenuTypeExtension.create(PartyBoxMenu::new));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        PartyBoxAction.register(registrar);
    }
}
