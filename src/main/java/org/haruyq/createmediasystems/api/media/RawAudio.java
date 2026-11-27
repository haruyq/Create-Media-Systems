package org.haruyq.createmediasystems.api.media;

import org.haruyq.createmediasystems.stream.AudioClips;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.ResourceLocation;

/**
 * Sound handed to CMS rather than found at a web address. Each gives a link that plays anywhere a link does (see
 * MediaPlayer#play, api.train.TrainAnnouncements#play).
 */
@ApiStatus.Experimental
public final class RawAudio {
    /** The largest clip, in bytes. */
    public static final int MAX_CLIP_BYTES = AudioClips.MAX_BYTES;

    private RawAudio() {
    }

    /** The link of the live feed {@code id}, which each client makes itself (see client.LiveAudioFeeds). */
    public static String feed(ResourceLocation id) {
        return MediaKind.RAW_SCHEME + id;
    }

    /**
     * Keeps {@code file} (a sound file any player plays, such as Ogg Vorbis, MP3, FLAC or WAV) on the server until it
     * stops; its link. Clients download it as they first play it. The same bytes give the same link. Server side only;
     * fails if {@code file} is empty or over {@link #MAX_CLIP_BYTES}.
     */
    public static String clip(byte[] file) {
        return AudioClips.store(file);
    }
}
