package org.haruyq.createmediasystems.stream;

import net.minecraft.server.level.ServerPlayer;

/** A source whose track may be a video, which the displays linked to it show. */
public interface VideoSource extends MediaSource {
    /** How brightly its displays light their surroundings, 0..1 (0: not at all); the same for everyone. */
    default float lighting() {
        return 1;
    }

    /**
     * {@code player}, who listens to it, has {@code fraction} (0..1) of the video {@code url} downloaded (see
     * ClientStreams#reportDownloads): counted (see Downloads) while it still plays that video.
     */
    void downloaded(ServerPlayer player, String url, float fraction);
}
