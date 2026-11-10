package org.haruyq.createmediasystems.launcher;

import java.util.List;
import org.haruyq.createmediasystems.CreateMediaSystems;
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
    private long externallyChargedAt = Long.MIN_VALUE;
    public final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack item) { return item.getItem() instanceof DiscCellItem
                || item.getItem() instanceof org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem; }
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
    private IEnergyStorage port() {
        return cell().getItem() instanceof org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem
                ? org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem.storage(cell()) : new DiscCellEnergy(cell());
    }

    public DiscCellCharger(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public DiscCellCharger(BlockPos pos, BlockState state) { this(CreateMediaSystems.DISC_CELL_CHARGER_ENTITY.get(), pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
    public ItemStack cell() { return inventory.getStackInSlot(0); }
    public void insert(ItemStack cell) { inventory.insertItem(0, cell, false); }
    public ItemStack take() { return inventory.extractItem(0, 1, false); }

    private void changed(boolean immediate) {
        setChanged();
        if (level != null && !level.isClientSide && (immediate || level.getGameTime() % 10 == 0)) sendData();
    }
    @Override public float calculateStressApplied() { lastStressApplied = 8; return 8; }

    @Override public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        float rpm = Math.abs(getSpeed());
        int received = rpm >= 16 ? energy.receiveEnergy(Math.min(2048, Math.round(rpm * 8)), false) : 0;
        boolean active = received > 0 || externallyChargedAt != Long.MIN_VALUE && level.getGameTime() - externallyChargedAt <= 8;
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
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) -> org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem.storage(stack),
                CreateMediaSystems.PARTYBOX_BATTERY.get());
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) -> new DiscCellEnergy(stack),
                CreateMediaSystems.DISC_CELLS.stream().map(entry -> entry.get()).toArray(DiscCellItem[]::new));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CreateMediaSystems.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.energy);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CreateMediaSystems.DISC_CELL_CHARGER_ENTITY.get(), (be, side) -> be.inventory);
    }
}
