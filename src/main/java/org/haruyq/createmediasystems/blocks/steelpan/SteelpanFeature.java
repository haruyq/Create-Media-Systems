package org.haruyq.createmediasystems.blocks.steelpan;

import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;

public final class SteelpanFeature implements Feature {
    public static final DeferredBlock<SteelpanBlock> BLOCK = CreateMediaSystems.BLOCKS.register("steelpan_low_c",
            () -> new SteelpanBlock(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL)
                    .noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> ITEM = CreateMediaSystems.ITEMS.register("steelpan_low_c",
            () -> new BlockItem(BLOCK.get(), new Item.Properties().stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
                    super.appendHoverText(stack, context, lines, flags);
                    lines.add(Component.translatable("steelpan.tooltip").withStyle(ChatFormatting.GRAY));
                }
            });
    public static final Supplier<BlockEntityType<SteelpanBlock.Entity>> ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            "steelpan_low_c", () -> BlockEntityType.Builder.of(SteelpanBlock.Entity::new, BLOCK.get()).build(null));
    public static final Supplier<SoundEvent> PLAYED = CreateMediaSystems.SOUNDS.register("steelpan", SoundEvent::createVariableRangeEvent);

    @Override
    public void register(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var pan = BLOCK.get();
            MovementBehaviour.REGISTRY.register(pan, new MountedSteelpan());
            MovingInteractionBehaviour.REGISTRY.register(pan, new MountedSteelpan.Interaction());
            BlockMovementChecks.registerMovementNecessaryCheck((state, level, pos) ->
                    state.is(pan) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.is(pan) ? CheckResult.SUCCESS : CheckResult.PASS);
        }));
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        SteelpanPackets.register(registrar);
    }
}
