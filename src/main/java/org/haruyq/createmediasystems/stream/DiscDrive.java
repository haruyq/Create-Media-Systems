package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;

/** A drive beside a source, whose disc that source plays while it holds no disc of its own. */
public interface DiscDrive {
    /** The kind of its disc once it is ready to play; null while it has none ready. */
    @Nullable
    DiscKind ready();

    String link();

    /** What plays a drive's disc while it holds none of its own. */
    interface Fed {
        boolean plays(DiscKind kind);

        /** The drive beside it took a disc ({@code by} put it in) or lost it. */
        void driveChanged(@Nullable Player by);

        /**
         * The drive beside it shares the disc it has under another link, as {@code by} chose another of its sound or
         * subtitle streams: the same disc, to go on with from where it is.
         */
        default void driveRelinked(@Nullable Player by) {
            driveChanged(by);
        }
    }

    /** The drive beside {@code fed}, at {@code pos}, whose disc is ready and of a kind it plays; null for none. */
    @Nullable
    static DiscDrive feeding(@Nullable BlockGetter level, BlockPos pos, Fed fed) {
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(pos.relative(side)) instanceof DiscDrive drive && drive.ready() != null
                        && fed.plays(drive.ready())) {
                    return drive;
                }
            }
        }
        return null;
    }
}
