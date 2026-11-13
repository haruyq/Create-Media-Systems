package org.haruyq.createmediasystems.items.launcher;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.items.power.BatteryItem;
import org.haruyq.createmediasystems.items.power.ChargeableItem;

/** Operates the captured charger and writes changes into the block data Create will put back on disassembly. */
final class MovingDiscCellCharger implements MovementBehaviour {
    private static final String DISPLAY = "CellDisplay", ACTIVE = "Charging";

    @Override
    public boolean mustTickWhileDisabled() {
        return true;
    }

    @Override
    public void tick(MovementContext context) {
        if (context.world.isClientSide) {
            if (context.contraption.getBlockEntityClientSide(context.localPos) instanceof DiscCellCharger charger) {
                var display = context.data.contains(DISPLAY) ? context.data.getCompound(DISPLAY) : null;
                if (!(context.temporaryData instanceof Displayed previous) || previous.tag != display || previous.charger != charger) {
                    if (display == null) charger.displayMounted(charger.cell(), false);
                    else charger.displayMounted(ItemStack.parseOptional(context.world.registryAccess(), display.getCompound("Item")),
                                display.getBoolean(ACTIVE));
                    context.temporaryData = new Displayed(charger, display);
                }
            }
            return;
        }
        if (context.blockEntityData == null) return;
        State state = state(context);
        int reserve = DiscCellCharger.stored(context.blockEntityData);
        int moved = context.disabled ? 0 : DiscCellCharger.chargeCell(state.cell(), reserve);
        boolean charging = moved > 0;
        if (charging) {
            context.blockEntityData.putInt(DiscCellCharger.RESERVE_TAG, reserve - moved);
            state.save(context);
        }
        if (charging != state.charging || charging && context.world.getGameTime() % 10 == 0) {
            state.charging = charging;
            sync(context, state);
        }
    }

    private record Displayed(DiscCellCharger charger, CompoundTag tag) {}

    @Override
    public void writeExtraData(MovementContext context) {
        if (context.temporaryData instanceof State state) show(context, state.cell(), state.charging);
    }

    private static State state(MovementContext context) {
        if (context.temporaryData instanceof State existing) return existing;
        State state = new State();
        state.charging = context.state.getValue(DiscCellChargerBlock.CHARGING);
        if (context.blockEntityData.contains(DiscCellCharger.CELL_TAG)) {
            state.slot.deserializeNBT(context.world.registryAccess(), context.blockEntityData.getCompound(DiscCellCharger.CELL_TAG));
        }
        context.temporaryData = state;
        return state;
    }

    private static void show(MovementContext context, ItemStack cell, boolean charging) {
        var display = new CompoundTag();
        display.put("Item", cell.saveOptional(context.world.registryAccess()));
        display.putBoolean(ACTIVE, charging);
        context.data.put(DISPLAY, display);
    }

    private static void sync(MovementContext context, State state) {
        show(context, state.cell(), state.charging);
        var carrier = context.contraption.entity;
        if (carrier == null) return;
        var block = context.contraption.getBlocks().get(context.localPos);
        if (block != null && block.state().getValue(DiscCellChargerBlock.CHARGING) != state.charging) {
            carrier.setBlock(context.localPos, new StructureBlockInfo(context.localPos,
                    block.state().setValue(DiscCellChargerBlock.CHARGING, state.charging), block.nbt()));
        }
        PacketDistributor.sendToPlayersTrackingEntity(carrier,
                new Sync(carrier.getId(), context.localPos, state.cell().copy(), state.charging));
    }

    private static final class State {
        final ItemStackHandler slot = new ItemStackHandler(1);
        boolean charging;

        ItemStack cell() {
            return slot.getStackInSlot(0);
        }

        void save(MovementContext context) {
            context.blockEntityData.put(DiscCellCharger.CELL_TAG, slot.serializeNBT(context.world.registryAccess()));
        }
    }

    static final class Interaction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos pos, AbstractContraptionEntity entity) {
            var held = player.getItemInHand(hand);
            if (player.isSpectator() || !held.isEmpty() && !(held.getItem() instanceof ChargeableItem)) return false;
            if (player.level().isClientSide) return true;
            var actor = entity.getContraption().getActorAt(pos);
            if (actor == null || actor.right == null || actor.right.blockEntityData == null) return false;
            MovementContext context = actor.right;
            State state = state(context);
            if (player.isShiftKeyDown() && BatteryItem.fits(held)) {
                int energy = DiscCellCharger.stored(context.blockEntityData);
                energy += ChargeableItem.drawBattery(held, DiscCellCharger.RESERVE_CAPACITY - energy);
                context.blockEntityData.putInt(DiscCellCharger.RESERVE_TAG, energy);
                DiscCellChargerBlock.showReserve(player, energy);
            } else if (held.isEmpty() && !state.cell().isEmpty()) {
                player.getInventory().placeItemBackInInventory(state.slot.extractItem(0, 1, false));
                state.charging = false;
            } else if (!held.isEmpty() && state.cell().isEmpty()) {
                state.slot.setStackInSlot(0, held.split(1));
            }
            state.save(context);
            sync(context, state);
            return true;
        }
    }

    private record Sync(int entityId, BlockPos pos, ItemStack cell, boolean charging) implements CustomPacketPayload {
        static final Type<Sync> TYPE = new Type<>(CreateMediaSystems.id("mounted_charger"));
        static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::entityId, BlockPos.STREAM_CODEC, Sync::pos,
                ItemStack.OPTIONAL_STREAM_CODEC, Sync::cell, ByteBufCodecs.BOOL, Sync::charging, Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (packet, context) -> {
            if (context.player().level().getEntity(packet.entityId) instanceof AbstractContraptionEntity entity) {
                var actor = entity.getContraption().getActorAt(packet.pos);
                if (actor != null && actor.right != null) show(actor.right, packet.cell, packet.charging);
            }
        });
    }
}
