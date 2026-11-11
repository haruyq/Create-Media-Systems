package org.haruyq.createmediasystems.launcher;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.MENUS;
import static org.haruyq.createmediasystems.CreateMediaSystems.MOD_ID;
import static org.haruyq.createmediasystems.CreateMediaSystems.RECIPE_SERIALIZERS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;
import static org.haruyq.createmediasystems.CreateMediaSystems.id;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** The disc launcher, the discs it fires, its energy cells and their charger. */
public final class LauncherFeature implements Feature {
    /** The disc launcher stores original individual discs, not lossy item identifiers. */
    public static final Supplier<DataComponentType<DiscPlayback>> DISC_PLAYBACK =
            COMPONENTS.registerComponentType("disc_playback", b -> b
                    .persistent(DiscPlayback.CODEC)
                    .networkSynchronized(DiscPlayback.STREAM_CODEC));
    public static final Supplier<DataComponentType<List<ItemStack>>> LAUNCHER_LOADED = COMPONENTS.registerComponentType(
            "launcher_loaded", b -> b.persistent(ItemStack.CODEC.listOf())
                    .networkSynchronized(ItemStack.LIST_STREAM_CODEC));
    public static final Supplier<DataComponentType<List<ItemStack>>> LAUNCHER_RESERVED = COMPONENTS.registerComponentType(
            "launcher_reserved", b -> b.persistent(ItemStack.CODEC.listOf())
                    .networkSynchronized(ItemStack.LIST_STREAM_CODEC));
    public static final Supplier<DataComponentType<Long>> LAUNCHER_RELOAD_START = COMPONENTS.registerComponentType(
            "launcher_reload_start", b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    public static final Supplier<DataComponentType<Long>> LAUNCHER_SHOT_AT = COMPONENTS.registerComponentType(
            "launcher_shot_at", b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    public static final Supplier<DataComponentType<ItemContainerContents>> CELL_MASTER = COMPONENTS.registerComponentType(
            "cell_master", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));
    public static final Supplier<DataComponentType<ItemContainerContents>> LAUNCHER_CELL = COMPONENTS.registerComponentType(
            "launcher_cell", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));
    public static final Supplier<DataComponentType<ItemContainerContents>> LAUNCHER_PENDING_CELL = COMPONENTS.registerComponentType(
            "launcher_pending_cell", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));
    public static final Supplier<DataComponentType<Boolean>> LAUNCHER_EJECT_ONLY = COMPONENTS.registerComponentType(
            "launcher_eject_only", b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    public static final DeferredHolder<SoundEvent, SoundEvent> DISC_FIRE_SOUND =
            SOUNDS.register("disc_fire", () -> SoundEvent.createVariableRangeEvent(id("disc_fire")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DISC_LATCH_SOUND =
            SOUNDS.register("disc_latch", () -> SoundEvent.createVariableRangeEvent(id("disc_latch")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DISC_INSERT_SOUND =
            SOUNDS.register("disc_insert", () -> SoundEvent.createVariableRangeEvent(id("disc_insert")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DISC_CHARGE_SOUND =
            SOUNDS.register("disc_charge", () -> SoundEvent.createVariableRangeEvent(id("disc_charge")));

    /** One cell per tier, in tier order. */
    public static final List<DeferredItem<DiscCellItem>> DISC_CELLS = Arrays.stream(DiscCellTier.values())
            .map(LauncherFeature::registerCell).toList();
    public static final Supplier<MenuType<DiscCellMenu>> DISC_CELL_MENU =
            MENUS.register("disc_cell", () -> IMenuTypeExtension.create(DiscCellMenu::new));
    public static final Supplier<DiscCellUpgradeRecipe.Serializer> CELL_UPGRADE_RECIPE =
            RECIPE_SERIALIZERS.register("disc_cell_upgrade", DiscCellUpgradeRecipe.Serializer::new);
    public static final DeferredBlock<ReloadGlowBlock> RELOAD_GLOW = BLOCKS.register("reload_glow", () -> new ReloadGlowBlock(
            BlockBehaviour.Properties.of().air().noCollission().noOcclusion().noLootTable().replaceable()
                    .strength(-1, 3600000).lightLevel(state -> 15)));
    public static final DeferredBlock<DiscCellChargerBlock> DISC_CELL_CHARGER = BLOCKS.register("disc_cell_charger",
            () -> new DiscCellChargerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()
                    .lightLevel(state -> state.getValue(DiscCellChargerBlock.CHARGING) ? 12 : 0)));
    public static final DeferredItem<?> DISC_CELL_CHARGER_ITEM = ITEMS.registerSimpleBlockItem("disc_cell_charger", DISC_CELL_CHARGER);
    public static final Supplier<BlockEntityType<DiscCellCharger>> DISC_CELL_CHARGER_ENTITY = BLOCK_ENTITIES.register(
            "disc_cell_charger", () -> BlockEntityType.Builder.of(DiscCellCharger::new, DISC_CELL_CHARGER.get()).build(null));
    public static final DeferredItem<DiscLauncherItem> DISC_LAUNCHER = ITEMS.register("disc_launcher",
            () -> new DiscLauncherItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>, EntityType<DiscProjectile>> DISC_PROJECTILE =
            ENTITIES.register("disc_projectile", () -> EntityType.Builder.<DiscProjectile>of(
                    DiscProjectile::new, MobCategory.MISC).sized(.35f, .12f)
                    .clientTrackingRange(10).updateInterval(1).build(MOD_ID + ":disc_projectile"));

    private static DeferredItem<DiscCellItem> registerCell(DiscCellTier tier) {
        return ITEMS.register(tier.itemId(), () -> new DiscCellItem(tier, new Item.Properties()
                .rarity(tier.level >= 5 ? Rarity.EPIC : tier.level >= 3 ? Rarity.RARE : Rarity.UNCOMMON)));
    }

    public static DiscCellItem cell(DiscCellTier tier) {
        return DISC_CELLS.get(tier.ordinal()).get();
    }

    @Override
    public void register(IEventBus modBus) {
        modBus.addListener(DiscCellCharger::registerCapabilities);
        modBus.addListener(LauncherFeature::hideUnavailableCells);
        NeoForge.EVENT_BUS.register(DiscLauncherTrigger.class);
        NeoForge.EVENT_BUS.register(DiscProjectileLifecycle.class);
    }

    /** A cell made of another mod's material is listed only when that mod is there. */
    private static void hideUnavailableCells(BuildCreativeModeTabContentsEvent event) {
        for (DiscCellTier tier : DiscCellTier.values()) {
            if (!tier.available()) {
                event.remove(new ItemStack(cell(tier)), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            }
        }
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        DiscLauncherReloadPacket.registerPayloads(registrar);
        DiscLauncherTrigger.register(registrar);
        LauncherFeedback.register(registrar);
    }
}
