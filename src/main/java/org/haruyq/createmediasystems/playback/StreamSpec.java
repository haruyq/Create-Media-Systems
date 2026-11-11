package org.haruyq.createmediasystems.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What a {@link StreamSound} plays and how it is heard: its track from {@code startSeconds} in, on {@code channel},
 * from {@code emitter}; {@code started} is told (from the loader thread) once the audio is loaded and lined up to
 * play, with the track's length in seconds as the native player sees it (0 if unknown or live). The rest is set by
 * the methods below: what plays it on the server, a moving disc's clock, the PCM decorators its samples go through
 * (in order), and whether it streams from the API even where the native player could play it.
 */
public record StreamSpec(String track, double startSeconds, Streams.Channel channel, SoundSource category,
                         Emitter emitter, DoubleConsumer started, @Nullable BlockPos source, long load,
                         @Nullable Streams.Video video, @Nullable DiscClock disc,
                         List<UnaryOperator<AudioStream>> decorators, boolean apiOnly) {

    /**
     * Where it is heard from and whether. {@code position} is where it plays from now, asked every tick (null: at the
     * listener); {@code tracedFrom} where Sound Physics traces it from (null: where it plays from). It stops at once
     * when {@code present} turns false after having been true (disc taken out, block gone), and once either that or
     * {@code nearby} has been false for a second, fading out meanwhile. Volume follows {@code gain} every tick
     * (OpenAL's own falloff is off), fading to it; {@code pitch}, if any, is a moving emitter's Doppler pitch.
     */
    public record Emitter(@Nullable Supplier<Vec3> position, @Nullable Supplier<Vec3> tracedFrom,
                          BooleanSupplier present, BooleanSupplier nearby, DoubleSupplier gain,
                          @Nullable DoubleSupplier pitch) {
    }

    /**
     * A moving disc's playback (see {@link #movingDisc}): its own identity, not the URL or a time tolerance, and a
     * server-owned clock its player opens at and is held to.
     */
    public record DiscClock(UUID id, DoubleSupplier seconds) {
        /** Where its track is now (ms). */
        long millis() {
            return Math.max(0, Math.round(seconds.getAsDouble() * 1000));
        }
    }

    /** A stream played at once, by nothing on the server: an announcement, a portable emitter. */
    public StreamSpec(String track, double startSeconds, Streams.Channel channel, SoundSource category,
                      Emitter emitter, DoubleConsumer started) {
        this(track, startSeconds, channel, category, emitter, started, null, 0, null, null, List.of(), false);
    }

    /**
     * Played by the source block at {@code source}, its {@code load} (see Streams.Load; 0 if unknown): it waits for the
     * server's word to play, so every listener starts it together. With {@code video}, its player also plays the
     * video that Blu-ray player's displays show.
     */
    public StreamSpec from(BlockPos source, long load, @Nullable Streams.Video video) {
        return new StreamSpec(track, startSeconds, channel, category, emitter, started, source.immutable(), load,
                video, disc, decorators, apiOnly);
    }

    /** A moving disc's: a playback of its own ({@code id}), held to {@code seconds}, the server's clock of it. */
    public StreamSpec movingDisc(UUID id, DoubleSupplier seconds) {
        return new StreamSpec(track, startSeconds, channel, category, emitter, started, source, load, video,
                new DiscClock(id, seconds), decorators, apiOnly);
    }

    /** Its samples also go through {@code decorator} (an emitter's own sound: bass, a horn), after those before. */
    public StreamSpec decorated(UnaryOperator<AudioStream> decorator) {
        List<UnaryOperator<AudioStream>> all = new ArrayList<>(decorators);
        all.add(decorator);
        return new StreamSpec(track, startSeconds, channel, category, emitter, started, source, load, video, disc,
                List.copyOf(all), apiOnly);
    }

    /** Streamed from the API even where the native player could play it. */
    public StreamSpec viaApi() {
        return new StreamSpec(track, startSeconds, channel, category, emitter, started, source, load, video, disc,
                decorators, true);
    }

    /** The same, from {@code seconds} into its track: a stream started again where it was. */
    StreamSpec startingAt(double seconds) {
        return new StreamSpec(track, seconds, channel, category, emitter, started, source, load, video, disc,
                decorators, apiOnly);
    }

    /** The playback it plays, asked for at {@code requestedAt} (System.nanoTime). */
    PlaybackId playback(long requestedAt) {
        return PlaybackId.of(track, source, load, disc == null ? null : disc.id(), startSeconds, requestedAt);
    }

    /** The API's stream of it (see ApiHttp#streamUrl), from {@code offset} seconds past its start. */
    String request(String apiBase, double offset) {
        return ApiHttp.streamUrl(apiBase, track, startSeconds + offset, channel.api);
    }

    /**
     * Which download it reads (see Feed): the request from the top of its track, the same for every stream of it on
     * this channel whatever its start, so the speakers of one source share one download.
     */
    String feedKey(String apiBase) {
        return ApiHttp.streamUrl(apiBase, track, 0, channel.api);
    }

    /** {@code opened} through its decorators. */
    AudioStream decorate(AudioStream opened) {
        for (UnaryOperator<AudioStream> decorator : decorators) {
            opened = decorator.apply(opened);
        }
        return opened;
    }
}
