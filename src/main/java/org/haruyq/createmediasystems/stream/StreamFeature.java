package org.haruyq.createmediasystems.stream;

import static org.haruyq.createmediasystems.CreateMediaSystems.ATTACHMENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;

import java.util.UUID;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.core.UUIDUtil;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Streams to every device: the sound they play through, a source's id, and the stream payloads. */
public final class StreamFeature implements Feature {
    /** Placeholder event: every stream plays through it, the audio itself comes from {@code getStream}. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STREAM =
            SOUNDS.register("stream", SoundEvent::createVariableRangeEvent);
    public static final Supplier<AttachmentType<UUID>> SOURCE_ID = ATTACHMENTS.register(
            "source_id", () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        StreamPackets.registerPayloads(registrar);
    }
}
