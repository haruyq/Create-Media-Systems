package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.jetbrains.annotations.Nullable;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock.FACING;

public final class PxS1100Feature implements Feature {
    public static final Supplier<SoundEvent> SYNTH = CreateMediaSystems.SOUNDS.register("keyboard_synth", SoundEvent::createVariableRangeEvent);
    public static final DeferredBlock<KeyboardBlock> BLOCK = CreateMediaSystems.BLOCKS.register("px_s1100",
            () -> new KeyboardBlock(BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.METAL)
                    .noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> ITEM = CreateMediaSystems.ITEMS.register("px_s1100",
            () -> new BlockItem(BLOCK.get(), new Item.Properties().stacksTo(1)) {
                @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
                    var level = context.getLevel();
                    return level.isClientSide ? super.placeBlock(context, state)
                            : KeyboardBlock.place(level, context.getClickedPos(), state);
                }
            });
    public static final Supplier<BlockEntityType<KeyboardBlockEntity>> ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            "px_s1100", () -> BlockEntityType.Builder.of(KeyboardBlockEntity::new, BLOCK.get()).build(null));

    @Override public void register(IEventBus modBus) {
        modBus.addListener((RegisterCapabilitiesEvent event) ->
                event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, entity, side) -> {
                    if (level.isClientSide || state.getValue(KeyboardBlock.PART) != KeyboardBlock.dcInput(state)
                            || side != null && side != state.getValue(FACING).getOpposite()) return null;
                    var root = KeyboardBlock.root(pos, state);
                    if (!(level.getBlockEntity(root) instanceof KeyboardBlockEntity keyboard)
                            || !KeyboardBlock.part(keyboard.getBlockState(), KeyboardBlock.Part.MAIN, state)) return null;
                    return new EnergyPort(keyboard, pos, side);
                }, BLOCK.get()));
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var block = BLOCK.get();
            MovementBehaviour.REGISTRY.register(block, new MountedKeyboard());
            MovingInteractionBehaviour.REGISTRY.register(block, new MountedKeyboard.Interaction());
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.is(block) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerAttachedCheck((state, level, pos, direction) -> {
                if (!state.is(block)) return CheckResult.PASS;
                var next = pos.relative(direction);
                var other = level.getBlockState(next);
                return other.is(block) && KeyboardBlock.part(other, other.getValue(KeyboardBlock.PART), state)
                        && KeyboardBlock.root(pos, state).equals(KeyboardBlock.root(next, other))
                        ? CheckResult.SUCCESS : CheckResult.PASS;
            });
        }));
    }

    @Override public void registerPayloads(PayloadRegistrar registrar) {
        KeyboardPackets.register(registrar.versioned("2"));
    }

    private record EnergyPort(KeyboardBlockEntity keyboard, BlockPos pos, @Nullable Direction side) implements IEnergyStorage {
        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            return canReceive() ? keyboard.runtime().charge(maxReceive, simulate) : 0;
        }
        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return keyboard.isRemoved() ? 0 : keyboard.runtime().energy(); }
        @Override public int getMaxEnergyStored() { return KeyboardRuntime.CAPACITY; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() {
            if (keyboard.isRemoved()) return false;
            var state = keyboard.getBlockState();
            return KeyboardBlock.dcInput(state).at(keyboard.getBlockPos(), state).equals(pos)
                    && (side == null || side == state.getValue(FACING).getOpposite());
        }
    }
}
