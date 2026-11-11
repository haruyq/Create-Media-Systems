package org.haruyq.createmediasystems.video;

import java.util.List;

import org.haruyq.createmediasystems.playback.NativeAudio;

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
 * A remote's buttons for its Blu-ray player: previous, play/pause, stop, next, play entry N, volume, and seeking (a bar,
 * and ten seconds back or on). What it shows of the Blu-ray player is what this client has of it (the server checks the
 * remote's range on every button).
 */
final class RemoteScreen extends Screen {
    private final GlobalPos target;
    private EditBox number;
    private SeekBar seek;

    static void open(GlobalPos target) {
        Minecraft.getInstance().setScreen(new RemoteScreen(target));
    }

    private RemoteScreen(GlobalPos target) {
        super(VideoFeature.REMOTE.get().getDescription());
        this.target = target;
    }

    @Nullable
    private Remotable videoPlayer() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        ItemStack remote = RemoteItem.held(mc.player);
        GlobalPos current = remote.getOrDefault(VideoFeature.REMOTE_TARGET.get(), target);
        return level != null && level.dimension() == current.dimension() && level.isLoaded(current.pos())
                && level.getBlockEntity(current.pos()) instanceof Remotable found ? found : null;
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
        Remotable videoPlayer = videoPlayer();
        addRenderableWidget(new AbstractSliderButton(left, top + 56, 200, 20, Component.empty(),
                videoPlayer == null ? 1 : videoPlayer.remoteVolume()) {
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
        addRenderableWidget(Button.builder(Component.literal("-10s"), b -> send(VideoPlayerBlock.Action.SEEK_BY, -10))
                .bounds(left, top + 84, 30, 20).build());
        seek = addRenderableWidget(new SeekBar(left + 34, top + 84, 132));
        addRenderableWidget(Button.builder(Component.literal("+10s"), b -> send(VideoPlayerBlock.Action.SEEK_BY, 10))
                .bounds(left + 170, top + 84, 30, 20).build());
    }

    /**
     * How far into its track what it plays is, as it plays here: dragged, it seeks there for everyone. Only while it
     * plays here with a known length; the ten-second buttons work anyway, by the server's position.
     */
    private static final class SeekBar extends AbstractSliderButton {
        private double length;
        private boolean dragging;

        SeekBar(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), 0);
        }

        void show(@Nullable NativeAudio.Progress progress) {
            active = progress != null && !progress.loading() && progress.length() > 0;
            if (!active) {
                dragging = false;
                value = 0;
                setMessage(Component.literal("--:--"));
                return;
            }
            length = progress.length();
            if (!dragging) {
                value = Math.min(1, progress.seconds() / length);
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(VideoPlayerBlock.Entity.clock(value * length) + " / "
                    + VideoPlayerBlock.Entity.clock(length)));
        }

        @Override
        protected void applyValue() {
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            dragging = true;
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            release();
        }

        /** Let go (on it or not, see mouseReleased): seeks to where it was let go. */
        void release() {
            if (dragging) {
                dragging = false;
                send(VideoPlayerBlock.Action.SEEK, (int) (value * length));
            }
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        seek.release();
        return super.mouseReleased(mouseX, mouseY, button);
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
        Remotable videoPlayer = videoPlayer();
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
        seek.show(url == null || videoPlayer == null ? null
                : NativeAudio.progress(RemoteItem.held(minecraft.player).getOrDefault(VideoFeature.REMOTE_TARGET.get(), target).pos(), url));
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
