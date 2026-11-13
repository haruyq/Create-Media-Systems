package org.haruyq.createmediasystems.items.launcher;

import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.items.power.PowerFeature;
import java.util.List;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class DiscCellCharger extends KineticBlockEntity {
    public static final int MIN_RPM = 16, STRESS = 8;
    /** FE a tick per RPM, and the most FE a tick. */
    public static final int FE_PER_RPM = 8, MAX_FE_PER_TICK = 2048;
    static final int RESERVE_CAPACITY = 32_000, RESERVE_RATE = MIN_RPM * FE_PER_RPM;
    static final String CELL_TAG = "Cell", RESERVE_TAG = "Reserve";
    private static final int CABLE_ACTIVE_TICKS = 8;
    private long externallyChargedAt = Long.MIN_VALUE;
    private final EnergyStorage reserve = new EnergyStorage(RESERVE_CAPACITY, MAX_FE_PER_TICK);
    private boolean mountedDisplay, mountedCharging;
    public final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack item) { return item.getItem() instanceof ChargeableItem; }
        @Override protected void onContentsChanged(int slot) { changed(true); }
    };
    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int max, boolean simulate) {
            int direct = port().receiveEnergy(max, simulate);
            int accepted = direct + reserve.receiveEnergy(Math.max(0, max - direct), simulate);
            if (!simulate && accepted > 0) {
                if (level != null) externallyChargedAt = level.getGameTime();
                changed(false);
            }
            return accepted;
        }
        @Override public int extractEnergy(int max, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return port().getEnergyStored() + reserve.getEnergyStored(); }
        @Override public int getMaxEnergyStored() { return port().getMaxEnergyStored() + RESERVE_CAPACITY; }
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

    public float renderRpm() {
        return mountedDisplay ? (mountedCharging ? MIN_RPM : 0) : getSpeed();
    }

    public void displayMounted(ItemStack stack, boolean charging) {
        if (!ItemStack.matches(cell(), stack)) inventory.setStackInSlot(0, stack);
        mountedDisplay = true;
        mountedCharging = charging;
    }

    public void supplyReserve(Player player, ItemStack battery) {
        int energy = reserve.getEnergyStored() + ChargeableItem.drawBattery(battery, RESERVE_CAPACITY - reserve.getEnergyStored());
        reserve.deserializeNBT(level.registryAccess(), net.minecraft.nbt.IntTag.valueOf(energy));
        changed(true);
        DiscCellChargerBlock.showReserve(player, energy);
    }

    static int stored(CompoundTag tag) {
        return Math.clamp(tag.getInt(RESERVE_TAG), 0, RESERVE_CAPACITY);
    }

    static int chargeCell(ItemStack cell, int available) {
        return ChargeableItem.storage(cell).receiveEnergy(Math.min(RESERVE_RATE, available), false);
    }

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
        if (received == 0) {
            received = chargeCell(cell(), reserve.getEnergyStored());
            if (received > 0) {
                reserve.extractEnergy(received, false);
                changed(false);
            }
        }
        boolean active = received > 0 || externallyChargedAt != Long.MIN_VALUE && level.getGameTime() - externallyChargedAt <= CABLE_ACTIVE_TICKS;
        if (getBlockState().getValue(DiscCellChargerBlock.CHARGING) != active)
            level.setBlockAndUpdate(worldPosition, getBlockState().setValue(DiscCellChargerBlock.CHARGING, active));
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put(CELL_TAG, inventory.serializeNBT(registries));
        tag.putInt(RESERVE_TAG, reserve.getEnergyStored());
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(CELL_TAG)) inventory.deserializeNBT(registries, tag.getCompound(CELL_TAG));
        reserve.deserializeNBT(registries, net.minecraft.nbt.IntTag.valueOf(stored(tag)));
    }
    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        tooltip.add(Component.translatable("block.create_media_systems.disc_cell_charger"));
        tooltip.add(Component.translatable("cell.create_media_systems.energy", port().getEnergyStored(), port().getMaxEnergyStored()));
        tooltip.add(Component.translatable("equipment.reserve", reserve.getEnergyStored(), RESERVE_CAPACITY));
        tooltip.add(Component.translatable("cell.create_media_systems.charger_info"));
        tooltip.add(Component.translatable("equipment.charger.reserve"));
        return true;
    }
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.energy);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.inventory);
    }
}
