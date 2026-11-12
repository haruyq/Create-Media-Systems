package org.haruyq.createmediasystems.broadcast;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.hifi.HifiStackBlock;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;
import org.jetbrains.annotations.Nullable;

/** Advances the actual deck state once per tick when multiple mounted heads share the same captured source. */
final class CapturedBroadcastSource implements MediaSource.Keyed {
    private static final Map<Contraption, Map<Link, Optional<CapturedBroadcastSource>>> SOURCES = new WeakHashMap<>();
    private final UUID id = UUID.randomUUID();
    private final BlockEntity deck;
    private final CompoundTag data;
    @Nullable private final String jukeboxUrl;
    @Nullable private final PlaybackClock jukeboxClock;
    private long lastTick = Long.MIN_VALUE;

    private CapturedBroadcastSource(BlockEntity deck, CompoundTag data) {
        this.deck = deck;
        this.data = data;
        if (deck instanceof JukeboxBlockEntity jukebox) {
            jukeboxUrl = MediaItem.url(jukebox.getTheItem());
            jukeboxClock = new PlaybackClock();
            jukeboxClock.load(jukebox.getSongPlayer().getTicksSinceSongStarted(),
                    jukeboxUrl != null && jukebox.getSongPlayer().isPlaying());
        } else {
            jukeboxUrl = null;
            jukeboxClock = null;
        }
    }

    @Nullable
    static CapturedBroadcastSource find(MovementContext context, Link link) {
        if (link.pos() == null && link.id() == null) return null;
        var sources = SOURCES.computeIfAbsent(context.contraption, unused -> new HashMap<>());
        var key = link.id() == null ? link : new Link(null, link.id());
        return sources.computeIfAbsent(key, ignored -> Optional.ofNullable(locate(context, link))).orElse(null);
    }

    @Nullable
    private static CapturedBroadcastSource locate(MovementContext context, Link link) {
        var blocks = context.contraption.getBlocks();
        var registries = context.world.registryAccess();
        var direct = link.pos() == null ? null : blocks.get(link.pos().subtract(context.contraption.anchor));
        var source = read(direct, link.id(), registries);
        if (source != null || link.id() == null) return source;
        for (StructureBlockInfo info : blocks.values()) {
            if (info == direct) continue;
            source = read(info, link.id(), registries);
            if (source != null) return source;
        }
        return null;
    }

    @Nullable
    private static CapturedBroadcastSource read(@Nullable StructureBlockInfo info, @Nullable UUID identity,
            HolderLookup.Provider registries) {
        if (info == null || info.nbt() == null) return null;
        Block block = info.state().getBlock();
        if (!(block instanceof DiscReaderBlock || block instanceof VideoPlayerBlock
                || block instanceof HifiStackBlock || block instanceof JukeboxBlock)) return null;
        BlockEntity deck = BlockEntity.loadStatic(info.pos(), info.state(), info.nbt(), registries);
        if (deck == null || identity != null && (!deck.hasData(StreamFeature.SOURCE_ID)
                || !Objects.equals(deck.getData(StreamFeature.SOURCE_ID), identity))) return null;
        return new CapturedBroadcastSource(deck, info.nbt());
    }

    void tick(long now) {
        if (lastTick == now) return;
        lastTick = now;
        if (deck instanceof MediaSource.Captured source) source.tickCaptured(now);
        if (jukeboxClock != null) {
            jukeboxClock.tick(now);
            if (jukeboxUrl != null && jukeboxClock.ended(jukeboxUrl, now)) jukeboxClock.stop();
        }
    }

    void save(HolderLookup.Provider registries) {
        if (jukeboxClock != null) {
            if (jukeboxUrl == null) return;
            if (jukeboxClock.running()) {
                data.putLong(JukeboxBlockEntity.TICKS_SINCE_SONG_STARTED_TAG_ID, jukeboxClock.position());
            } else {
                data.remove(JukeboxBlockEntity.TICKS_SINCE_SONG_STARTED_TAG_ID);
            }
            return;
        }
        // The original tag is shared with Create's block snapshot, including the snapshot it restores on disassembly.
        var saved = deck.saveWithFullMetadata(registries);
        for (String key : data.getAllKeys().toArray(String[]::new)) data.remove(key);
        saved.remove("x");
        saved.remove("y");
        saved.remove("z");
        data.merge(saved);
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    @Nullable
    public Streams.Source nowPlaying() {
        if (deck instanceof MediaSource.Captured source) return source.nowPlaying();
        return jukeboxUrl != null && jukeboxClock != null && jukeboxClock.running()
                ? new Streams.Source(jukeboxUrl, jukeboxClock.position(), null) : null;
    }

    @Override
    public float gain() {
        return deck instanceof MediaSource.Captured source ? source.gain() : 1;
    }
}
