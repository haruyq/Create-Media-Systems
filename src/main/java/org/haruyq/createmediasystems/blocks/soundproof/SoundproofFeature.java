package org.haruyq.createmediasystems.blocks.soundproof;

import static org.haruyq.createmediasystems.CreateMediaSystems.ATTACHMENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.Set;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofDoorBlock.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * What keeps the mod's sounds in a room (see Soundproofing): four doors (see SoundproofDoorBlock.Kind), an acoustic
 * foam panel for walls and ceilings, and an insulation sheet that goes into a wall's own blocks.
 */
public final class SoundproofFeature implements Feature {
    public static final DeferredBlock<SoundproofDoorBlock> DOOR = door("soundproof_door", Kind.NORMAL);
    public static final DeferredItem<BlockItem> DOOR_ITEM = ITEMS.register("soundproof_door",
            () -> new DoubleHighBlockItem(DOOR.get(), new Item.Properties()));
    public static final DeferredBlock<SoundproofDoorBlock> GLASS_DOOR = door("soundproof_glass_door", Kind.GLASS);
    public static final DeferredItem<BlockItem> GLASS_DOOR_ITEM = ITEMS.register("soundproof_glass_door",
            () -> new DoubleHighBlockItem(GLASS_DOOR.get(), new Item.Properties()));
    public static final DeferredBlock<SoundproofDoorBlock> SLIDING_DOOR = door("soundproof_sliding_door", Kind.SLIDING);
    public static final DeferredItem<BlockItem> SLIDING_DOOR_ITEM = ITEMS.register("soundproof_sliding_door",
            () -> new DoubleHighBlockItem(SLIDING_DOOR.get(), new Item.Properties()));
    public static final DeferredBlock<SoundproofDoorBlock> DOUBLE_DOOR = door("soundproof_double_door", Kind.DOUBLE);
    public static final DeferredItem<BlockItem> DOUBLE_DOOR_ITEM = ITEMS.register("soundproof_double_door",
            () -> new SoundproofDoorBlock.DoubleItem(DOUBLE_DOOR.get(), new Item.Properties()));
    public static final Supplier<BlockEntityType<SoundproofDoorBlock.Entity>> DOOR_ENTITY = BLOCK_ENTITIES.register(
            "soundproof_door", () -> BlockEntityType.Builder.of(SoundproofDoorBlock.Entity::new, DOOR.get(),
                    GLASS_DOOR.get(), SLIDING_DOOR.get(), DOUBLE_DOOR.get()).build(null));
    public static final DeferredBlock<AcousticPanelBlock> PANEL = BLOCKS.register("acoustic_panel",
            () -> new AcousticPanelBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(.8f)
                    .sound(SoundType.WOOL).noOcclusion()));
    public static final DeferredItem<BlockItem> PANEL_ITEM = ITEMS.registerSimpleBlockItem(PANEL);
    public static final DeferredItem<Item> SHEET = ITEMS.register("sound_insulation_sheet",
            () -> new Sheet(new Item.Properties()));
    /** The blocks of a chunk with an insulation sheet in them; its clients know them too, to hear by. */
    public static final Supplier<AttachmentType<Set<BlockPos>>> SHEETS = ATTACHMENTS.register("insulation_sheets",
            () -> AttachmentType.<Set<BlockPos>>builder(() -> Set.of()).serialize(Soundproofing.SHEETS_CODEC)
                    .sync(Soundproofing.SHEETS_STREAM_CODEC).build());

    private static DeferredBlock<SoundproofDoorBlock> door(String id, Kind kind) {
        return BLOCKS.register(id, () -> new SoundproofDoorBlock(kind, BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL).strength(5).requiresCorrectToolForDrops().noOcclusion()
                .pushReaction(PushReaction.DESTROY)));
    }

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SoundproofFeature::changed);
    }

    /**
     * A sheet comes out with its block, whatever took that: it drops as soon as what is there is no full block. A
     * block taken without its neighbours being told (as a contraption assembling may) leaves its sheet behind
     * unheard, in the next full block put there.
     */
    private static void changed(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level && Soundproofing.sheeted(level, event.getPos())
                && !event.getState().isCollisionShapeFullBlock(level, event.getPos())) {
            Soundproofing.set(level, event.getPos(), false);
            Block.popResource(level, event.getPos(), new ItemStack(SHEET.get()));
        }
    }

    /** Goes into the full block it is used on, unseen, and comes back out of one that has it. */
    private static final class Sheet extends Item {
        Sheet(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            Player player = context.getPlayer();
            boolean has = Soundproofing.sheeted(level, pos);
            if (!has && !level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return InteractionResult.PASS;
            if (level instanceof ServerLevel) {
                Soundproofing.set(level, pos, !has);
                if (!has) {
                    context.getItemInHand().consume(1, player);
                } else if (player == null) {
                    Block.popResource(level, pos, new ItemStack(this));
                } else if (!player.hasInfiniteMaterials()) {
                    player.getInventory().placeItemBackInInventory(new ItemStack(this));
                }
                level.playSound(null, pos, has ? SoundEvents.WOOL_BREAK : SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1, 1);
                if (player != null) {
                    player.displayClientMessage(Component.translatable(
                            "create_media_systems.sound_insulation_sheet." + (has ? "removed" : "applied")), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
