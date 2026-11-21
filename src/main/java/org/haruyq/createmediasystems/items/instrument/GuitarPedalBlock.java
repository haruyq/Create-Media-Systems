package org.haruyq.createmediasystems.items.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Placement, patching and bypass behavior shared by the guitar's floor pedals. */
public abstract class GuitarPedalBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private final AutoEffect effect;

    protected GuitarPedalBlock(AutoEffect effect, Properties properties) {
        super(properties);
        this.effect = effect;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(BlockStateProperties.LIT, true));
    }

    public final AutoEffect effect() {
        return effect;
    }

    protected final boolean enabled(BlockState state) {
        return state.getValue(BlockStateProperties.LIT);
    }

    abstract GuitarPatch.Setting setting(GuitarPatch.Link link, Level level, BlockState state);

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, BlockStateProperties.LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        return stack.isEmpty() && player.getOffhandItem().isEmpty()
                ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) press(level, pos, state, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    protected void press(Level level, BlockPos pos, BlockState state, Player player) {
        BlockState next = state.cycle(BlockStateProperties.LIT);
        level.setBlockAndUpdate(pos, next);
        String key = enabled(next) ? "enabled" : "bypassed";
        player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems." + key, effect.displayName()), true);
    }
}
