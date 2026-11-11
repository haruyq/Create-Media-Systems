package org.haruyq.createmediasystems.train;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.playback.StreamSpec;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client side of in-train announcements: each is heard from the train speakers of the carriage we are on. */
final class Announcements {
    /** Where each reader's announcements play (see TrainDiscReaderBehaviour.Announce#channel). */
    private static final Map<String, ClientStreams.Slot> SLOTS = new HashMap<>();

    private Announcements() {
    }

    static void play(TrainDiscReaderBehaviour.Announce payload) {
        Map<Integer, List<Vec3>> speakers = new HashMap<>();
        Vec3[] last = {Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()};
        var heard = new StreamSpec.Emitter(() -> {
            Vec3 at = speaker(payload.carriages(), speakers);
            return at == null ? last[0] : (last[0] = at);
        }, null, () -> true, () -> carriage(payload.carriages()) != null,
                () -> speaker(payload.carriages(), speakers) == null ? 0 : payload.volume(), null);
        SLOTS.computeIfAbsent(payload.channel(), channel -> new ClientStreams.Slot("train/" + channel)).play(new StreamSound(new StreamSpec(payload.url(),
                payload.startSeconds(), Streams.Channel.MIX, SoundSource.VOICE, heard,
                seconds -> Minecraft.getInstance().execute(() -> PacketDistributor.sendToServer(new StreamPackets.Started(
                        new StreamPackets.Target.Channel(payload.channel()), (float) seconds))))));
    }

    /** The carriage of {@code carriages} we are on, by the same test the server picks passengers with. */
    @Nullable
    private static CarriageContraptionEntity carriage(List<Integer> carriages) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return null;
        }
        for (int id : carriages) {
            if (mc.level.getEntity(id) instanceof CarriageContraptionEntity carriage
                    && carriage.getContraption() != null && TrainDiscReaderBehaviour.aboard(carriage, mc.player)) {
                return carriage;
            }
        }
        return null;
    }

    /**
     * The train speaker nearest us on the carriage we are on, where an announcement plays from for us; null if that
     * carriage has none (then it is silent: a speaker is heard only in its own carriage). {@code found} keeps each
     * carriage's speakers, in its own coordinates (its blocks don't change while it runs).
     */
    @Nullable
    private static Vec3 speaker(List<Integer> carriages, Map<Integer, List<Vec3>> found) {
        CarriageContraptionEntity carriage = carriage(carriages);
        if (carriage == null) {
            return null;
        }
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Vec3> mouths = found.computeIfAbsent(carriage.getId(), i -> carriage.getContraption().getBlocks().entrySet()
                .stream().filter(e -> e.getValue().state().is(TrainFeature.TRAIN_SPEAKER.get()))
                .map(e -> e.getKey().getCenter().relative(e.getValue().state().getValue(TrainSpeakerBlock.FACING), 0.5))
                .toList());
        return mouths.stream().map(local -> carriage.toGlobalVector(local, 1))
                .min(Comparator.comparingDouble(at -> at.distanceToSqr(ear))).orElse(null);
    }
}
