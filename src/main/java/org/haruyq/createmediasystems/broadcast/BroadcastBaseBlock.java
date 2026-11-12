package org.haruyq.createmediasystems.broadcast;

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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

public final class BroadcastBaseBlock extends KineticBlock implements IBE<BroadcastBaseBlock.Base> {
    public BroadcastBaseBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public Class<Base> getBlockEntityClass() {
        return Base.class;
    }

    @Override
    public BlockEntityType<? extends Base> getBlockEntityType() {
        return BroadcastFeature.BASE_ENTITY.get();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return box(2, 0, 2, 14, 16, 14);
    }

    public static final class Base extends KineticBlockEntity {
        public static final int MIN_RPM = 16;
        private static final int STRESS = 8;
        private static final int CAPACITY = 16_000;
        private long paidAt = Long.MIN_VALUE;
        private boolean paid;
        private final Buffer buffer = new Buffer();

        private final class Buffer extends EnergyStorage {
            Buffer() {
                super(CAPACITY, 512, 0);
            }

            @Override
            public int receiveEnergy(int amount, boolean simulate) {
                int accepted = super.receiveEnergy(amount, simulate);
                if (!simulate && accepted > 0) energyChanged();
                return accepted;
            }

            boolean draw(int amount) {
                if (energy < amount) return false;
                energy -= amount;
                energyChanged();
                return true;
            }
        }

        public Base(BlockPos pos, BlockState state) {
            super(BroadcastFeature.BASE_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

        public IEnergyStorage port() {
            return buffer;
        }

        boolean supply(BroadcastProfile profile, long now) {
            if (paidAt == now && paid) return true;
            paidAt = now;
            if (Math.abs(getSpeed()) >= MIN_RPM) return paid = true;
            return paid = buffer.draw(profile.energyPerTick);
        }

        private void energyChanged() {
            setChanged();
            if (level != null && !level.isClientSide && level.getGameTime() % 20 == 0) sendData();
        }

        @Override
        public float calculateStressApplied() {
            return lastStressApplied = STRESS;
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            tag.put("Energy", buffer.serializeNBT(registries));
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            if (tag.contains("Energy")) buffer.deserializeNBT(registries, tag.get("Energy"));
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            super.addToGoggleTooltip(tooltip, sneaking);
            tooltip.add(Component.translatable("broadcast.base.power", MIN_RPM));
            tooltip.add(Component.translatable("cell.create_media_systems.energy", buffer.getEnergyStored(), CAPACITY));
            return true;
        }
    }
}
