package org.haruyq.createmediasystems.blocks.radio.client;

import java.util.HashMap;
import java.util.Map;

import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.blocks.radio.RadioPackets;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.playback.StreamSpec;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.StreamPackets.Target;
import org.haruyq.createmediasystems.stream.Streams;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What this player's receiver plays: each track of the station the server says it gets, in their ears alone (at the
 * listener, not from a place in the world), at the station's volume times the receiver's own. A station is dropped
 * once the server has not said it is still received for a few relays. A receiver that is on hisses as much as its
 * signal is short of full: over the music at the edge of a station's reach, alone where none is received.
 */
public final class RadioAudio implements RadioPackets.Client {
    private static final int EXPIRE_TICKS = 4 * Streams.RELAY_TICKS;
    private static final RadioAudio INSTANCE = new RadioAudio();
    /** How loud the hiss is with no signal, against a station at full volume. Tuning knob. */
    private static final float HISS = .5f;
    private final Map<Target.Radio, Playing> stations = new HashMap<>();
    /** How strong (0..1) the tuned station is received, as the server last said, and how loud the hiss is by it. */
    private float signal, hiss;
    private Noise noise;

    static void register() {
        RadioPackets.client(INSTANCE);
        NeoForge.EVENT_BUS.addListener(INSTANCE::tick);
    }

    /** Whether a station is received here now, also one that plays nothing. */
    public static boolean receiving() {
        return INSTANCE.signal > 0;
    }

    @Override
    public void play(RadioPackets.Play packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        stop(packet.station());
        Playing playing = new Playing(mc.level, packet);
        stations.put(packet.station(), playing);
        playing.start();
    }

    @Override
    public void tune(RadioPackets.Tune packet) {
        Playing playing = stations.get(packet.station());
        if (playing != null) {
            playing.volume = packet.volume();
            playing.seconds = packet.seconds();
            playing.receivedAt = playing.level.getGameTime();
        }
    }

    @Override
    public void signal(RadioPackets.Signal packet) {
        signal = packet.strength();
    }

    @Override
    public void sync(RadioPackets.Sync packet) {
        Playing playing = stations.get(packet.station());
        if (playing != null) {
            playing.slot.sync(packet.seconds());
        }
    }

    @Override
    public void stop(RadioPackets.Stop packet) {
        stop(packet.station());
    }

    private void stop(Target.Radio station) {
        Playing playing = stations.remove(station);
        if (playing != null) {
            playing.slot.stop();
        }
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        float volume = mc.player == null || LoudmanItem.tuned(mc.player) == 0 ? 0 : LoudmanItem.volume(mc.player);
        hiss = HISS * volume * (1 - signal);
        if (hiss > 0 && (noise == null || !mc.getSoundManager().isActive(noise))) {
            mc.getSoundManager().play(noise = new Noise());
        }
        stations.values().removeIf(playing -> {
            if (mc.level != playing.level || mc.player == null || !playing.present()) {
                playing.slot.stop();
                return true;
            }
            playing.gain = playing.volume * Math.sqrt(signal) * volume;
            if (!playing.slot.playing() && playing.level.getGameTime() - playing.startedAt >= EXPIRE_TICKS) {
                playing.start();
            }
            return false;
        });
    }

    /** The hiss, in the listener's ears: one loop, as loud as {@link #hiss} says, which ends once that is nothing. */
    private static final class Noise extends AbstractTickableSoundInstance {
        Noise() {
            super(RadioFeature.STATIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            relative = true;
            attenuation = Attenuation.NONE;
            volume = 0;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            // The signal comes every relay; eased, so the hiss does not step with it.
            volume = Mth.lerp(.2f, volume, INSTANCE.hiss);
            if (INSTANCE.hiss <= 0 && volume < .005f) {
                stop();
            }
        }
    }

    private static final class Playing {
        final ClientLevel level;
        final RadioPackets.Play play;
        final ClientStreams.Slot slot;
        long startedAt, receivedAt;
        volatile float seconds;
        volatile float volume;
        volatile double gain;

        Playing(ClientLevel level, RadioPackets.Play play) {
            this.level = level;
            this.play = play;
            seconds = play.seconds();
            receivedAt = level.getGameTime();
            slot = new ClientStreams.Slot("radio " + play.station().pos().toShortString());
        }

        boolean present() {
            return level.getGameTime() - receivedAt < EXPIRE_TICKS;
        }

        private double positionSeconds() {
            return seconds + Math.max(0, level.getGameTime() - receivedAt) / (double) PlaybackClock.TICKS_PER_SECOND;
        }

        void start() {
            startedAt = level.getGameTime();
            // No position: it plays at the listener, as earphones do.
            // ponytail: mono (the MIX channel); stereo needs a LEFT and a RIGHT stream per track, as a speaker pair has.
            var ears = new StreamSpec.Emitter(null, null, this::present, this::present, () -> gain, null, false);
            slot.play(new StreamSound(new StreamSpec(play.url(), positionSeconds(), Streams.Channel.MIX, SoundSource.RECORDS,
                    ears, length -> Minecraft.getInstance().execute(() -> {
                        if (present() && Minecraft.getInstance().level == level) {
                            PacketDistributor.sendToServer(new StreamPackets.Started(play.station(), (float) length));
                        }
                    })).from(play.station().source(), play.load(), null)));
        }
    }
}
