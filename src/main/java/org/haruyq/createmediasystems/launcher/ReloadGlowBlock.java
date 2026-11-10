package org.haruyq.createmediasystems.launcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Legacy white light, kept registered only so old saves can clean it up through their scheduled tick. */
public final class ReloadGlowBlock extends Block {
    private static final Map<GlobalPos, Long> UNTIL = new ConcurrentHashMap<>();
    public ReloadGlowBlock(Properties properties) { super(properties); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    public static void illuminate(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos pos = player.blockPosition().above();
        BlockState state = level.getBlockState(pos);
        if (!state.is(CreateMediaSystems.RELOAD_GLOW.get()) && !state.isAir()) return;
        UNTIL.put(GlobalPos.of(level.dimension(), pos), level.getGameTime() + 8);
        if (!state.is(CreateMediaSystems.RELOAD_GLOW.get())) {
            level.setBlockAndUpdate(pos, CreateMediaSystems.RELOAD_GLOW.get().defaultBlockState());
            level.scheduleTick(pos, CreateMediaSystems.RELOAD_GLOW.get(), 8);
        }
    }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        if (UNTIL.getOrDefault(key, Long.MIN_VALUE) > level.getGameTime()) level.scheduleTick(pos, this, 8);
        else {
            UNTIL.remove(key);
            level.removeBlock(pos, false);
        }
    }
}
