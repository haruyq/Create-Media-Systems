package org.haruyq.createmediasystems.train;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.playback.StreamSpec;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client side of in-train announcements: each is heard from the train speakers of the carriage we are on. */
final class Announcements {
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
        ClientStreams.play("train/" + payload.channel(), new StreamSound(new StreamSpec(payload.url(),
                payload.startSeconds(), Streams.Channel.MIX, SoundSource.VOICE, heard,
                seconds -> Minecraft.getInstance().execute(() -> PacketDistributor.sendToServer(
                        new TrainDiscReaderBehaviour.StartedAnnouncement(payload.channel()))))));
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
                .map(e -> SpeakerBlock.mouth(e.getKey(), e.getValue().state().getValue(SpeakerBlock.FACING)))
                .toList());
        return mouths.stream().map(local -> carriage.toGlobalVector(local, 1))
                .min(Comparator.comparingDouble(at -> at.distanceToSqr(ear))).orElse(null);
    }
}
