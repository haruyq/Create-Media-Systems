package org.haruyq.createmediasystems.playback;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Music played from a thing that moves through the world on its own server clock (a launched disc, a PartyBox, a
 * megaphone; see stream.Portable), as this client sees it now: what EmitterAudioManager plays for this listener.
 */
public interface MusicEmitter {
    /** Which one it is: the same while the same thing plays on (an entity's UUID, a held item's own id). */
    UUID id();

    /** Its clock's revision: another is another playback (played, paused or moved on). */
    int revision();

    /** What it plays: a media item's link, or a jukebox record. */
    ItemStack media();

    /** Whether it is still in this world, playing, and heard at all. */
    boolean present();

    /** Where its music comes from now. */
    Vec3 position();

    /**
     * How fast it moves (blocks/tick), for its Doppler pitch; null for none: it stands still or jumped (see
     * DopplerMath#step).
     */
    @Nullable
    Vec3 velocity();

    /** Where in its track its server clock is now (s). */
    double seconds();

    /** How far it is heard (blocks). */
    double range();

    /** How loud it is for {@code ear}, the camera (0..1). */
    double gain(Entity ear);

    /** Its own sound, which its samples go through in order: a PartyBox's bass, a megaphone's horn. */
    default List<UnaryOperator<AudioStream>> decorators() {
        return List.of();
    }

    /** How many emitters of its kind play at once here, the nearest first. */
    int cap();

    /** Whether its link streams from the API even where the native player could play it. */
    default boolean viaApi() {
        return false;
    }

    /** How much of its sound reaches {@code distance} blocks away: half at {@code half}, and less on. */
    static double falloff(double distance, double half) {
        double d = Math.max(0, distance) / half;
        return 1 / (1 + d * d);
    }
}
