package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.SableCompat;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A bottom shaft supplies kinetic power; standard FE cables can supply this same dock instead. */
public final class DiscCellChargerBlock extends KineticBlock implements IBE<DiscCellCharger>,
        SableCompat.SilentAssemblyRemoval {
    public static final BooleanProperty CHARGING = BooleanProperty.create("charging");
    public DiscCellChargerBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(CHARGING, false)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(CHARGING); }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return Direction.Axis.Y; }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) { return face == Direction.DOWN; }
    @Override public Class<DiscCellCharger> getBlockEntityClass() { return DiscCellCharger.class; }
    @Override public BlockEntityType<? extends DiscCellCharger> getBlockEntityType() { return LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Block.box(2, 0, 2, 14, 11, 14);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(held.getItem() instanceof ChargeableItem)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DiscCellCharger charger) {
            if (player.isShiftKeyDown() && BatteryItem.fits(held)) {
                charger.supplyReserve(player, held);
            } else if (charger.cell().isEmpty()) {
                charger.insert(held.split(1));
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    static void showReserve(Player player, int energy) {
        player.displayClientMessage(Component.translatable("equipment.reserve", energy, DiscCellCharger.RESERVE_CAPACITY), true);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DiscCellCharger charger)
            player.getInventory().placeItemBackInInventory(charger.take());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!moved && !level.isClientSide && !state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof DiscCellCharger charger) {
            ItemStack cell = charger.take();
            if (!cell.isEmpty()) Containers.dropItemStack(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, cell);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
