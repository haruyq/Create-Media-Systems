package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** Shared transport for battery-powered players; the disc is never changed to represent the current entry. */
public final class PortablePlaylist {
    private PortablePlaylist() {}

    public static boolean fits(ItemStack disc) {
        return MediaItem.fits(disc, DiscKind.Slot.PORTABLE) || MediaItem.kindOf(disc) == DiscKind.CD;
    }

    public static int size(ItemStack disc) {
        return disc.getItem() instanceof MediaItem ? MediaItem.linksOf(disc).size()
                : disc.has(DataComponents.JUKEBOX_PLAYABLE) ? 1 : 0;
    }

    public static int index(ItemStack disc, int index) {
        return Math.clamp(index, 0, Math.max(0, size(disc) - 1));
    }

    @Nullable
    public static String url(ItemStack disc, int index) {
        var links = MediaItem.linksOf(disc);
        if (index < 0 || index >= links.size()) return null;
        String url = links.get(index);
        return url.isBlank() ? null : url;
    }

    public static boolean playable(ItemStack disc, int index) {
        if (index < 0 || index >= size(disc)) return false;
        if (!(disc.getItem() instanceof MediaItem)) return true;
        String url = url(disc, index);
        return url != null && UrlPolicy.isAllowed(url);
    }

    public static double duration(ItemStack disc, int index, HolderLookup.Provider registries) {
        if (!playable(disc, index)) return -1;
        String url = url(disc, index);
        return url == null ? MediaItem.lengthSeconds(disc, registries) : Streams.duration(url);
    }

    public static int find(ItemStack disc, int from, int direction) {
        for (int i = from, count = size(disc); i >= 0 && i < count; i += direction) {
            if (playable(disc, i)) return i;
        }
        return -1;
    }

    public static int control(ItemStack disc, PlaybackClock clock, int track, long now, boolean powered,
            Remotable.Action action, int value) {
        switch (action) {
            case PLAY_PAUSE -> {
                if (clock.running()) {
                    clock.pause(now);
                } else if (powered) {
                    int next = find(disc, track, 1);
                    if (next < 0) next = find(disc, 0, 1);
                    if (next < 0) return track;
                    if (next != track) select(clock, now);
                    if (clock.ended(now)) clock.seek(0, now);
                    clock.play(now);
                    return next;
                }
            }
            case STOP -> clock.stop();
            case PREVIOUS, NEXT, SELECT -> {
                int next = switch (action) {
                    case PREVIOUS -> find(disc, track - 1, -1);
                    case NEXT -> find(disc, track + 1, 1);
                    default -> value > 0 && playable(disc, value - 1) ? value - 1 : -1;
                };
                if (next >= 0 && next != track) {
                    select(clock, now);
                    return next;
                }
            }
            case SEEK, SEEK_BY -> {
                if (!playable(disc, track)) return track;
                long ticks = (long) value * PlaybackClock.TICKS_PER_SECOND;
                if (action == Remotable.Action.SEEK_BY) ticks += clock.position(now);
                long end = (long) ((clock.length() > 0 ? clock.length() : MediaItem.MAX_LENGTH_SECONDS)
                        * PlaybackClock.TICKS_PER_SECOND);
                clock.seek(Math.clamp(ticks, 0, Math.max(0, end - PlaybackClock.TICKS_PER_SECOND)), now);
            }
            case VOLUME -> { }
        }
        return track;
    }

    public record Settlement(int track, int energy) {}

    /**
     * Settle each finished entry at its boundary. If the player was unloaded, the remainder of that time belongs
     * to the following tracks, with the same battery cost as playback in a loaded chunk.
     */
    public static Settlement settle(ItemStack disc, HolderLookup.Provider registries, PlaybackClock clock,
            PlaybackClock.Drain drain, long now, int track, int energy, double rate) {
        while (clock.running()) {
            String url = url(disc, track);
            if (!playable(disc, track) || url != null && clock.length() <= 0 && Streams.refused(url)) {
                int next = find(disc, track + 1, 1);
                if (next < 0) return finish(clock, drain, now, energy, disc);
                track = next;
                clock.pause(now);
                drain.settle(clock, now, energy, 0);
                select(clock, now);
                clock.play(now);
                continue;
            }
            if (clock.length() <= 0) {
                clock.learn(duration(disc, track, registries));
            }
            if (!clock.ended(now)) break;
            long boundary = now - (clock.position(now) - (long) Math.ceil(clock.length() * PlaybackClock.TICKS_PER_SECOND));
            energy = drain.settle(clock, boundary, energy, rate);
            if (!clock.running()) return new Settlement(track, energy);
            int next = find(disc, track + 1, 1);
            if (next < 0) return finish(clock, drain, now, energy, disc);
            track = next;
            select(clock, boundary);
        }
        return new Settlement(track, drain.settle(clock, now, energy, rate));
    }

    private static Settlement finish(PlaybackClock clock, PlaybackClock.Drain drain, long now, int energy, ItemStack disc) {
        clock.stop();
        clock.forget();
        return new Settlement(Math.max(0, find(disc, 0, 1)), drain.settle(clock, now, energy, 0));
    }

    private static void select(PlaybackClock clock, long now) {
        clock.forget();
        clock.seek(0, now);
    }
}
