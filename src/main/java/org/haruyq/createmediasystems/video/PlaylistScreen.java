package org.haruyq.createmediasystems.video;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.playback.YtDlp;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A disc's playlist. A YouTube playlist link in a row is read with yt-dlp on saving and replaced by its videos, as
 * many as fit.
 */
final class PlaylistScreen extends Screen {
    private static final int ROWS = 8;
    private static final int ROW_HEIGHT = 24;
    /** Edited in place; always at least one (maybe empty) row. */
    private final List<String> entries;
    private final int capacity;
    private int scroll;
    private boolean reading;
    private Component status = Component.empty();

    static void open(List<String> playlist, int capacity) {
        Minecraft.getInstance().setScreen(new PlaylistScreen(playlist, capacity));
    }

    private PlaylistScreen(List<String> playlist, int capacity) {
        super(Component.translatable("create_media_systems.screen.playlist"));
        this.capacity = capacity;
        entries = new ArrayList<>(playlist.subList(0, Math.min(playlist.size(), capacity)));
        if (entries.isEmpty()) {
            entries.add("");
        }
    }

    @Override
    protected void init() {
        int left = width / 2 - 170;
        int top = height / 2 - (ROWS * ROW_HEIGHT) / 2 - 10;
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - ROWS));
        for (int row = 0; row < ROWS && scroll + row < entries.size(); row++) {
            int entry = scroll + row;
            int y = top + row * ROW_HEIGHT;
            EditBox box = new EditBox(font, left + 24, y, 246, 20, Component.translatable("create_media_systems.screen.playlist.url"));
            box.setMaxLength(UrlPolicy.MAX_LENGTH);
            box.setValue(entries.get(entry));
            box.setResponder(value -> entries.set(entry, value));
            addRenderableWidget(box);
            if (row == 0 && scroll == 0) {
                setInitialFocus(box);
            }
            addRenderableWidget(Button.builder(Component.literal("↑"), b -> move(entry, -1))
                    .bounds(left + 274, y, 20, 20).build()).active = entry > 0;
            addRenderableWidget(Button.builder(Component.literal("↓"), b -> move(entry, 1))
                    .bounds(left + 296, y, 20, 20).build()).active = entry + 1 < entries.size();
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> remove(entry))
                    .bounds(left + 318, y, 20, 20).build());
        }
        int bottom = top + ROWS * ROW_HEIGHT + 6;
        addRenderableWidget(Button.builder(Component.translatable("create_media_systems.screen.playlist.add"), b -> add())
                .bounds(left + 24, bottom, 120, 20).build()).active = entries.size() < capacity;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> done())
                .bounds(left + 218, bottom, 120, 20).build()).active = !reading;
    }

    private void done() {
        List<String> rows = List.copyOf(entries);
        List<String> playlists = rows.stream().map(String::strip).filter(YtDlp::isPlaylist).distinct().toList();
        if (playlists.isEmpty()) {
            save(rows);
            return;
        }
        reading = true;
        status = Component.translatable("create_media_systems.screen.playlist.reading");
        rebuildWidgets();
        int room = capacity - (int) rows.stream().filter(row -> !row.isBlank() && !YtDlp.isPlaylist(row)).count();
        Thread.ofVirtual().name("CMS playlist").start(() -> {
            Map<String, List<String>> videos = new LinkedHashMap<>();
            Component failed = null;
            try {
                for (String playlist : playlists) {
                    videos.put(playlist, YtDlp.playlist(playlist, room));
                }
            } catch (IOException e) {
                failed = Component.translatable("create_media_systems.screen.playlist.failed", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = Component.translatable("create_media_systems.screen.playlist.failed", e.toString());
            }
            Component failure = failed;
            minecraft.execute(() -> {
                if (minecraft.screen != this) {
                    return; // closed while reading
                }
                if (failure == null) {
                    save(YtDlp.fill(rows, videos, capacity));
                    return;
                }
                reading = false;
                status = failure.copy().withStyle(ChatFormatting.RED);
                rebuildWidgets();
            });
        });
    }

    private void save(List<String> rows) {
        PacketDistributor.sendToServer(new VideoDiscItem.SetPlaylist(List.copyOf(rows)));
        onClose();
    }

    private void add() {
        entries.add("");
        scroll = entries.size() - ROWS;
        rebuildWidgets();
    }

    private void move(int entry, int by) {
        Collections.swap(entries, entry, entry + by);
        rebuildWidgets();
    }

    private void remove(int entry) {
        entries.remove(entry);
        if (entries.isEmpty()) {
            entries.add("");
        }
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int before = scroll;
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, entries.size() - ROWS));
        if (scroll != before) {
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 170;
        int top = height / 2 - (ROWS * ROW_HEIGHT) / 2 - 10;
        graphics.drawCenteredString(font, title, width / 2, top - 16, 0xFFFFFF);
        for (int row = 0; row < ROWS && scroll + row < entries.size(); row++) {
            graphics.drawString(font, (scroll + row + 1) + ".", left, top + row * ROW_HEIGHT + 6, 0xA0A0A0);
        }
        graphics.drawString(font, Component.translatable("create_media_systems.screen.playlist.count", entries.size(),
                capacity), left + 150, top + ROWS * ROW_HEIGHT + 12, 0x808080);
        graphics.drawWordWrap(font, status, left, top + ROWS * ROW_HEIGHT + 32, 340, 0xFFFFFF);
    }
}
