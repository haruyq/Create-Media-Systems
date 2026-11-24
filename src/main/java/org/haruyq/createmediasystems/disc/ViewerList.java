package org.haruyq.createmediasystems.disc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Disc;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Stability;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Viewer;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The viewers of what a drive emulator or a screen server shares, by name, as its host's client last told. Not saved. */
public final class ViewerList {
    private List<Viewer> viewers = List.of();
    private List<String> names = List.of();

    /** Takes what {@code host}'s client tells of its viewers, keeping those online; false if nothing changed. */
    public boolean tell(ServerPlayer host, List<Viewer> told) {
        if (told.equals(viewers)) {
            return false;
        }
        List<Viewer> known = new ArrayList<>();
        List<String> online = new ArrayList<>();
        for (Viewer viewer : told) {
            ServerPlayer watching = host.server.getPlayerList().getPlayer(viewer.id());
            if (watching != null) {
                known.add(viewer);
                online.add(watching.getGameProfile().getName());
            }
        }
        viewers = known;
        names = online;
        return true;
    }

    public void clear() {
        viewers = List.of();
        names = List.of();
    }

    /** Its goggle tooltip lines, each viewer's connection judged against what {@code disc} needs. */
    public void describe(List<Component> tooltip, Disc disc) {
        tooltip.add(Component.literal("     ").append(Component.translatable(viewers.isEmpty()
                ? "create_media_systems.drive.no_viewers" : "create_media_systems.drive.viewers")).withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < viewers.size(); i++) {
            Viewer viewer = viewers.get(i);
            Stability stability = viewer.stability(disc);
            tooltip.add(Component.literal("     " + names.get(i) + "  ").withStyle(ChatFormatting.WHITE)
                    .append(Component.translatable("create_media_systems.drive.stability."
                            + stability.name().toLowerCase(Locale.ROOT)).withStyle(stability.color()))
                    .append(Component.literal(!viewer.measured() ? "" : String.format(Locale.ROOT, "  %d ms  %.1f Mbps",
                            viewer.rttMillis(), viewer.kbps() / 1000.0)).withStyle(ChatFormatting.GRAY)));
        }
    }

    public void write(CompoundTag tag) {
        ListTag list = new ListTag();
        for (int i = 0; i < viewers.size(); i++) {
            CompoundTag viewer = new CompoundTag();
            viewer.putUUID("Id", viewers.get(i).id());
            viewer.putString("Name", names.get(i));
            viewer.putBoolean("Measured", viewers.get(i).measured());
            viewer.putInt("Rtt", viewers.get(i).rttMillis());
            viewer.putInt("Kbps", viewers.get(i).kbps());
            list.add(viewer);
        }
        tag.put("Viewers", list);
    }

    public void read(CompoundTag tag) {
        List<Viewer> known = new ArrayList<>();
        List<String> online = new ArrayList<>();
        for (Tag each : tag.getList("Viewers", Tag.TAG_COMPOUND)) {
            CompoundTag viewer = (CompoundTag) each;
            known.add(new Viewer(viewer.getUUID("Id"), viewer.getBoolean("Measured"), viewer.getInt("Rtt"), viewer.getInt("Kbps")));
            online.add(viewer.getString("Name"));
        }
        viewers = known;
        names = online;
    }
}
