package org.haruyq.createmediasystems.api.media;

import org.haruyq.createmediasystems.stream.ExternalSource;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What a {@link MediaBlock} plays: a track (music, or a video) its speakers and displays play in step, and a still
 * picture its displays show. Every link is one this server allows (its allowedHosts), and each client loads it itself.
 * Worked on the server.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface MediaPlayer {
    /** {@code block}'s player, made once as the block entity is. */
    static <E extends BlockEntity & MediaBlock> MediaPlayer of(E block) {
        return new ExternalSource.Media(block);
    }

    /**
     * Plays the track at {@code url} from its top, every listener loading it and starting together. {@code videoHeight}
     * is the tallest picture (pixels) its displays show of it (1080, say); 0 plays its sound alone. {@code by} is the
     * player who started it, null for none. False, changing nothing, for a link this server doesn't allow.
     */
    boolean play(String url, int videoHeight, @Nullable Player by);

    /**
     * Shows the image at {@code url} (PNG, JPEG, BMP, a GIF's first frame) on its displays while they show no video:
     * beside music, or alone. Empty takes it away. False, changing nothing, for a link this server doesn't allow.
     */
    boolean show(String url);

    /** Stops its track; its image stays. */
    void stop();

    /** Whether it plays a track: false once stopped, or once the track has ended. */
    boolean playing();

    /** Counts its track on; called every tick of its block entity on the server. */
    void tick();

    /** Saves what it plays, and how far in, into {@code tag}, under a key of its own. */
    void save(CompoundTag tag);

    /** Takes up what {@link #save} wrote into {@code tag}. */
    void load(CompoundTag tag);
}
