package org.haruyq.createmediasystems;

import java.lang.reflect.Method;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/** Ships for the Sable game tests. */
public final class Ships {
    private Ships() {}

    /**
     * Assembles a ship as {@link SubLevelAssemblyHelper#assembleBlocks} does, once the server has forgotten the chunks
     * it looked up this tick. Sable 2.0.6 gives a removed ship's plot to the next ship at once, and until the tick ends
     * the server can still answer for that plot's chunk with the removed ship's: the new ship's block entities then
     * arrive without their data, and are not found where they are. The tests of a batch share a tick, so one's ship can
     * take the plot another has just freed.
     */
    public static ServerSubLevel assemble(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, BoundingBox3ic bounds) {
        try {
            Method forget = ServerChunkCache.class.getDeclaredMethod("clearCache");
            forget.setAccessible(true);
            forget.invoke(level.getChunkSource());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
    }
}
