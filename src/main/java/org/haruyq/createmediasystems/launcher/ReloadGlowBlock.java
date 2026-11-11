package org.haruyq.createmediasystems.launcher;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Legacy white light, kept registered only so old saves can clean it up through their scheduled tick. */
public final class ReloadGlowBlock extends Block {
    public ReloadGlowBlock(Properties properties) { super(properties); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.removeBlock(pos, false);
    }
}
