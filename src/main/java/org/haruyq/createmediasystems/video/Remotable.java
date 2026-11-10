package org.haruyq.createmediasystems.video;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** What a remote works from afar (see RemoteItem): a Blu-ray player, or a hi-fi stack's disc player and amplifier. */
public interface Remotable {
    /**
     * {@code action} from {@code by}'s remote; {@code value} is the entry (from 1) to select, the volume in percent,
     * the second to seek to, or the seconds to seek by.
     */
    void control(VideoPlayerBlock.Action action, int value, @Nullable Player by);

    /** The links of the disc in it; empty for none. */
    List<String> playlist();

    /** The playlist entry playing (or paused, or stopped at). */
    int index();

    /** How loud it plays, 0 to 1, as its remote's slider shows it. */
    float remoteVolume();

    /** Empty, stopped, paused or playing, as its remote shows it. */
    Component state();
}
