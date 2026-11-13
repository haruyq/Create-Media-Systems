package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.function.Supplier;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;

public final class PianoFeature implements Feature {
    public static final DeferredBlock<GrandPianoBlock> PIANO = CreateMediaSystems.BLOCKS.register("grand_piano",
            () -> new GrandPianoBlock(BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD)
                    .noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> PIANO_ITEM = CreateMediaSystems.ITEMS.register("grand_piano",
            () -> new BlockItem(PIANO.get(), new Item.Properties().stacksTo(1)) {
                @Override
                protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
                    var level = context.getLevel();
                    return level.isClientSide ? super.placeBlock(context, state)
                            : GrandPianoBlock.placeParts(level, context.getClickedPos(), state);
                }
            });
    public static final Supplier<BlockEntityType<PianoBlockEntity>> PIANO_ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            "grand_piano", () -> BlockEntityType.Builder.of(PianoBlockEntity::new, PIANO.get()).build(null));

    @Override
    public void register(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var piano = PIANO.get();
            MovementBehaviour.REGISTRY.register(piano, new MountedPiano());
            MovingInteractionBehaviour.REGISTRY.register(piano, new MountedPiano.Interaction());
            BlockMovementChecks.registerMovementNecessaryCheck((state, level, pos) ->
                    state.is(piano) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.is(piano) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerAttachedCheck((state, level, pos, direction) -> {
                if (!state.is(piano)) return CheckResult.PASS;
                var otherPos = pos.relative(direction);
                var other = level.getBlockState(otherPos);
                if (other.is(piano) && GrandPianoBlock.root(pos, state).equals(GrandPianoBlock.root(otherPos, other))) {
                    return CheckResult.SUCCESS;
                }
                return CheckResult.PASS;
            });
        }));
    }

    @Override public void registerPayloads(PayloadRegistrar registrar) { PianoPackets.register(registrar); }
}
