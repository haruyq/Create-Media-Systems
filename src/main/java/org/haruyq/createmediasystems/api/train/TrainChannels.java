package org.haruyq.createmediasystems.api.train;

import java.util.Set;

import org.haruyq.createmediasystems.blocks.train.ChannelHolder;
import org.haruyq.createmediasystems.blocks.train.TrainSignals;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Train detectors' channels: a channel is on while a train is over any detector set to it, however far away and
 * even with that detector's chunk unloaded, and every train signal receiver on it gives a redstone signal then.
 * The server keeps which are on; ask it there.
 */
@ApiStatus.Experimental
public final class TrainChannels {
    /** Longest a channel's name may be (characters). */
    public static final int MAX_LENGTH = TrainSignals.MAX_CHANNEL_LENGTH;

    private TrainChannels() {
    }

    /** Whether {@code channel} is on: a train is over one of its detectors. False for "". */
    public static boolean isOn(MinecraftServer server, String channel) {
        return TrainSignals.get(server).isActive(channel);
    }

    /** Every channel that is on, as of the last server tick. */
    public static Set<String> on(MinecraftServer server) {
        return TrainSignals.get(server).active();
    }

    /**
     * The channel of the train detector or train signal receiver at {@code pos} ("" for one set to none), or null for
     * neither there. On a client too, as far as it has the block.
     */
    @Nullable
    public static String channel(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChannelHolder holder ? holder.channel() : null;
    }

    /**
     * Sets the channel of the train detector or train signal receiver at {@code pos}, as a player does through its
     * screen: "" for none; its spaces at either end are dropped. Server. Whether it was set: false for neither block
     * there, a client's level, or a name over {@link #MAX_LENGTH} or with control characters.
     */
    public static boolean setChannel(Level level, BlockPos pos, String channel) {
        String normal = TrainSignals.normalize(channel);
        if (level.isClientSide() || normal.isEmpty() && !channel.isBlank()
                || !(level.getBlockEntity(pos) instanceof ChannelHolder holder)) {
            return false;
        }
        holder.setChannel(normal);
        return true;
    }

    /** Told of a channel turning on or off. */
    @FunctionalInterface
    @ApiStatus.OverrideOnly
    public interface Change {
        void changed(MinecraftServer server, String channel, boolean on);
    }

    /** Runs {@code change} on the server at the end of each tick a channel turned on or off, once for each. */
    public static void onChange(Change change) {
        TrainSignals.onChange(change::changed);
    }
}
