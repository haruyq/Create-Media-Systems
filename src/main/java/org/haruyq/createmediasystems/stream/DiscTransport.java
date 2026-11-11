package org.haruyq.createmediasystems.stream;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * A disc player's transport: the entry of its playlist it is at, whether it plays or is paused, and how far into the
 * entry it is (its clock); worked by its buttons and remote (see Remotable.Action), and moving on as an entry ends. The
 * Blu-ray player and the hi-fi's disc player each hold one. A CD player's buttons (see {@code cdButtons}) stop at the
 * first track, pick a track while stopped, and go back to the top of the track playing a moment into it.
 */
public final class DiscTransport {
    /** How far into a track (ticks) a CD player's PREVIOUS goes back to its top rather than to the track before. */
    static final int RESTART_TICKS = 3 * PlaybackClock.TICKS_PER_SECOND;

    /** What holds a transport: the playlist it plays, and what follows as it changes. */
    public interface Deck {
        /** The links of the disc in it, in order; empty for none. */
        List<String> playlist();

        /**
         * A new run of the entry playing began, from where its clock is: an entry was played ({@code sought} false) or
         * sought to. {@code by} worked it; null when the entry before it ended.
         */
        default void started(@Nullable Player by, boolean sought) {
        }

        /** Anything about it changed: saved, and sent to clients. */
        void changed();
    }

    /**
     * The NBT keys its holder keeps it under. It saves where it is under the first {@code position} key, and adds them
     * all up as it loads: an older save split it in two.
     */
    public record Keys(String index, String playing, String paused, String... position) {
    }

    private final Deck deck;
    private final boolean cdButtons;
    private final PlaybackClock clock;
    private int index;
    /** Whether an entry plays, or is paused: false while stopped. */
    private boolean playing;

    public DiscTransport(Deck deck, boolean cdButtons, PlaybackClock clock) {
        this.deck = deck;
        this.cdButtons = cdButtons;
        this.clock = clock;
    }

    public PlaybackClock clock() {
        return clock;
    }

    public int index() {
        return index;
    }

    public boolean playing() {
        return playing;
    }

    public boolean paused() {
        return playing && !clock.running();
    }

    /**
     * What it plays: the entry it is at, unless stopped or paused, as far in as its clock was at its last tick; a video
     * from {@code at} if {@code kind} has a picture, with the entry after it, which clients get ready ahead. Null for
     * nothing.
     */
    @Nullable
    public Streams.Source source(BlockPos at, @Nullable DiscKind kind) {
        List<String> playlist = deck.playlist();
        if (!playing || !clock.running() || index >= playlist.size()) {
            return null;
        }
        Streams.Video video = kind == null || !kind.hasPicture() ? null : new Streams.Video(at, kind.pictureHeight,
                index + 1 < playlist.size() ? Optional.of(playlist.get(index + 1)) : Optional.empty());
        return new Streams.Source(playlist.get(index), clock.position(), video);
    }

    /** A disc went in (or out): its first entry plays, as {@code by} put it in; with none, it stops. */
    public void insert(@Nullable Player by) {
        index = 0;
        if (deck.playlist().isEmpty()) {
            stop();
        } else {
            play(0, by);
        }
    }

    /** Plays entry {@code entry} from the top, if the playlist has it, as {@code by} asked (null: the one before ended). */
    public void play(int entry, @Nullable Player by) {
        if (entry < 0 || entry >= deck.playlist().size()) {
            return;
        }
        index = entry;
        playing = true;
        clock.start(0);
        deck.started(by, false);
        deck.changed();
    }

    /** Stops: back to the top of the entry, and on a CD player to the first track. */
    public void stop() {
        playing = false;
        clock.stop();
        if (cdButtons) {
            index = 0;
        }
        deck.changed();
    }

    /**
     * {@code action} from {@code by}'s buttons or remote at game time {@code now}; {@code value} is the entry (from 1)
     * to select, the second to seek to, or the seconds to seek by. Its holder sets its own volume.
     */
    public void control(Remotable.Action action, int value, @Nullable Player by, long now) {
        int last = deck.playlist().size() - 1;
        switch (action) {
            case PLAY_PAUSE -> {
                if (!playing) {
                    play(Math.min(index, last), by);
                } else {
                    if (clock.running()) {
                        clock.pause(now);
                    } else {
                        clock.resume();
                    }
                    deck.changed();
                }
            }
            case STOP -> stop();
            case PREVIOUS -> {
                if (cdButtons && !playing) {
                    select(index - 1);
                } else {
                    play(cdButtons && clock.position(now) > RESTART_TICKS ? index : Math.max(index - 1, 0), by);
                }
            }
            case NEXT -> {
                if (cdButtons && !playing) {
                    select(Math.min(index + 1, last));
                } else {
                    play(index + 1, by);
                }
            }
            case SELECT -> play(value - 1, by);
            case SEEK -> seek((long) value * PlaybackClock.TICKS_PER_SECOND, by);
            case SEEK_BY -> seek(clock.position(now) + (long) value * PlaybackClock.TICKS_PER_SECOND, by);
            case VOLUME -> {
            }
        }
    }

    /** Stopped, a CD player's PREVIOUS and NEXT pick the track to play. */
    private void select(int entry) {
        index = Math.max(entry, 0);
        deck.changed();
    }

    /**
     * The entry playing (or paused) goes on from {@code to} ticks in: as a new run of it (see Deck#started), every
     * listener loads it there and they start together.
     */
    private void seek(long to, @Nullable Player by) {
        List<String> playlist = deck.playlist();
        if (!playing || index >= playlist.size()) {
            return;
        }
        clock.seek(Streams.seekable(playlist.get(index), to));
        deck.started(by, true);
        deck.changed();
    }

    /**
     * Server, each tick at game time {@code now} once its clock has ticked: past the end of the entry playing, it moves
     * on to the next, or stops after the last.
     */
    public void advance(long now) {
        if (!playing) {
            return;
        }
        List<String> playlist = deck.playlist();
        if (index >= playlist.size()) {
            stop();
        } else if (clock.ended(playlist.get(index), now)) {
            if (index + 1 < playlist.size()) {
                play(index + 1, null);
            } else {
                stop();
            }
        }
    }

    /** Empty (no disc, or none with links), stopped, paused or playing, as its remote and goggles show it. */
    public Component state() {
        return Component.translatable(deck.playlist().isEmpty() ? "create_media_systems.video_player.empty"
                : !playing ? "create_media_systems.video_player.stopped"
                : paused() ? "create_media_systems.video_player.paused" : "create_media_systems.video_player.playing");
    }

    public void save(CompoundTag tag, Keys keys) {
        tag.putInt(keys.index(), index);
        tag.putBoolean(keys.playing(), playing);
        tag.putBoolean(keys.paused(), paused());
        tag.putLong(keys.position()[0], clock.position());
    }

    public void load(CompoundTag tag, Keys keys) {
        index = Math.max(0, tag.getInt(keys.index()));
        playing = tag.getBoolean(keys.playing());
        long position = 0;
        for (String key : keys.position()) {
            position += tag.getLong(key);
        }
        clock.load(position, playing && !tag.getBoolean(keys.paused()));
    }
}
