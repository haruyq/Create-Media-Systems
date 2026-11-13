package org.haruyq.createmediasystems.blocks.broadcast.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntToDoubleFunction;
import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.broadcast.BroadcastProfile;
import org.junit.jupiter.api.Test;

class BroadcastAudioStreamTest {
    private static final int RATE = 48_000;

    @Test
    void eachTowerIsClearlyAudibleBelowItsMast() {
        for (var profile : BroadcastProfile.values()) {
            if (!profile.outdoor()) continue;
            double below = profile.gain(new Vec3(0, -12, 0));
            double alongside = profile.gain(new Vec3(12, 0, 0));
            assertTrue(below > .68 && below < alongside, profile.name());
            assertTrue(profile.gain(new Vec3(.5, -12, .5)) > below * .98, profile.name());
            assertTrue(profile.gain(new Vec3(0, 12, 0)) < below, profile.name());
        }
    }

    @Test
    void speechRetainsGainOverTheAdvertisedRangeAndFadesAtTheEdge() {
        for (var profile : BroadcastProfile.values()) {
            if (!profile.outdoor()) continue;
            double far = profile.gain(new Vec3(profile.reach * .9, 0, 0));
            double nearEdge = profile.gain(new Vec3(profile.reach * .95, 0, 0));
            double atEdge = profile.gain(new Vec3(profile.reach * .99, 0, 0));
            assertTrue(far > .25 && nearEdge > .15 && nearEdge < far, profile.name());
            assertTrue(atEdge > 0 && atEdge < .04, profile.name());
            assertEquals(0, profile.gain(new Vec3(profile.reach, 0, 0)));
        }
        assertEquals(0, BroadcastProfile.HORN.gain(new Vec3(650, 0, 0)));
        assertTrue(BroadcastProfile.ARRAY.gain(new Vec3(650, 0, 0)) > .31);
    }

    @Test
    void a343BlockPathArrivesAfterOneSecondAndItsEchoesComeLater() throws Exception {
        var pcm = BroadcastAudioStream.wrap(wave(frame -> frame == 0 ? .8 : 0), BroadcastProfile.HORN,
                () -> new BroadcastAudioStream.Path(343, false));
        int first = -1;
        int echo = 0;
        for (int i = 0; i < 111; i++) {
            ByteBuffer buffer = pcm.read(960);
            for (int j = 0; buffer.remaining() >= 2; j++) {
                int frame = i * 480 + j;
                int sample = Math.abs(buffer.getShort());
                if (first < 0 && sample > 20) first = frame;
                if (frame >= RATE + .064 * RATE && frame < RATE + .068 * RATE) echo = Math.max(echo, sample);
            }
        }
        assertEquals(RATE, first);
        assertTrue(echo > 100);
        pcm.close();
    }

    @Test
    void digitalArrayPassesMoreSpeechDetailThanTheAnalogHorn() throws Exception {
        double analog = toneRms(BroadcastProfile.HORN, 7800, false);
        double digital = toneRms(BroadcastProfile.ARRAY, 7800, false);
        double obstructed = toneRms(BroadcastProfile.ARRAY, 7800, true);
        assertTrue(digital > analog * 2 && obstructed < digital / 2);
    }

    private static double toneRms(BroadcastProfile profile, int hz, boolean obstructed) throws Exception {
        try (var pcm = BroadcastAudioStream.wrap(wave(frame -> .24 * Math.sin(2 * Math.PI * hz * frame / RATE)), profile,
                () -> new BroadcastAudioStream.Path(0, obstructed))) {
            pcm.read(RATE / 2);
            ByteBuffer measured = pcm.read(RATE / 2);
            double energy = 0;
            while (measured.remaining() >= 2) energy += Math.pow(measured.getShort() / 32768.0, 2);
            return Math.sqrt(energy / (RATE / 4));
        }
    }

    private static AudioStream wave(IntToDoubleFunction sample) {
        return new AudioStream() {
            private int frame;
            @Override public AudioFormat getFormat() { return new AudioFormat(RATE, 16, 1, true, false); }
            @Override public ByteBuffer read(int bytes) {
                var pcm = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
                while (pcm.remaining() >= 2) pcm.putShort((short) (32767 * sample.applyAsDouble(frame++)));
                return pcm.flip();
            }
            @Override public void close() {}
        };
    }
}
