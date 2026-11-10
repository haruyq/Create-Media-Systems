package org.haruyq.createmediasystems.launcher;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;

/** The physical CD's identity and paused position. The server alone writes this component. */
public record DiscPlayback(UUID id, String source, long positionTicks) {
    public static final Codec<DiscPlayback> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(DiscPlayback::id),
            Codec.STRING.fieldOf("source").forGetter(DiscPlayback::source),
            Codec.LONG.fieldOf("positionTicks").forGetter(DiscPlayback::positionTicks)
    ).apply(instance, DiscPlayback::new));
    public static final StreamCodec<ByteBuf, DiscPlayback> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DiscPlayback::id,
            ByteBufCodecs.STRING_UTF8, DiscPlayback::source,
            ByteBufCodecs.VAR_LONG, DiscPlayback::positionTicks, DiscPlayback::new);

    public DiscPlayback {
        positionTicks = Math.max(0, positionTicks);
    }

    public DiscPlayback at(long ticks) {
        return new DiscPlayback(id, source, ticks);
    }

    /** Changing the media starts that CD's new track at zero, keeping its physical identity. */
    static DiscPlayback prepare(Level level, ItemStack item) {
        String source = source(level, item);
        DiscPlayback previous = item.get(CreateMediaSystems.DISC_PLAYBACK.get());
        return previous == null ? new DiscPlayback(UUID.randomUUID(), source, 0)
                : previous.source.equals(source) ? previous : new DiscPlayback(previous.id, source, 0);
    }

    private static String source(Level level, ItemStack item) {
        String url = mediaUrl(item);
        if (url != null) return "url:" + url;
        return JukeboxSong.fromStack(level.registryAccess(), item)
                .map(song -> "song:" + song.value().soundEvent().value().getLocation())
                .orElseGet(() -> "item:" + BuiltInRegistries.ITEM.getKey(item.getItem()));
    }

    public static String mediaUrl(ItemStack item) {
        if (item.is(CreateMediaSystems.CUSTOM_DISC.get())) return item.get(CreateMediaSystems.URL.get());
        if (item.is(CreateMediaSystems.DVD.get()) || item.is(CreateMediaSystems.BLU_RAY.get())) {
            List<String> playlist = item.getOrDefault(CreateMediaSystems.PLAYLIST.get(), List.of());
            return playlist.isEmpty() ? null : playlist.getFirst();
        }
        return null;
    }
}
