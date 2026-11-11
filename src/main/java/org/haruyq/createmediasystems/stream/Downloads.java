package org.haruyq.createmediasystems.stream;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * How much of the video a VideoSource plays its listeners have downloaded (see ClientStreams#reportDownloads), as its
 * goggles show it: their average, and how many of them have all of it. The source forgets it as each video starts.
 */
public final class Downloads {
    /** What each has told, 0..1: all of it once it plays for them or is kept. Server only. */
    private final Map<ServerPlayer, Float> told = new HashMap<>();
    /** Their average, in percent; -1 while none has told. */
    private int average = -1;
    private int downloaders;
    private int finished;

    public void clear() {
        told.clear();
        average = -1;
        downloaders = 0;
        finished = 0;
    }

    /** {@code player} has {@code fraction} of the video; whether what its goggles show changed. */
    public boolean told(ServerPlayer player, float fraction) {
        told.put(player, Mth.clamp(fraction, 0, 1));
        told.keySet().removeIf(ServerPlayer::isRemoved);
        int mean = (int) (told.values().stream().mapToDouble(Float::doubleValue).average().orElse(0) * 100);
        int done = (int) told.values().stream().filter(each -> each >= 1).count();
        if (mean == average && told.size() == downloaders && done == finished) {
            return false;
        }
        average = mean;
        downloaders = told.size();
        finished = done;
        return true;
    }

    /** For clients' goggles; not saved. */
    public void write(CompoundTag tag) {
        tag.putInt("Downloaded", average);
        tag.putInt("Downloaders", downloaders);
        tag.putInt("Finished", finished);
    }

    public void read(CompoundTag tag) {
        average = tag.contains("Downloaded") ? tag.getInt("Downloaded") : -1;
        downloaders = tag.getInt("Downloaders");
        finished = tag.getInt("Finished");
    }

    public void addTo(List<Component> tooltip, String indent) {
        if (average < 0 || finished >= downloaders) {
            return;
        }
        tooltip.add(Component.literal(indent)
                .append(Component.translatable("create_media_systems.video_player.downloaded",
                        Component.literal(average + "%").withStyle(ChatFormatting.AQUA)))
                .append(downloaders > 1 ? Component.literal("  " + finished + "/" + downloaders).withStyle(ChatFormatting.WHITE)
                        : Component.empty())
                .withStyle(ChatFormatting.GRAY));
    }
}
