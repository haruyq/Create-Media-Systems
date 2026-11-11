package org.haruyq.createmediasystems.video;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.UUID;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.ServerConfig;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class VideoFeature implements Feature {
    public static final ModConfigSpec.IntValue REMOTE_RANGE = ServerConfig.intSetting("remoteRange", "blocks",
            "Farthest (in blocks) a remote reaches the Blu-ray player it is paired with.", 64, 1, 4096);

    public static final Supplier<DataComponentType<GlobalPos>> REMOTE_TARGET = COMPONENTS.registerComponentType(
            "remote_target", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));
    public static final Supplier<DataComponentType<UUID>> REMOTE_SOURCE = COMPONENTS.registerComponentType(
            "remote_source", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    public static final DeferredBlock<VideoPlayerBlock> VIDEO_PLAYER = BLOCKS.register("blu_ray_player",
            () -> new VideoPlayerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> VIDEO_PLAYER_ITEM = ITEMS.registerSimpleBlockItem("blu_ray_player", VIDEO_PLAYER);
    public static final Supplier<BlockEntityType<VideoPlayerBlock.Entity>> VIDEO_PLAYER_ENTITY = BLOCK_ENTITIES.register(
            "blu_ray_player", () -> BlockEntityType.Builder.of(VideoPlayerBlock.Entity::new, VIDEO_PLAYER.get()).build(null));
    public static final DeferredItem<RemoteItem> REMOTE = ITEMS.register("remote",
            () -> new RemoteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<EqualizerBlock> EQUALIZER = BLOCKS.register("equalizer",
            () -> new EqualizerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> EQUALIZER_ITEM = ITEMS.registerSimpleBlockItem("equalizer", EQUALIZER);
    public static final Supplier<BlockEntityType<EqualizerBlock.Entity>> EQUALIZER_ENTITY = BLOCK_ENTITIES.register(
            "equalizer", () -> BlockEntityType.Builder.of(EqualizerBlock.Entity::new, EQUALIZER.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        RemoteItem.registerPayloads(registrar);
        EqualizerBlock.registerPayloads(registrar);
    }
}
