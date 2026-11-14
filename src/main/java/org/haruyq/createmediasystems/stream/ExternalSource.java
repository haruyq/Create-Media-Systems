package org.haruyq.createmediasystems.stream;

import java.util.Optional;

import org.haruyq.createmediasystems.api.media.MediaPlayer;
import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Another mod's block as a source (see api.media.MediaBlock): what CMS asks of a source, answered by the player CMS
 * made for it, so none of it is the other mod's to keep up with.
 */
public interface ExternalSource extends VideoSource {
    MediaPlayer media();

    @Override
    @Nullable
    default Streams.Source nowPlaying() {
        return ((Media) media()).nowPlaying();
    }

    @Override
    default String image() {
        return ((Media) media()).image;
    }

    @Override
    default void downloaded(ServerPlayer player, String url, float fraction) {
    }

    /** A track on a clock, as a disc reader keeps its disc's, and a still picture. */
    final class Media implements MediaPlayer {
        private static final String KEY = "CmsMedia";
        private final BlockEntity owner;
        private final PlaybackClock clock = new PlaybackClock();
        private String url = "";
        private int videoHeight;
        private String image = "";

        public Media(BlockEntity owner) {
            this.owner = owner;
        }

        private static boolean allowed(String url) {
            return url.length() <= UrlPolicy.MAX_LENGTH && UrlPolicy.isAllowed(url);
        }

        @Override
        public boolean play(String url, int videoHeight, @Nullable Player by) {
            Level level = owner.getLevel();
            if (level == null || level.isClientSide || !allowed(url)) {
                return false;
            }
            this.url = url;
            this.videoHeight = Math.max(0, videoHeight);
            clock.start(0);
            Streams.loading(owner, by, clock, level.getGameTime());
            return true;
        }

        @Override
        public boolean show(String url) {
            if (!url.isEmpty() && !allowed(url)) {
                return false;
            }
            image = url;
            owner.setChanged();
            return true;
        }

        @Override
        public void stop() {
            clock.stop();
            owner.setChanged();
        }

        @Override
        public boolean playing() {
            return clock.running();
        }

        @Override
        public void tick() {
            Level level = owner.getLevel();
            if (level == null || level.isClientSide || !clock.running()) {
                return;
            }
            long now = level.getGameTime();
            Streams.tick(owner, clock, now);
            if (clock.ended(url, now)) {
                clock.stop();
            }
            owner.setChanged();
        }

        @Nullable
        Streams.Source nowPlaying() {
            return !clock.running() ? null : new Streams.Source(url, clock.position(), videoHeight <= 0 ? null
                    : new Streams.Video(owner.getBlockPos(), videoHeight, Optional.empty()));
        }

        @Override
        public void save(CompoundTag tag) {
            CompoundTag own = new CompoundTag();
            own.putString("Url", url);
            own.putInt("VideoHeight", videoHeight);
            own.putLong("Ticks", clock.position());
            own.putBoolean("Playing", clock.running());
            own.putString("Image", image);
            tag.put(KEY, own);
        }

        @Override
        public void load(CompoundTag tag) {
            CompoundTag own = tag.getCompound(KEY);
            url = own.getString("Url");
            videoHeight = own.getInt("VideoHeight");
            clock.load(own.getLong("Ticks"), own.getBoolean("Playing") && !url.isEmpty());
            image = own.getString("Image");
        }
    }
}
