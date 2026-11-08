package org.haruyq.createmediasystems.video;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A remote's buttons for its Blu-ray player: previous, play/pause, stop, next, play entry N, and volume. What it shows of
 * the Blu-ray player is what this client has of it (the server checks the remote's range on every button).
 */
final class RemoteScreen extends Screen {
    private final GlobalPos target;
    private EditBox number;

    static void open(GlobalPos target) {
        Minecraft.getInstance().setScreen(new RemoteScreen(target));
    }

    private RemoteScreen(GlobalPos target) {
        super(CreateMediaSystems.REMOTE.get().getDescription());
        this.target = target;
    }

    @Nullable
    private VideoPlayerBlock.Entity videoPlayer() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        ItemStack remote = RemoteItem.held(mc.player);
        GlobalPos current = remote.getOrDefault(CreateMediaSystems.REMOTE_TARGET.get(), target);
        return level != null && level.dimension() == current.dimension() && level.isLoaded(current.pos())
                && level.getBlockEntity(current.pos()) instanceof VideoPlayerBlock.Entity found ? found : null;
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        int top = height / 2 - 40;
        button(left, top, "⏮", VideoPlayerBlock.Action.PREVIOUS);
        button(left + 52, top, "⏯", VideoPlayerBlock.Action.PLAY_PAUSE);
        button(left + 104, top, "⏹", VideoPlayerBlock.Action.STOP);
        button(left + 156, top, "⏭", VideoPlayerBlock.Action.NEXT);
        number = addRenderableWidget(new EditBox(font, left, top + 28, 96, 20,
                Component.translatable("create_media_systems.screen.remote.number")));
        number.setMaxLength(3);
        number.setFilter(text -> text.chars().allMatch(Character::isDigit));
        number.setHint(Component.translatable("create_media_systems.screen.remote.number"));
        addRenderableWidget(Button.builder(Component.translatable("create_media_systems.screen.remote.play_number"), b -> {
            if (!number.getValue().isEmpty()) {
                send(VideoPlayerBlock.Action.SELECT, Integer.parseInt(number.getValue()));
            }
        }).bounds(left + 104, top + 28, 96, 20).build());
        VideoPlayerBlock.Entity videoPlayer = videoPlayer();
        addRenderableWidget(new AbstractSliderButton(left, top + 56, 200, 20, Component.empty(),
                videoPlayer == null ? 1 : videoPlayer.volume()) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("create_media_systems.screen.remote.volume", Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
            }

            @Override
            public void onRelease(double mouseX, double mouseY) {
                super.onRelease(mouseX, mouseY);
                send(VideoPlayerBlock.Action.VOLUME, (int) Math.round(value * 100));
            }
        });
    }

    private void button(int x, int y, String label, VideoPlayerBlock.Action action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> send(action, 0)).bounds(x, y, 44, 20).build());
    }

    private static void send(VideoPlayerBlock.Action action, int value) {
        PacketDistributor.sendToServer(new RemoteItem.RemoteControl(action.ordinal(), value));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int top = height / 2 - 40;
        graphics.drawCenteredString(font, title, width / 2, top - 36, 0xFFFFFF);
        VideoPlayerBlock.Entity videoPlayer = videoPlayer();
        Component status;
        String url = null;
        if (videoPlayer == null) {
            status = Component.translatable("create_media_systems.remote.out_of_sight");
        } else {
            List<String> playlist = videoPlayer.playlist();
            if (playlist.isEmpty()) {
                status = videoPlayer.state();
            } else {
                int index = Math.min(videoPlayer.index(), playlist.size() - 1);
                status = Component.translatable("create_media_systems.video_player.track", index + 1, playlist.size())
                        .append("  ").append(videoPlayer.state());
                url = playlist.get(index);
            }
        }
        graphics.drawCenteredString(font, status, width / 2, top - 22, 0xF0C040);
        if (url != null) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(url, 300), width / 2, top - 11, 0x909090);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
