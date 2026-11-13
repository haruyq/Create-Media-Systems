package org.haruyq.createmediasystems.api.lighting;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * A lighting console block, as another mod's page or fixture sees it. A formed console is several blocks; its
 * settings and fixtures are its {@link #controller()}'s.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface ConsoleView {
    BlockPos getBlockPos();

    @Nullable
    Level getLevel();

    boolean isRemoved();

    /** Whether it is part of a whole console. */
    boolean formed();

    /** The whole console's block that holds its settings; null while it is not formed. */
    @Nullable
    ConsoleView controller();

    /** Its fixtures' positions, in their order. */
    List<BlockPos> fixtures();

    /** The group (0 to 5) of the fixture at {@code fixture}; 0 if it is not linked. */
    int group(BlockPos fixture);

    /**
     * The fixture at {@code fixture}'s place among its group's, from 0: left to right as the console's player sees
     * them, then near to far, then in the order they were linked.
     */
    int order(BlockPos fixture);

    /** How many fixtures {@code group} has, at least 1. */
    int groupSize(int group);

    /** The family of {@code group}'s fixtures, or null while it has none. */
    @Nullable
    String family(int group);

    /**
     * {@code owner}'s own settings for this console (a page's, say): saved with it and sent to players on
     * {@link #changed}. Change them on the server; a client's copy is replaced on sync.
     */
    CompoundTag extras(String owner);

    /** Saves it and, on the server, sends it to players. */
    void changed();

    @Nullable
    BlockPos audioSource();

    @Nullable
    BlockPos videoSource();

    boolean hasAudio();

    boolean hasVideo();

    /** Its sound's levels at {@code gameTime}; null without a sound source playing. */
    @Nullable
    AudioLevels audio(long gameTime);

    /** Its picture's levels and colours at {@code gameTime}; null without a video source playing. */
    @Nullable
    AudioLevels video(long gameTime);

    /** Where the lights' show is in the playing track. */
    ConsolePlayhead playhead();

    boolean enabled();

    /** The master level, 0 to 1. */
    float master();

    int selectedGroup();

    int selectedColor();

    /** Group {@code group}'s settings (0 to 5). */
    GroupView groupSettings(int group);

    /**
     * Changes a setting the console keeps as its own controls do: {@code group} it applies to, and the slot
     * {@code index}, {@code value} and {@code other} as that control takes. On a client, here and on the server, which
     * takes it as the player's own (within reach, with an empty hand or a wrench). On the server, at once and for
     * every player, whoever is near: what a mod running the show there calls.
     */
    void set(ConsoleSetting setting, int group, int index, float value, float other);

    /**
     * Has {@code group}'s lights follow {@code player} (under Motion.TRACK, set with ConsoleSetting.MOTION), or
     * nobody with null. Server only.
     */
    void track(int group, @Nullable UUID player);
}
