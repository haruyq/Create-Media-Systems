package org.haruyq.createmediasystems.blocks.piano;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class PianoPlacement {
    private PianoPlacement() {}

    public static boolean fits(Level level, BlockPos root, BlockPos pos, VoxelShape shape, @Nullable Player player) {
        if (!level.isLoaded(pos) || !SableCompat.sameSubLevel(level, root, pos)) return false;
        var inWorld = BlockPos.containing(SableCompat.toWorld(level, pos, pos.getCenter()));
        return level.isInWorldBounds(inWorld) && level.getWorldBorder().isWithinBounds(inWorld)
                && (player == null || SableCompat.mayInteract(level, player, pos))
                && level.isUnobstructed(null, shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static boolean supported(Level level, BlockPos pos, Direction up) {
        var floor = pos.relative(up.getOpposite());
        return level.isLoaded(floor) && level.getBlockState(floor).isFaceSturdy(level, floor, up);
    }

    public static boolean install(Level level, Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        for (var entry : blocks.entrySet()) {
            var pos = entry.getKey();
            var previous = level.getBlockState(pos);
            if (previous.equals(entry.getValue())) continue;
            if (!level.setBlock(pos, entry.getValue(), Block.UPDATE_ALL)) {
                placed.forEach((at, state) -> level.setBlock(at, state, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON));
                return false;
            }
            placed.put(pos, previous);
        }
        return true;
    }
}
