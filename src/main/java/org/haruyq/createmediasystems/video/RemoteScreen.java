package org.haruyq.createmediasystems.video;

import java.util.List;

import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.client.PlaybackSeekBar;
import org.haruyq.createmediasystems.partybox.PartyBoxEntity;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A remote's buttons for its paired device. What it shows of the device is what this client has of it (the
 * server checks the remote's range on every button).
 */
final class RemoteScreen extends Screen {
    private final GlobalPos target;
    private final InteractionHand hand;
    private EditBox number;
    private PlaybackSeekBar seek;
    @Nullable private PartyBoxEntity box;

    static void open(GlobalPos target, InteractionHand hand) {
        Minecraft.getInstance().setScreen(new RemoteScreen(target, hand));
    }

    private RemoteScreen(GlobalPos target, InteractionHand hand) {
        super(VideoFeature.REMOTE.get().getDescription());
        this.target = target;
        this.hand = hand;
    }

    private ItemStack remote() {
        return minecraft == null || minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(hand);
    }

    private void findBox() {
        var id = remote().get(VideoFeature.REMOTE_ENTITY.get());
        if (id == null || minecraft.level == null || minecraft.level.dimension() != target.dimension()) {
            box = null;
            return;
        }
        if (box != null && !box.isRemoved() && box.getUUID().equals(id)) return;
        box = null;
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof PartyBoxEntity found && found.getUUID().equals(id)) {
                box = found;
                break;
            }
        }
    }

    @Nullable
    private Remotable videoPlayer() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        ItemStack remote = remote();
        if (remote.has(VideoFeature.REMOTE_ENTITY.get())) return null;
        GlobalPos current = remote.getOrDefault(VideoFeature.REMOTE_TARGET.get(), target);
        return level != null && level.dimension() == current.dimension() && level.isLoaded(current.pos())
                && level.getBlockEntity(current.pos()) instanceof Remotable found ? found : null;
    }

    @Override
    protected void init() {
        findBox();
        int left = width / 2 - 100;
        int top = height / 2 - 40;
        button(left, top, "⏮", Remotable.Action.PREVIOUS);
        button(left + 52, top, "⏯", Remotable.Action.PLAY_PAUSE);
        button(left + 104, top, "⏹", Remotable.Action.STOP);
        button(left + 156, top, "⏭", Remotable.Action.NEXT);
        number = addRenderableWidget(new EditBox(font, left, top + 28, 96, 20,
                Component.translatable("create_media_systems.screen.remote.number")));
        number.setMaxLength(3);
        number.setFilter(text -> text.chars().allMatch(Character::isDigit));
        number.setHint(Component.translatable("create_media_systems.screen.remote.number"));
        addRenderableWidget(Button.builder(Component.translatable("create_media_systems.screen.remote.play_number"), b -> {
            if (!number.getValue().isEmpty()) {
                send(Remotable.Action.SELECT, Integer.parseInt(number.getValue()));
            }
        }).bounds(left + 104, top + 28, 96, 20).build());
        Remotable videoPlayer = videoPlayer();
        addRenderableWidget(new AbstractSliderButton(left, top + 56, 200, 20, Component.empty(),
                box != null ? box.volume() / 32.0 : videoPlayer == null ? 1 : videoPlayer.volume()) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("create_media_systems.screen.remote.volume", Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
                if (!dragging) send(Remotable.Action.VOLUME, (int) Math.round(value * 100));
            }

            private boolean dragging;
            @Override
            public void onClick(double mouseX, double mouseY) {
                dragging = true;
                super.onClick(mouseX, mouseY);
            }
            @Override
            public void onRelease(double mouseX, double mouseY) {
                super.onRelease(mouseX, mouseY);
                dragging = false;
                send(Remotable.Action.VOLUME, (int) Math.round(value * 100));
            }
        });
        addRenderableWidget(Button.builder(Component.literal("-10s"), b -> send(Remotable.Action.SEEK_BY, -10))
                .bounds(left, top + 84, 30, 20).build());
        seek = addRenderableWidget(new PlaybackSeekBar(left + 34, top + 84, 132,
                seconds -> send(Remotable.Action.SEEK, seconds)));
        addRenderableWidget(Button.builder(Component.literal("+10s"), b -> send(Remotable.Action.SEEK_BY, 10))
                .bounds(left + 170, top + 84, 30, 20).build());
    }

    @Override
    public void tick() {
        if (!remote().is(VideoFeature.REMOTE.get())) onClose();
        else findBox();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) seek.release();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void button(int x, int y, String label, Remotable.Action action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> send(action, 0)).bounds(x, y, 44, 20).build());
    }

    private void send(Remotable.Action action, int value) {
        PacketDistributor.sendToServer(new RemoteItem.RemoteControl(hand, action, value));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int top = height / 2 - 40;
        Remotable videoPlayer = videoPlayer();
        Component status;
        String url = null;
        if (box != null) {
            status = Component.translatable(box.playing() ? "portable.playing" : "portable.paused");
            if (box.trackCount() > 0) {
                status = Component.translatable("portable.track", box.track() + 1, box.trackCount()).append("   ").append(status);
            }
            url = box.currentUrl();
        } else if (videoPlayer == null) {
            status = Component.translatable("create_media_systems.remote.out_of_sight");
        } else {
            List<String> playlist = videoPlayer.playlist();
            if (playlist.isEmpty()) {
                status = videoPlayer.state();
            } else {
                status = videoPlayer.track().append("  ").append(videoPlayer.state());
                url = playlist.get(videoPlayer.shownIndex());
            }
        }
        NativeAudio.Playback playback = url == null || videoPlayer == null ? null : NativeAudio.playback(
                remote().getOrDefault(VideoFeature.REMOTE_TARGET.get(), target).pos(), url);
        var progress = playback == null ? null : playback.progress();
        if (box != null) seek.show(box.seconds(), box.duration());
        else seek.show(progress == null ? 0 : progress.seconds(), progress == null || progress.loading() ? -1 : progress.length());
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, box == null ? title : box.getName(), width / 2, top - 36, 0xFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, top - 22, 0xF0C040);
        if (url != null) {
            Component shown = Streams.title(url);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(shown == null ? url : shown.getString(), 300),
                    width / 2, top - 11, 0x909090);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
