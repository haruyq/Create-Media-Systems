package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
    public static final DeferredBlock<KeyboardPowerAdapter> ADAPTER = CreateMediaSystems.BLOCKS.register("px_s1100_power_adapter",
            () -> new KeyboardPowerAdapter(BlockBehaviour.Properties.of().strength(2, 4).sound(SoundType.METAL)
                    .noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> ADAPTER_ITEM = CreateMediaSystems.ITEMS.register("px_s1100_power_adapter",
            () -> new BlockItem(ADAPTER.get(), new Item.Properties()) {
                @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
                    lines.add(Component.translatable("px_s1100.adapter.connection").withStyle(ChatFormatting.GRAY));
                    lines.add(Component.translatable("px_s1100.adapter.inputs", KeyboardPowerAdapter.MIN_RPM,
                            KeyboardPowerAdapter.TRANSFER).withStyle(ChatFormatting.GRAY));
                }
            });
    public static final Supplier<BlockEntityType<KeyboardPowerAdapter.Supply>> ADAPTER_ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            "px_s1100_power_adapter", () -> BlockEntityType.Builder.of(KeyboardPowerAdapter.Supply::new, ADAPTER.get()).build(null));

    @Override public void register(IEventBus modBus) {
        modBus.addListener((RegisterCapabilitiesEvent event) ->
                event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, entity, side) -> {
                    var part = state.getValue(KeyboardBlock.PART);
                    if (level.isClientSide || !KeyboardBlock.powerInput(state, part)
                            || side != null && side != state.getValue(FACING).getOpposite()) return null;
                    var root = KeyboardBlock.root(pos, state);
                    if (!(level.getBlockEntity(root) instanceof KeyboardBlockEntity keyboard)
                            || !KeyboardBlock.part(keyboard.getBlockState(), KeyboardBlock.Part.MAIN, state)) return null;
                    return new EnergyPort(keyboard, pos, part, side);
                }, BLOCK.get()));
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                ADAPTER_ENTITY.get(), KeyboardPowerAdapter.Supply::input));
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var block = BLOCK.get();
            MovementBehaviour.REGISTRY.register(block, new MountedKeyboard());
            MovementBehaviour.REGISTRY.register(ADAPTER.get(), new MountedKeyboardAdapter());
            MovingInteractionBehaviour.REGISTRY.register(block, new MountedKeyboard.Interaction());
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.is(block) || state.is(ADAPTER.get()) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerAttachedCheck((state, level, pos, direction) -> {
                if (!state.is(block) && !state.is(ADAPTER.get())) return CheckResult.PASS;
                var next = pos.relative(direction);
                var other = level.getBlockState(next);
                if (state.is(ADAPTER.get())) {
                    return KeyboardPowerAdapter.output(state) == direction && KeyboardBlock.isAdapterPort(next, other, direction.getOpposite())
                            ? CheckResult.SUCCESS : CheckResult.PASS;
                }
                if (other.is(ADAPTER.get()) && KeyboardPowerAdapter.output(other) == direction.getOpposite()
                        && KeyboardBlock.isAdapterPort(pos, state, direction)) return CheckResult.SUCCESS;
                return other.is(block) && KeyboardBlock.part(other, other.getValue(KeyboardBlock.PART), state)
                        && KeyboardBlock.root(pos, state).equals(KeyboardBlock.root(next, other))
                        ? CheckResult.SUCCESS : CheckResult.PASS;
            });
        }));
    }

    @Override public void registerPayloads(PayloadRegistrar registrar) {
        KeyboardPackets.register(registrar.versioned("4"));
    }

    private record EnergyPort(KeyboardBlockEntity keyboard, BlockPos pos, KeyboardBlock.Part part, @Nullable Direction side)
            implements IEnergyStorage {
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
            return KeyboardBlock.powerInput(state, part) && part.at(keyboard.getBlockPos(), state).equals(pos)
                    && (side == null || side == state.getValue(FACING).getOpposite());
        }
    }
}
