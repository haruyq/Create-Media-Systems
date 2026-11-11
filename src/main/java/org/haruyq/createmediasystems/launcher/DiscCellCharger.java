package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.power.PowerFeature;
import java.util.List;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

/** One owned inventory; rotation and FE both charge the real cell, including its persistent master. */
public final class DiscCellCharger extends KineticBlockEntity {
    /** The slowest shaft that charges, and the stress it takes. */
    public static final int MIN_RPM = 16, STRESS = 8;
    /** FE a tick per RPM, and the most FE a tick. */
    public static final int FE_PER_RPM = 8, MAX_FE_PER_TICK = 2048;
    /** How long it shows as charging after FE last came in by cable. */
    private static final int CABLE_ACTIVE_TICKS = 8;
    private long externallyChargedAt = Long.MIN_VALUE;
    public final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack item) { return item.getItem() instanceof ChargeableItem; }
        @Override protected void onContentsChanged(int slot) { changed(true); }
    };
    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int max, boolean simulate) {
            int accepted = port().receiveEnergy(max, simulate);
            if (!simulate && accepted > 0) {
                if (level != null) externallyChargedAt = level.getGameTime();
                changed(false);
            }
            return accepted;
        }
        @Override public int extractEnergy(int max, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return port().getEnergyStored(); }
        @Override public int getMaxEnergyStored() { return port().getMaxEnergyStored(); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return getEnergyStored() < getMaxEnergyStored(); }
    };
    private IEnergyStorage port() { return ChargeableItem.storage(cell()); }

    public DiscCellCharger(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public DiscCellCharger(BlockPos pos, BlockState state) { this(LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
    public ItemStack cell() { return inventory.getStackInSlot(0); }
    public void insert(ItemStack cell) { inventory.insertItem(0, cell, false); }
    public ItemStack take() { return inventory.extractItem(0, 1, false); }

    private void changed(boolean immediate) {
        setChanged();
        if (level != null && !level.isClientSide && (immediate || level.getGameTime() % 10 == 0)) sendData();
    }
    @Override public float calculateStressApplied() { lastStressApplied = STRESS; return STRESS; }

    @Override public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        float rpm = Math.abs(getSpeed());
        int received = rpm >= MIN_RPM ? energy.receiveEnergy(Math.min(MAX_FE_PER_TICK, Math.round(rpm * FE_PER_RPM)), false) : 0;
        boolean active = received > 0 || externallyChargedAt != Long.MIN_VALUE && level.getGameTime() - externallyChargedAt <= CABLE_ACTIVE_TICKS;
        if (getBlockState().getValue(DiscCellChargerBlock.CHARGING) != active)
            level.setBlockAndUpdate(worldPosition, getBlockState().setValue(DiscCellChargerBlock.CHARGING, active));
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Cell", inventory.serializeNBT(registries));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Cell")) inventory.deserializeNBT(registries, tag.getCompound("Cell"));
    }
    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        tooltip.add(Component.translatable("block.create_media_systems.disc_cell_charger"));
        tooltip.add(Component.translatable("cell.create_media_systems.energy", energy.getEnergyStored(), energy.getMaxEnergyStored()));
        tooltip.add(Component.translatable("cell.create_media_systems.charger_info"));
        return true;
    }
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.energy);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.inventory);
    }
}
