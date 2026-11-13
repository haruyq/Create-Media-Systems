package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardFeature;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.MidiKeyboardBlock;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.playback.AudibleTimeline;
import org.haruyq.createmediasystems.playback.CmsStream;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The piano piece the keyboard plays in its Ponder scene: heard from a synth of its own, its keys moving as it is
 * heard. Scrubbing the scene starts the sound over from there; pausing it stops the sound.
 */
final class PonderPerformance {
    private static final String ASSET = "/assets/create_media_systems/keyboard/a88mkii/spring.mid";
    static final int FRAMES_PER_TICK = Synth.RATE / 20;
    /** Ticks the last notes ring on after the piece. */
    private static final int TAIL_TICKS = 40;
    static final Score SCORE = Score.load();
    private static final Map<PonderScene, Show> SHOWS = new WeakHashMap<>();

    @Nullable
    private static Player player;
    @Nullable
    private static Show shown;
    private static int time, still;

    private PonderPerformance() {
    }

    /** A MIDI file's channel messages (see Synth#message), each with the frame it plays at, in order. */
    record Score(int[] frames, int[] messages) {
        int length() {
            return frames.length == 0 ? 0 : frames[frames.length - 1];
        }

        static Score load() {
            try (var input = PonderPerformance.class.getResourceAsStream(ASSET)) {
                if (input == null) {
                    throw new IOException("Missing " + ASSET);
                }
                return read(MidiSystem.getSequence(new BufferedInputStream(input)));
            } catch (IOException | InvalidMidiDataException e) {
                LogUtils.getLogger().warn("Could not load the MIDI keyboard's Ponder performance", e);
                return new Score(new int[0], new int[0]);
            }
        }

        private static Score read(Sequence sequence) throws InvalidMidiDataException {
            if (sequence.getDivisionType() != Sequence.PPQ) {
                throw new InvalidMidiDataException("Expected PPQ time");
            }
            List<MidiEvent> events = new ArrayList<>();
            for (var track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    events.add(track.get(i));
                }
            }
            events.sort(Comparator.comparingLong(MidiEvent::getTick));
            int[] frames = new int[events.size()], messages = new int[events.size()];
            int count = 0, tempo = 500_000;
            long last = 0, elapsed = 0, unitsPerSecond = 1_000_000L * sequence.getResolution();
            for (MidiEvent event : events) {
                elapsed += (event.getTick() - last) * tempo;
                last = event.getTick();
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    tempo = (data[0] & 255) << 16 | (data[1] & 255) << 8 | data[2] & 255;
                } else if (event.getMessage() instanceof ShortMessage message && message.getStatus() < 0xF0) {
                    frames[count] = Math.toIntExact(elapsed * Synth.RATE / unitsPerSecond);
                    messages[count++] = message.getStatus() | message.getData1() << 8 | message.getData2() << 16;
                }
            }
            return new Score(Arrays.copyOf(frames, count), Arrays.copyOf(messages, count));
        }
    }

    /** The piece, from a key frame with a word on it, to its end: the rest of the scene. */
    static void play(CreateSceneBuilder scene, Vec3 keys) {
        Show show = new Show();
        SHOWS.put(scene.getScene(), show);
        scene.overlay().showText(80).attachKeyFrame().pointAt(keys).placeNearTarget()
                .text("Here is a piano piece played on it.");
        scene.addInstruction(ponder -> show.start = ponder.getCurrentTime());
        scene.idle(Math.ceilDiv(SCORE.length(), FRAMES_PER_TICK) + TAIL_TICKS);
    }

    /** One scene's performance: the scene time it starts at, and its keys as last shown. */
    private static final class Show {
        int start = -1;
        private final long[] keys = new long[2];
        private int shownAt = -1, next;

        /** The frame of the piece {@code scene} is at by its own time; negative before it. */
        int frame(PonderScene scene) {
            int now = scene.getCurrentTime();
            return start < 0 || now < start ? -1 : (now - start) * FRAMES_PER_TICK;
        }

        void show(MidiKeyboardBlock.Entity keyboard, int frame) {
            if (frame < shownAt) {
                next = 0;
                keys[0] = keys[1] = 0;
            }
            shownAt = frame;
            int[] frames = SCORE.frames(), messages = SCORE.messages();
            for (; next < frames.length && frames[next] <= frame; next++) {
                int message = messages[next], kind = message & 0xF0, note = message >> 8 & 127;
                if (kind == Synth.NOTE_ON || kind == Synth.NOTE_OFF) {
                    boolean down = kind == Synth.NOTE_ON && (message >> 16 & 127) > 0;
                    keys[note >> 6] = down ? keys[note >> 6] | 1L << note : keys[note >> 6] & ~(1L << note);
                }
            }
            keyboard.keysDown.set(0, keys[0]);
            keyboard.keysDown.set(1, keys[1]);
        }
    }

    /** Before {@code keyboard} is drawn: if it plays in a Ponder scene, its keys as the piece is heard now. */
    static void pose(MidiKeyboardBlock.Entity keyboard) {
        if (!(keyboard.getLevel() instanceof PonderLevel level) || !(SHOWS.get(level.scene) instanceof Show show)) {
            return;
        }
        Player playing = player;
        int heard = playing != null && playing.show == show ? playing.heard() : -1;
        show.show(keyboard, heard >= 0 ? heard : show.frame(level.scene));
    }

    /** Each client tick: the sound follows the open Ponder scene's time. */
    static void tick(Minecraft mc) {
        PonderScene scene = mc.screen instanceof PonderUI ui ? ui.getActiveScene() : null;
        Show show = scene == null ? null : SHOWS.get(scene);
        int frame = show == null || scene.isFinished() ? -1 : show.frame(scene);
        if (frame < 0) {
            stop();
            shown = null;
            return;
        }
        int now = scene.getCurrentTime();
        if (show == shown && now == time) {
            if (++still >= 3) {
                stop();
            }
            return;
        }
        boolean follows = show == shown && now == time + 1 && player != null;
        shown = show;
        time = now;
        still = 0;
        if (follows) {
            return;
        }
        stop();
        SoundFont bank = KeyboardAudio.bank(mc);
        if (bank != null && frame < SCORE.length()) {
            player = new Player(show, bank, frame);
            mc.getSoundManager().play(player.sound);
        }
    }

    private static void stop() {
        if (player != null) {
            player.closed = true;
            Minecraft.getInstance().getSoundManager().stop(player.sound);
        }
        player = null;
    }

    /** The piece rendered from a frame on, as a stereo stream heard where the camera is. */
    private static final class Player implements CmsStream {
        private static final AudioFormat FORMAT = new AudioFormat(Synth.RATE, 16, 2, true, false);
        final Show show;
        final Played sound = new Played(this);
        private final Synth synth;
        private final AudibleTimeline audible = new AudibleTimeline(Synth.RATE);
        private final float[] left = new float[FRAMES_PER_TICK], right = new float[FRAMES_PER_TICK];
        private final ByteBuffer out = ByteBuffer.allocateDirect(FRAMES_PER_TICK * 4).order(ByteOrder.LITTLE_ENDIAN);
        private int frame, next;
        volatile boolean closed;

        Player(Show show, SoundFont bank, int from) {
            this.show = show;
            synth = new Synth(bank);
            frame = from;
            // Started midway: the pedal and sounds as set so far, but none of the notes struck before.
            int[] frames = SCORE.frames(), messages = SCORE.messages();
            for (; next < frames.length && frames[next] < from; next++) {
                int kind = messages[next] & 0xF0;
                if (kind != Synth.NOTE_ON && kind != Synth.NOTE_OFF) {
                    synth.send(messages[next]);
                }
            }
        }

        /** The frame of the piece heard now; -1 if none is yet. */
        int heard() {
            double seconds = audible.seconds(System.nanoTime());
            return seconds < 0 ? -1 : (int) (seconds * Synth.RATE);
        }

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public long attach(int source, long deviceNow) {
            long at = deviceNow == 0 ? 0 : deviceNow + 32_000_000L;
            audible.attach(source, at);
            return at;
        }

        @Override
        public int bufferBytes(int vanillaBytes) {
            return FRAMES_PER_TICK * 4;
        }

        @Override
        public boolean needsFastRefill() {
            return true;
        }

        @Override
        public ByteBuffer read(int size) {
            audible.queue(frame / (double) Synth.RATE, FRAMES_PER_TICK);
            int[] frames = SCORE.frames(), messages = SCORE.messages();
            for (int block = 0; block < FRAMES_PER_TICK; block += Synth.BLOCK) {
                for (; next < frames.length && frames[next] < frame + block + Synth.BLOCK; next++) {
                    synth.send(messages[next]);
                }
                synth.render(left, right, block, 1);
            }
            frame += FRAMES_PER_TICK;
            out.clear();
            for (int i = 0; i < FRAMES_PER_TICK; i++) {
                out.putShort((short) Math.round(Math.clamp(left[i], -1, 1) * 32767));
                out.putShort((short) Math.round(Math.clamp(right[i], -1, 1) * 32767));
            }
            return out.flip();
        }

        @Override
        public void close() {
            closed = true;
            audible.close();
        }
    }

    private static final class Played extends AbstractTickableSoundInstance {
        private final Player player;

        Played(Player player) {
            super(KeyboardFeature.PLAYED.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.player = player;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (player.closed) {
                stop();
            }
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(player);
        }
    }
}
