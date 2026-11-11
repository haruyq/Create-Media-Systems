package org.haruyq.createmediasystems;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class CreateMediaSystems {
    public static final String MOD_ID = "create_media_systems";
    /** The mod's id before it was renamed: worlds from then name its blocks, items and data with it. */
    public static final String LEGACY_ID = "disksb";
    public static final String COMMAND = "cms";
    /** Bumped whenever a payload's format changes, so a client and server that differ refuse each other. */
    private static final String PROTOCOL = "22";

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);

    private CreateMediaSystems() {
    }

    public static void register(IEventBus modBus, ModContainer container, List<? extends Feature> features) {
        features.forEach(feature -> feature.register(modBus));
        ServerConfig.register(modBus, container);
        // Worlds from before the rename keep what they have of the mod.
        for (DeferredRegister<?> register : List.of(SOUNDS, COMPONENTS, BLOCKS, ITEMS, BLOCK_ENTITIES, ATTACHMENTS, TABS)) {
            for (DeferredHolder<?, ?> entry : register.getEntries()) {
                register.addAlias(legacyId(entry.getId().getPath()), entry.getId());
            }
        }
        for (DeferredRegister<?> register : List.of(SOUNDS, ENTITIES, COMPONENTS, BLOCKS, ITEMS, BLOCK_ENTITIES,
                MENUS, RECIPE_SERIALIZERS, ATTACHMENTS, TABS)) {
            register.register(modBus);
        }
        NeoForge.EVENT_BUS.addListener(CreateMediaSystems::renameLegacySavedData);
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar(PROTOCOL);
            ServerConfig.registerPayloads(registrar);
            features.forEach(feature -> feature.registerPayloads(registrar));
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation legacyId(String path) {
        return ResourceLocation.fromNamespaceAndPath(LEGACY_ID, path);
    }

    /** A world from before the rename keeps the mod's saved data as data/LEGACY_ID_*.dat; it becomes MOD_ID_*.dat. */
    private static void renameLegacySavedData(ServerAboutToStartEvent event) {
        String prefix = LEGACY_ID + "_";
        // A dimension's data is at most <world>/dimensions/<namespace>/<path>/data/<name>.dat.
        try (Stream<Path> files = Files.walk(event.getServer().getWorldPath(LevelResource.ROOT), 5)) {
            for (Path file : files.filter(f -> f.getParent() != null && f.getParent().endsWith("data")
                    && f.getFileName().toString().startsWith(prefix)).toList()) {
                Path renamed = file.resolveSibling(MOD_ID + "_" + file.getFileName().toString().substring(prefix.length()));
                if (!Files.exists(renamed)) {
                    Files.move(file, renamed);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            LogUtils.getLogger().warn("Could not rename this world's saved data from before the mod was renamed", e);
        }
    }
}
