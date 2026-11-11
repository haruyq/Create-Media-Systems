package org.haruyq.createmediasystems.stream;

import java.util.List;
import java.util.Optional;

import org.haruyq.createmediasystems.ServerConfig;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

/**
 * An item that carries media: its kind (see DiscKind), and where on it its links are kept. Whatever plays an item asks
 * this, never which item it is. A media item without links plays nothing, never its jukebox placeholder song.
 */
public interface MediaItem {
    /** Longest a track is taken to be (seconds): a length told beyond it is wrong. */
    double MAX_LENGTH_SECONDS = 86400;

    DiscKind kind();

    /** The links {@code stack} holds, in order; empty for none. */
    List<String> links(ItemStack stack);

    @Nullable
    static DiscKind kindOf(ItemStack stack) {
        return stack.getItem() instanceof MediaItem media ? media.kind() : null;
    }

    /** The links on {@code stack}; empty for none, or for an item that is no media item. */
    static List<String> linksOf(ItemStack stack) {
        return stack.getItem() instanceof MediaItem media ? media.links(stack) : List.of();
    }

    /** Whether {@code stack} goes in {@code slot}: a media item whose kind fits it, or in a portable one any jukebox disc. */
    static boolean fits(ItemStack stack, DiscKind.Slot slot) {
        DiscKind kind = kindOf(stack);
        return kind != null ? kind.fits(slot)
                : slot == DiscKind.Slot.PORTABLE && stack.has(DataComponents.JUKEBOX_PLAYABLE);
    }

    /** The first link on {@code stack}, which a portable player plays; null for none. */
    @Nullable
    static String url(ItemStack stack) {
        List<String> links = linksOf(stack);
        return links.isEmpty() || links.getFirst().isBlank() ? null : links.getFirst();
    }

    /** Whether {@code stack} is a media item without links: it plays nothing. */
    static boolean silent(ItemStack stack) {
        return stack.getItem() instanceof MediaItem && url(stack) == null;
    }

    /** Whether this server and its clients may play {@code stack}'s link (see ServerConfig#isAllowed); one with none may. */
    static boolean allowed(ItemStack stack) {
        String url = url(stack);
        return url == null || ServerConfig.isAllowed(url);
    }

    /** The jukebox song a portable player plays from {@code stack}: none for a media item, which plays its link. */
    static Optional<Holder<JukeboxSong>> song(ItemStack stack, HolderLookup.Provider registries) {
        return stack.getItem() instanceof MediaItem ? Optional.empty() : JukeboxSong.fromStack(registries, stack);
    }

    /** Server: how long (seconds) what {@code stack} plays is; -1 while unknown, or for nothing. */
    static double lengthSeconds(ItemStack stack, HolderLookup.Provider registries) {
        String url = url(stack);
        return url != null ? Streams.duration(url)
                : song(stack, registries).map(song -> (double) song.value().lengthInSeconds()).orElse(-1.0);
    }

    /** Whether {@code seconds} can be a track's length, as a client tells it. */
    static boolean plausibleLength(double seconds) {
        return Double.isFinite(seconds) && seconds > 0 && seconds <= MAX_LENGTH_SECONDS;
    }
}
