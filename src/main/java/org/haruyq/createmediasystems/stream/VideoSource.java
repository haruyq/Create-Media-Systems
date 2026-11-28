package org.haruyq.createmediasystems.stream;

import net.minecraft.server.level.ServerPlayer;

/** A source whose track may be a video, which the displays linked to it show. */
public interface VideoSource extends MediaSource {
    /**
     * The link of a still picture its displays show while they show no video of it, which every client fetches itself
     * (as an image viewer's); empty for none.
     */
    default String image() {
        return "";
    }

    /**
     * {@code player}, who listens to it, has {@code fraction} (0..1) of the video {@code url} downloaded (see
     * ClientStreams#reportDownloads): counted (see Downloads) while it still plays that video.
     */
    void downloaded(ServerPlayer player, String url, float fraction);
}
