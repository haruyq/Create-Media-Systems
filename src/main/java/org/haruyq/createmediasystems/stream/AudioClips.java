package org.haruyq.createmediasystems.stream;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Downloads;
import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Sound files other mods gave the server (see api.media.AudioClips), kept in memory by their SHA-256 until the server
 * stops, and sent in parts to each client that plays one ({@link MediaKind#CLIP}) and asks for it.
 */
public final class AudioClips {
    public static final int MAX_BYTES = 8 << 20;
    /** What all the clips may take together; the least recently played go first past it. */
    static final long MAX_TOTAL_BYTES = 64L << 20;
    public static final int PART_BYTES = 512 << 10;
    public static final int MAX_PARTS = MAX_BYTES / PART_BYTES;
    /** How long a player waits before the same clip is sent to them again. */
    private static final long RESEND_MILLIS = 30_000;
    private static final Map<String, byte[]> CLIPS = new LinkedHashMap<>(16, 0.75f, true);
    // ponytail: one entry per player and clip asked for, dropped only as the server stops; a sweep would bound it.
    private static final Map<String, Long> SENT = new HashMap<>();
    private static long total;

    private AudioClips() {
    }

    /** Keeps {@code bytes} as a clip; its link. Fails if it is empty or over {@link #MAX_BYTES}. */
    public static synchronized String store(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("A clip is 1 to " + MAX_BYTES + " bytes, not " + bytes.length);
        }
        String hash = Downloads.sha256(bytes);
        if (CLIPS.put(hash, bytes.clone()) == null) {
            total += bytes.length;
        }
        var oldest = CLIPS.entrySet().iterator();
        while (total > MAX_TOTAL_BYTES && oldest.hasNext()) {
            var clip = oldest.next();
            if (!clip.getKey().equals(hash)) {
                total -= clip.getValue().length;
                oldest.remove();
            }
        }
        return MediaKind.CLIP_SCHEME + hash;
    }

    /** The clip with {@code hash}; null if the server has none (any more). */
    @Nullable
    static synchronized byte[] get(String hash) {
        return CLIPS.get(hash);
    }

    public static synchronized void serverStopped() {
        CLIPS.clear();
        SENT.clear();
        total = 0;
    }

    /** {@code player} asks for a clip: its parts, or one empty part if the server hasn't it or sent it just now. */
    private static void asked(ServerPlayer player, String hash) {
        if (!hash.matches("[0-9a-f]{64}")) {
            return;
        }
        byte[] clip;
        synchronized (AudioClips.class) {
            long now = System.currentTimeMillis();
            Long last = SENT.get(player.getUUID() + hash);
            clip = last != null && now - last < RESEND_MILLIS ? null : get(hash);
            if (clip != null) {
                SENT.put(player.getUUID() + hash, now);
            }
        }
        if (clip == null) {
            PacketDistributor.sendToPlayer(player, new Part(hash, 0, 0, new byte[0]));
            return;
        }
        int parts = Math.ceilDiv(clip.length, PART_BYTES);
        for (int i = 0; i < parts; i++) {
            int from = i * PART_BYTES;
            PacketDistributor.sendToPlayer(player, new Part(hash, i, parts,
                    Arrays.copyOfRange(clip, from, Math.min(clip.length, from + PART_BYTES))));
        }
    }

    /** A client asks for the clip with {@code hash}. */
    public record Ask(String hash) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(CreateMediaSystems.id("clip_ask"));
        public static final StreamCodec<ByteBuf, Ask> CODEC = ByteBufCodecs.stringUtf8(64).map(Ask::new, Ask::hash);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Part {@code index} of {@code count} of a clip; a count of 0 says the server hasn't it. */
    public record Part(String hash, int index, int count, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Part> TYPE = new Type<>(CreateMediaSystems.id("clip_part"));
        public static final StreamCodec<ByteBuf, Part> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Part::hash,
                ByteBufCodecs.VAR_INT, Part::index,
                ByteBufCodecs.VAR_INT, Part::count,
                ByteBufCodecs.byteArray(PART_BYTES), Part::bytes,
                Part::new);

        @Override
        public Type<Part> type() {
            return TYPE;
        }
    }

    /** How this client takes a part; set as the client starts. */
    private static volatile Consumer<Part> client = part -> {
    };

    public static void client(Consumer<Part> handler) {
        client = handler;
    }

    static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Ask.TYPE, Ask.CODEC, (ask, context) -> asked((ServerPlayer) context.player(), ask.hash()));
        registrar.playToClient(Part.TYPE, Part.CODEC, (part, context) -> client.accept(part));
    }
}
