package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardFeature;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardPackets;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.MidiKeyboardBlock;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.blocks.piano.client.NoteTrails;
import org.haruyq.createmediasystems.blocks.piano.client.NoteTrailRenderer;
import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.StreamPump;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The sound of keyboards nearby: each performer has a {@link Synth} fed their messages as they arrive
 * (see {@link #played}). Their parts are mixed and heard from the keyboard itself or from
 * each speaker linked to it. Nothing is streamed and nothing waits for a tick: a message is in the sound a few
 * milliseconds after it arrives (see {@link Tap}).
 */
public final class KeyboardAudio {
    /** GeneralUser GS, a General MIDI bank with Roland GS's variations; a resource pack may put another in its place. */
    private static final ResourceLocation BANK = CreateMediaSystems.id("keyboard/a88mkii/generaluser_gs.sf3");
    /** The speakers of one keyboard heard at once, the nearest first. */
    private static final int MAX_OUTPUTS = 6;
    private static final int[] EMPTY_SETUP = new int[0];

    /** Any thread reads these: a player's messages come in on the network thread. */
    private static final Map<BlockPos, Instance> PLAYING = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Performance> PERFORMANCES = new ConcurrentHashMap<>();
    private static volatile Map<BlockPos, MidiKeyboardBlock.Entity> near = Map.of();
    @Nullable
    private static volatile SoundFont bank;
    private static boolean bankAsked;

    private KeyboardAudio() {
    }

    /** The keyboards of the client's world that are loaded, by where they are. */
    static Map<BlockPos, MidiKeyboardBlock.Entity> near() {
        return near;
    }

    static NoteTrails trails(BlockPos pos) {
        return PERFORMANCES.computeIfAbsent(pos, ignored -> new Performance()).trails;
    }

    /** What the bank calls {@code program} (a drum kit's, for {@code drums}); null while it has none to tell. */
    @Nullable
    static String programName(int program, boolean drums) {
        SoundFont font = bank;
        return font == null ? null : font.name(drums ? SoundFont.DRUMS : 0, program);
    }

    /**
     * The player of the keyboard at {@code pos} played {@code message} (see Controller): its keys, pads and lever
     * move, and its synth, if it sounds here, plays it. Any thread.
     */
    static void played(BlockPos pos, int player, int message) {
        MidiKeyboardBlock.Entity keyboard = near.get(pos);
        if (keyboard == null && message == KeyboardPackets.END && !PERFORMANCES.containsKey(pos)) return;
        var performance = PERFORMANCES.computeIfAbsent(pos, ignored -> new Performance());
        synchronized (performance) {
            if (message == KeyboardPackets.END) {
                performance.parts.remove(player);
                performance.trails.release(player, System.nanoTime());
            } else {
                var part = performance.parts.computeIfAbsent(player, ignored -> new Part());
                var previous = part.synth;
                part.play(message, bank, part.synth == null && keyboard != null ? keyboard.setup() : EMPTY_SETUP);
                if (previous != part.synth) performance.updateSynths();
                if ((message & 255) == Controller.KEY) {
                    int note = message >> 8 & 127, velocity = message >> 16 & 127;
                    long now = System.nanoTime();
                    if (velocity > 0) performance.trails.press(player, player, note, velocity, now);
                    else performance.trails.release(player, note, now);
                }
            }
            if (message == KeyboardPackets.END) performance.updateSynths();
            performance.updateModel(keyboard);
        }
    }

    private static final class Part {
        final long[] keys = new long[2];
        int pads, bend = 8192, modulation;
        Synth synth;

        void play(int message, @Nullable SoundFont font, int[] setup) {
            int key = message >> 8 & 127, value = message >> 16 & 127;
            switch (message & 255) {
                case Controller.KEY -> {
                    int part = key >> 6;
                    keys[part] = value > 0 ? keys[part] | 1L << key : keys[part] & ~(1L << key);
                }
                case Controller.PAD -> pads = value > 0 ? pads | 1 << key : pads & ~(1 << key);
                default -> {
                    if ((message & 0xF0) == Controller.BEND) bend = key | value << 7;
                    if ((message & 0xF0) == Controller.CONTROL && key == 1) modulation = value;
                    if ((message & 0x80) != 0 && font != null) {
                        if (synth == null) {
                            synth = new Synth(font);
                            for (int initial : setup) synth.send(initial);
                        }
                        synth.send(message);
                    }
                }
            }
        }
    }

    private static final class Performance {
        final Map<Integer, Part> parts = new HashMap<>();
        final NoteTrails trails = new NoteTrails(NoteTrailRenderer::time);
        volatile List<Synth> synths = List.of();

        void updateSynths() {
            synths = parts.values().stream().map(part -> part.synth).filter(java.util.Objects::nonNull).toList();
        }

        void updateModel(@Nullable MidiKeyboardBlock.Entity keyboard) {
            if (keyboard == null) return;
            long low = 0, high = 0;
            int pads = 0, bend = 8192, modulation = 0;
            for (var part : parts.values()) {
                low |= part.keys[0];
                high |= part.keys[1];
                pads |= part.pads;
                modulation = Math.max(modulation, part.modulation);
                if (Math.abs(part.bend - 8192) > Math.abs(bend - 8192)) bend = part.bend;
            }
            keyboard.keysDown.set(0, low);
            keyboard.keysDown.set(1, high);
            keyboard.padsDown = pads;
            keyboard.modulation = modulation;
            keyboard.bend = bend;
        }
    }

    static void tick(Minecraft mc) {
        Map<BlockPos, MidiKeyboardBlock.Entity> loaded = new HashMap<>();
        if (mc.level != null && mc.getCameraEntity() != null) {
            for (MidiKeyboardBlock.Entity keyboard : MidiKeyboardBlock.Entity.LOADED) {
                if (!keyboard.isRemoved() && keyboard.getLevel() == mc.level) {
                    loaded.put(keyboard.getBlockPos(), keyboard);
                }
            }
        }
        near = loaded;
        PERFORMANCES.keySet().retainAll(loaded.keySet());
        if (!loaded.isEmpty()) {
            bank(mc);
        }
        Vec3 ear = loaded.isEmpty() ? Vec3.ZERO : mc.gameRenderer.getMainCamera().getPosition();
        PLAYING.values().removeIf(instance -> {
            MidiKeyboardBlock.Entity keyboard = loaded.get(instance.keyboard.getBlockPos());
            boolean played = keyboard != null && !keyboard.performers().isEmpty();
            if (keyboard == instance.keyboard && (played || instance.performance.synths.stream().anyMatch(synth -> !synth.silent()))
                    && heard(instance.keyboard, ear, EmitterRange.DROP_MARGIN).findAny().isPresent()) {
                return false;
            }
            instance.sounds.values().forEach(sound -> end(mc, sound));
            return true;
        });
        for (MidiKeyboardBlock.Entity keyboard : loaded.values()) {
            Instance instance = PLAYING.get(keyboard.getBlockPos());
            boolean played = !keyboard.performers().isEmpty();
            var performance = PERFORMANCES.computeIfAbsent(keyboard.getBlockPos(), ignored -> new Performance());
            synchronized (performance) { performance.updateModel(keyboard); }
            SoundFont font = bank;
            if (instance == null && played && font != null && heard(keyboard, ear, 0).findAny().isPresent()) {
                instance = new Instance(keyboard, performance);
                PLAYING.put(keyboard.getBlockPos(), instance);
            }
            if (instance != null) {
                instance.follow(mc, ear);
            }
        }
    }

    static void clear() {
        var mc = Minecraft.getInstance();
        PLAYING.values().forEach(instance -> instance.sounds.values().forEach(sound -> end(mc, sound)));
        PLAYING.clear();
        PERFORMANCES.clear();
        near = Map.of();
    }

    /** The sound bank; null until it has been read, which the first call starts. */
    @Nullable
    static SoundFont bank(Minecraft mc) {
        if (!bankAsked) {
            bankAsked = true;
            // ponytail: read once and kept until the game is closed; a resource reload doesn't read it again.
            CompletableFuture.supplyAsync(() -> read(mc), Util.ioPool()).thenAccept(font -> bank = font);
        }
        return bank;
    }

    @Nullable
    private static SoundFont read(Minecraft mc) {
        try (InputStream in = mc.getResourceManager().open(BANK)) {
            return SoundFont.read(ByteBuffer.wrap(in.readAllBytes()));
        } catch (IOException | RuntimeException failed) {
            LogUtils.getLogger().error("Could not read the keyboard's sound bank {}; keyboards stay silent", BANK, failed);
            return null;
        }
    }

    private static void end(Minecraft mc, LiveSound sound) {
        sound.end();
        mc.getSoundManager().stop(sound);
    }

    /**
     * Where a keyboard is heard from: the block it comes from, the point (in that block's space), which way it faces
     * if any, which side of the stereo sound, how loud and how far.
     */
    private record Heard(BlockPos pos, Vec3 origin, Optional<Direction> facing, Streams.Channel side, float volume,
                         double reach) {
        double distance(Level level, Vec3 ear) {
            return SableCompat.toBlockSpace(level, pos, ear).distanceTo(origin);
        }

        float gain(Level level, Vec3 ear) {
            Vec3 listener = SableCompat.toBlockSpace(level, pos, ear);
            return (float) (volume * Math.clamp(1 - listener.distanceTo(origin) / reach, 0, 1)
                    * new Streams.Sound(origin, facing, side, pos).directivity(listener));
        }
    }

    /** What {@code keyboard} is heard from that reaches {@code ear}, with {@code margin} to spare: its speakers, or itself without any. */
    private static Stream<Heard> heard(MidiKeyboardBlock.Entity keyboard, Vec3 ear, double margin) {
        BlockPos pos = keyboard.getBlockPos();
        List<MidiKeyboardBlock.Output> outputs = keyboard.outputs();
        Stream<Heard> from = outputs.isEmpty()
                ? Stream.of(new Heard(pos, Vec3.atLowerCornerOf(pos).add(MidiKeyboardBlock.place(keyboard.facing(),
                        Layout.WIDTH / 2, Layout.HEIGHT, Layout.DEPTH / 2)), Optional.empty(), Streams.Channel.MIX, 1,
                        MidiKeyboardBlock.Entity.RANGE))
                : outputs.stream().map(output -> new Heard(output.pos(), output.origin(), output.facing(),
                        output.channel(), output.volume(), EmitterRange.DEFAULT_RANGE));
        return from.filter(heard -> heard.distance(keyboard.getLevel(), ear) <= heard.reach + margin);
    }

    /** One keyboard as it sounds here. */
    private static final class Instance {
        final MidiKeyboardBlock.Entity keyboard;
        final Performance performance;
        final Tap tap;
        final Map<BlockPos, LiveSound> sounds = new HashMap<>();

        Instance(MidiKeyboardBlock.Entity keyboard, Performance performance) {
            this.keyboard = keyboard;
            this.performance = performance;
            tap = new Tap(performance);
        }

        void follow(Minecraft mc, Vec3 ear) {
            Level level = keyboard.getLevel();
            List<Heard> wanted = heard(keyboard, ear, EmitterRange.DROP_MARGIN)
                    .sorted(Comparator.comparingDouble(heard -> heard.distance(level, ear))).limit(MAX_OUTPUTS).toList();
            sounds.values().removeIf(sound -> {
                if (wanted.contains(sound.heard) && mc.getSoundManager().isActive(sound)) {
                    return false;
                }
                end(mc, sound);
                return true;
            });
            for (Heard heard : wanted) {
                if (!sounds.containsKey(heard.pos)) {
                    LiveSound sound = new LiveSound(this, heard);
                    sounds.put(heard.pos, sound);
                    mc.getSoundManager().play(sound);
                }
            }
        }
    }

    /**
     * The keyboard's mix as its streams read it. Every stream (one per speaker) reads the same frames,
     * so the synths render them once, into a ring the others then read from; they render as a stream asks for
     * more, {@link #chunk} frames at a time, so what a message has to wait behind is the few chunks queued. All on
     * the sound thread.
     */
    private static final class Tap {
        static final int SIZE = 1 << 14, MAX_CHUNK = 2048;
        /** Chunks a channel keeps queued (see Channel#pumpBuffers). */
        static final int QUEUED = 4;
        final Performance performance;
        final float[] left = new float[SIZE], right = new float[SIZE];
        final float[] mixLeft = new float[Synth.BLOCK], mixRight = new float[Synth.BLOCK];
        long rendered;
        /**
         * Frames read at a time: as few as keep a source from running dry (see {@link #fit}), and more whenever one
         * did all the same.
         */
        int chunk = 4 * Synth.BLOCK;
        private long grownAt;

        Tap(Performance performance) {
            this.performance = performance;
        }

        void read(long from, int frames, Streams.Channel side, ByteBuffer out) {
            while (rendered < from + frames) {
                int at = (int) (rendered & SIZE - 1);
                var parts = performance.synths;
                java.util.Arrays.fill(left, at, at + Synth.BLOCK, 0);
                java.util.Arrays.fill(right, at, at + Synth.BLOCK, 0);
                for (var synth : parts) {
                    synth.render(mixLeft, mixRight, 0, 1);
                    for (int i = 0; i < Synth.BLOCK; i++) {
                        left[at + i] += mixLeft[i];
                        right[at + i] += mixRight[i];
                    }
                }
                rendered += Synth.BLOCK;
            }
            out.clear();
            for (int i = 0; i < frames; i++) {
                int at = (int) (from + i & SIZE - 1);
                float sample = side == Streams.Channel.LEFT ? left[at] : side == Streams.Channel.RIGHT ? right[at]
                        : (left[at] + right[at]) * 0.7f;
                out.putShort((short) Math.round(Math.clamp(sample, -1, 1) * 32767));
            }
            out.flip();
        }

        /**
         * The sound device mixes {@code period} frames at a time, taking them off a source's queue at once: the queue
         * holds that and half as much again (6 ms at least) for the refill to come in time.
         */
        void fit(int period) {
            int queued = period + Math.max(period / 2, Synth.RATE * 6 / 1000);
            chunk = Math.clamp((queued / QUEUED + Synth.BLOCK - 1) / Synth.BLOCK * Synth.BLOCK, chunk, MAX_CHUNK);
        }

        /** A source ran dry: half as much again is read at a time from now on. Once for all the sources it happened to at once. */
        void ranDry() {
            long now = System.nanoTime();
            if (now - grownAt > 500_000_000L) {
                grownAt = now;
                chunk = Math.min(MAX_CHUNK, (chunk * 3 / 2 + Synth.BLOCK - 1) / Synth.BLOCK * Synth.BLOCK);
            }
        }
    }

    /** One side of a keyboard's sound, read off its {@link Tap} for one channel. */
    private static final class LiveStream implements CmsStream {
        private static final AudioFormat FORMAT = new AudioFormat(Synth.RATE, 16, 1, true, false);
        private final Tap tap;
        private final Streams.Channel side;
        private final LiveSound sound;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(Tap.MAX_CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN);
        private final Runnable keepPlaying = this::keepPlaying;
        private int source = -1;
        private long position = -1;
        private boolean closed;

        LiveStream(Tap tap, Streams.Channel side, LiveSound sound) {
            this.tap = tap;
            this.side = side;
            this.sound = sound;
            StreamPump.live(keepPlaying);
        }

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public long attach(int source, long deviceNow) {
            this.source = source;
            position = -1;
            long context = ALC10.alcGetCurrentContext();
            int refresh = context == 0 ? 0 : ALC10.alcGetInteger(ALC10.alcGetContextsDevice(context), ALC10.ALC_REFRESH);
            if (refresh > 0) {
                tap.fit(Synth.RATE / refresh);
            }
            return 0;
        }

        /** Unpaused, it plays what is played then, not what was queued when the game paused. */
        @Override
        public boolean advancesWhilePaused() {
            return true;
        }

        @Override
        public ByteBuffer read(int size) {
            int frames = tap.chunk;
            // A stream that starts, or fell a ring behind, takes up where the others are: what they have queued.
            if (position < 0 || position < tap.rendered - Tap.SIZE + frames) {
                position = Math.max(0, tap.rendered - (long) Tap.QUEUED * frames);
            }
            tap.read(position, frames, side, buffer);
            position += frames;
            return buffer;
        }

        /**
         * After each refill: a source that played all it had queued has stopped, and is started again with what was
         * just queued (the engine would drop the sound at its next tick).
         */
        private void keepPlaying() {
            if (!closed && source >= 0 && !sound.isStopped()
                    && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                tap.ranDry();
                AL10.alSourcePlay(source);
            }
        }

        @Override
        public void close() {
            closed = true;
            StreamPump.ended(keepPlaying);
        }
    }

    /** A keyboard heard from one place: the keyboard, or a speaker. */
    private static final class LiveSound extends AbstractTickableSoundInstance {
        private final Instance instance;
        final Heard heard;

        LiveSound(Instance instance, Heard heard) {
            super(KeyboardFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.instance = instance;
            this.heard = heard;
            relative = false;
            attenuation = Attenuation.NONE;
            pitch = 1;
            tick();
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            Level level = instance.keyboard.getLevel();
            if (level == null || level != mc.level) {
                stop();
                return;
            }
            Vec3 at = SableCompat.toWorld(level, heard.pos, heard.origin);
            x = at.x;
            y = at.y;
            z = at.z;
            volume = heard.gain(level, mc.gameRenderer.getMainCamera().getPosition());
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new LiveStream(instance.tap, heard.side, this));
        }
    }
}
