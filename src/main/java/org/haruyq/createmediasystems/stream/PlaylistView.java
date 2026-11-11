package org.haruyq.createmediasystems.stream;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** A source that plays a playlist: its links, and which of them it is at. */
public interface PlaylistView {
    /** The links it plays in order; empty for none. */
    List<String> playlist();

    /** The entry playing (or paused, or stopped at). */
    int index();

    /** The entry it shows as at: {@link #index()}, kept within its playlist; -1 for an empty one. */
    default int shownIndex() {
        return Math.min(index(), playlist().size() - 1);
    }

    default MutableComponent track() {
        return Component.translatable("create_media_systems.video_player.track", shownIndex() + 1, playlist().size());
    }
}
