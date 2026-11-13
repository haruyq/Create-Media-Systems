package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class KeyboardPowerAdapter extends KineticBlock implements IBE<KeyboardPowerAdapter.Supply> {
    public static final int MIN_RPM = 16, CAPACITY = 2400;
    static final int TRANSFER = KeyboardRuntime.DRAW * 2;
    static final String ENERGY = "Energy";
    private static final VoxelShape X = Shapes.or(box(2, 0, 3, 14, 1, 13), box(3, 1, 4, 13, 6, 12));
    private static final VoxelShape Z = Shapes.or(box(3, 0, 2, 13, 1, 14), box(4, 1, 3, 12, 6, 13));

    KeyboardPowerAdapter(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, false));
    }

    public static Direction output(BlockState state) {
        return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.POWERED);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var forward = context.getHorizontalDirection();
        var clicked = context.getClickedFace().getOpposite();
        var neighbour = pos.relative(clicked);
        if (clicked.getAxis().isHorizontal() && KeyboardBlock.isAdapterPort(neighbour, level.getBlockState(neighbour), clicked.getOpposite())) {
            forward = clicked;
        } else if (!KeyboardBlock.isAdapterPort(pos.relative(forward), level.getBlockState(pos.relative(forward)), forward.getOpposite())) {
            for (var side : Direction.Plane.HORIZONTAL) {
                var target = pos.relative(side);
                if (KeyboardBlock.isAdapterPort(target, level.getBlockState(target), side.getOpposite())) {
                    forward = side;
                    break;
                }
            }
        }
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, forward);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, rotation.rotate(output(state)));
    }

    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(output(state)));
    }

    @Override public Direction.Axis getRotationAxis(BlockState state) { return Direction.Axis.Y; }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    static int stored(CompoundTag tag) {
        return Math.clamp(tag.getInt(ENERGY), 0, CAPACITY);
    }

    @Override public Class<Supply> getBlockEntityClass() { return Supply.class; }
    @Override public BlockEntityType<? extends Supply> getBlockEntityType() { return PxS1100Feature.ADAPTER_ENTITY.get(); }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return output(state).getAxis() == Direction.Axis.X ? X : Z;
    }

    public static final class Supply extends KineticBlockEntity {
        private final EnergyStorage buffer = new EnergyStorage(CAPACITY, 256, TRANSFER) {
            @Override public int receiveEnergy(int amount, boolean simulate) {
                int accepted = super.receiveEnergy(amount, simulate);
                if (accepted > 0 && !simulate) setChanged();
                return accepted;
            }
        };

        public Supply(BlockPos pos, BlockState state) {
            super(PxS1100Feature.ADAPTER_ENTITY.get(), pos, state);
        }

        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
        @Override public float calculateStressApplied() { return lastStressApplied = 4; }

        @Nullable IEnergyStorage input(@Nullable Direction face) {
            if (face != null && face != output(getBlockState()).getOpposite()) return null;
            return new IEnergyStorage() {
                @Override public int receiveEnergy(int amount, boolean simulate) {
                    return canReceive() ? buffer.receiveEnergy(amount, simulate) : 0;
                }
                @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
                @Override public int getEnergyStored() { return buffer.getEnergyStored(); }
                @Override public int getMaxEnergyStored() { return CAPACITY; }
                @Override public boolean canExtract() { return false; }
                @Override public boolean canReceive() {
                    return !isRemoved() && (face == null || face == output(getBlockState()).getOpposite());
                }
            };
        }

        @Override public void tick() {
            super.tick();
            if (level == null || level.isClientSide) return;
            var state = getBlockState();
            var direction = output(state);
            var target = worldPosition.relative(direction);
            var back = direction.getOpposite();
            boolean kinetic = Math.abs(getSpeed()) >= MIN_RPM;
            int available = kinetic ? TRANSFER : Math.min(TRANSFER, buffer.getEnergyStored());
            boolean active = false;
            if (available > 0 && level.isLoaded(target) && SableCompat.sameSubLevel(level, worldPosition, target)
                    && KeyboardBlock.isAdapterPort(target, level.getBlockState(target), back)) {
                var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, target, back);
                if (port != null) {
                    int moved = port.receiveEnergy(available, false);
                    active = moved > 0 || port.getEnergyStored() == port.getMaxEnergyStored();
                    if (moved > 0 && !kinetic) {
                        buffer.extractEnergy(moved, false);
                        setChanged();
                    }
                }
            }
            if (state.getValue(BlockStateProperties.POWERED) != active) {
                level.setBlockAndUpdate(worldPosition, state.setValue(BlockStateProperties.POWERED, active));
            }
        }

        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            tag.putInt(ENERGY, buffer.getEnergyStored());
        }

        @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            buffer.deserializeNBT(registries, net.minecraft.nbt.IntTag.valueOf(stored(tag)));
        }

        @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            super.addToGoggleTooltip(tooltip, sneaking);
            tooltip.add(Component.translatable("px_s1100.adapter.inputs", MIN_RPM, TRANSFER));
            return true;
        }
    }
}
