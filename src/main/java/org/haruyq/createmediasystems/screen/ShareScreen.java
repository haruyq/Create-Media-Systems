package org.haruyq.createmediasystems.screen;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.playback.VideoFrame;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeWin32;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sun.jna.Platform;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The screen share server's screen: a picker of what to share (an app's window or a whole monitor, each shown as it
 * looks now) with the share's resolution, frame rate, bitrate, sound and its volume, and, while it is shared, a preview of it
 * with how it goes out.
 */
public final class ShareScreen extends Screen {
    private static final int CARD_WIDTH = 144, THUMB_HEIGHT = 81, CARD_HEIGHT = THUMB_HEIGHT + 14, GAP = 8;
    private static final int[] HEIGHTS = {480, 720, 1080, 1440};
    /** The bitrate slider's range and step, kbps. */
    private static final int MIN_KBPS = 500, MAX_KBPS = 20_000, KBPS_STEP = 100;
    /** A row of the microphone list. */
    private static final int ROW = 14;
    /** What the host chose last, offered again next time. */
    private static ScreenShare.Settings remembered = new ScreenShare.Settings(
            new ScreenShare.Source(false, 0, "Screen 1"), 30, 1080, 6000, ScreenShare.Sound.PC, null);

    private record Card(ScreenShare.Source source, @Nullable ResourceLocation thumbnail, int width, int height) {
    }

    private final BlockPos pos;
    private boolean picking;
    private boolean windowsTab = true;
    private final List<Card> windows = new ArrayList<>(), screens = new ArrayList<>();
    private boolean loaded;
    @Nullable
    private ScreenShare.Source selected;
    /** The picture height shared, not this screen's (Screen.height). */
    private int resolution = remembered.height(), kbps = remembered.kbps();
    /** The share's volume, percent, as set here; it goes out on Go Live or Apply (see ScreenShare#volume). */
    private int volume = ScreenShare.volume;
    private ScreenShare.Sound sound = remembered.sound();
    @Nullable
    private String mic = remembered.mic();
    private List<ScreenSound.Device> devices = List.of();
    /** The microphone list, open above its button, and how far it is scrolled. */
    private boolean micsOpen;
    private int micScroll;
    private Button micButton;
    private EditBox fps;
    private String fpsText = Integer.toString(remembered.fps());
    private int scroll;
    private Component message = Component.empty();
    @Nullable
    private VideoFrame preview;
    /** The share whose pictures the preview shows; it starts after Go Live, off the game thread. */
    @Nullable
    private ScreenShare previewing;
    private final VideoTrackSink previewSink = picture -> {
        VideoFrame shown = preview;
        if (shown != null) {
            shown.put(picture);
        }
    };

    private ShareScreen(BlockPos pos) {
        super(Component.translatable("create_media_systems.screen_share.title"));
        this.pos = pos.immutable();
        picking = ScreenShare.current() == null || !pos.equals(ScreenClient.server());
    }

    static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ShareScreen(pos));
    }

    @Nullable
    private ScreenServerBlock.Entity entity() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof ScreenServerBlock.Entity server ? server : null;
    }

    private boolean takenByAnother() {
        ScreenServerBlock.Entity server = entity();
        var player = Minecraft.getInstance().player;
        return server != null && server.host() != null && player != null && !server.host().equals(player.getUUID());
    }

    @Override
    protected void init() {
        if (!Platform.isWindows() || takenByAnother()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(width / 2 - 50, height - 30, 100, 20).build());
            return;
        }
        if (picking) {
            initPicker();
            if (!loaded) {
                loaded = true;
                load();
            }
        } else {
            initPreview();
        }
    }

    private void initPicker() {
        addRenderableWidget(Button.builder(tab(true), b -> tab(true, b)).bounds(width / 2 - 152, 26, 150, 20).build());
        addRenderableWidget(Button.builder(tab(false), b -> tab(false, b)).bounds(width / 2 + 2, 26, 150, 20).build());
        int y = height - 54;
        int x = width / 2 - 206;
        addRenderableWidget(CycleButton.<Integer>builder(h -> Component.literal(h + "p"))
                .withValues(java.util.Arrays.stream(HEIGHTS).boxed().toList()).withInitialValue(resolution)
                .create(x, y, 100, 20, Component.translatable("create_media_systems.screen_share.resolution"),
                        (b, value) -> resolution = value));
        fps = addRenderableWidget(new EditBox(font, x + 104 + 30, y, 36, 20, Component.translatable("create_media_systems.screen_share.fps")));
        fps.setFilter(text -> text.matches("\\d{0,3}"));
        fps.setValue(fpsText);
        fps.setResponder(text -> fpsText = text);
        addRenderableWidget(new AbstractSliderButton(x + 174, y, 130, 20, Component.empty(),
                Math.clamp((kbps - MIN_KBPS) / (double) (MAX_KBPS - MIN_KBPS), 0, 1)) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("create_media_systems.screen_share.bitrate_value", kbps));
            }

            @Override
            protected void applyValue() {
                kbps = MIN_KBPS + (int) Math.round(value * (MAX_KBPS - MIN_KBPS) / KBPS_STEP) * KBPS_STEP;
            }
        });
        addRenderableWidget(CycleButton.<ScreenShare.Sound>builder(s -> Component.translatable(
                        "create_media_systems.screen_share.sound." + s.name().toLowerCase(Locale.ROOT)))
                .withValues(ScreenShare.Sound.values()).withInitialValue(sound)
                .create(x + 308, y, 104, 20, Component.translatable("create_media_systems.screen_share.sound"),
                        (b, value) -> sound = value));
        micButton = addRenderableWidget(Button.builder(micLabel(), b -> {
            micsOpen = !micsOpen;
            micScroll = 0;
        }).bounds(x, y + 24, 146, 20).build());
        addRenderableWidget(new AbstractSliderButton(x + 150, y + 24, 100, 20, Component.empty(), volume / 200.0) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("create_media_systems.screen_share.volume", volume));
            }

            @Override
            protected void applyValue() {
                // 0 to 200%, in steps of 5.
                volume = (int) Math.round(value * 40) * 5;
            }
        });
        boolean live = ScreenShare.current() != null && pos.equals(ScreenClient.server());
        addRenderableWidget(Button.builder(Component.translatable(live ? "create_media_systems.screen_share.apply"
                : "create_media_systems.screen_share.go_live"), b -> goLive()).bounds(x + 254, y + 24, 77, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(live ? "create_media_systems.screen_share.back" : "gui.cancel"), b -> {
            if (live) {
                picking = false;
                rebuildWidgets();
            } else {
                onClose();
            }
        }).bounds(x + 335, y + 24, 77, 20).build());
    }

    private Component tab(boolean apps) {
        Component name = Component.translatable(apps ? "create_media_systems.screen_share.apps" : "create_media_systems.screen_share.screens");
        return apps == windowsTab ? name.copy().withStyle(ChatFormatting.UNDERLINE) : name;
    }

    private void tab(boolean apps, Button button) {
        windowsTab = apps;
        scroll = 0;
        rebuildWidgets();
    }

    private Component micLabel() {
        Component name = mic == null ? Component.translatable("create_media_systems.screen_share.mic.none")
                : devices.stream().filter(d -> d.id().equals(mic)).findFirst()
                        .<Component>map(d -> Component.literal(d.name()))
                        .orElse(Component.translatable("create_media_systems.screen_share.loading"));
        return Component.translatable("create_media_systems.screen_share.mic").append(": ").append(name);
    }

    /** How many of the list's rows (None, then each microphone) show above its button. */
    private int micRows() {
        return Math.min(devices.size() + 1, Math.max(1, (micButton.getY() - 30) / ROW));
    }

    private int micWidth() {
        return Math.max(micButton.getWidth(), Math.min(320, width - micButton.getX() - 4));
    }

    /** The option (0: none, else a microphone's index + 1) the list shows at the mouse; -1 off the list. */
    private int micAt(double mouseX, double mouseY) {
        int rows = micRows(), x = micButton.getX(), top = micButton.getY() - 2 - rows * ROW;
        if (mouseX < x || mouseX >= x + micWidth() || mouseY < top || mouseY >= top + rows * ROW) {
            return -1;
        }
        return micScroll + (int) ((mouseY - top) / ROW);
    }

    private void renderMics(GuiGraphics g, int mouseX, int mouseY) {
        int rows = micRows(), x = micButton.getX(), w = micWidth(), top = micButton.getY() - 2 - rows * ROW;
        int hovered = micAt(mouseX, mouseY);
        g.pose().pushPose();
        // Over the cards and the buttons around it.
        g.pose().translate(0, 0, 300);
        g.fill(x - 1, top - 1, x + w + 1, top + rows * ROW + 1, 0xFF5865F2);
        for (int i = 0; i < rows; i++) {
            int option = micScroll + i, y = top + i * ROW;
            String id = option == 0 ? null : devices.get(option - 1).id();
            g.fill(x, y, x + w, y + ROW, option == hovered ? 0xFF4E5058 : java.util.Objects.equals(id, mic) ? 0xFF3A3C42 : 0xFF2B2D31);
            String name = option == 0 ? I18n.get("create_media_systems.screen_share.mic.none") : devices.get(option - 1).name();
            g.drawString(font, font.plainSubstrByWidth(name, w - 8), x + 4, y + 3, 0xFFFFFF);
        }
        g.pose().popPose();
    }

    private static int fpsValue(int value) {
        return Math.clamp(value, 1, 240);
    }

    private void goLive() {
        if (selected == null) {
            message = Component.translatable("create_media_systems.screen_share.pick").withStyle(ChatFormatting.RED);
            return;
        }
        int rate = fpsValue(fps.getValue().isEmpty() ? 30 : Integer.parseInt(fps.getValue()));
        ScreenShare.Sound heard = sound == ScreenShare.Sound.APP && !selected.window() ? ScreenShare.Sound.PC : sound;
        ScreenShare.Settings settings = new ScreenShare.Settings(selected, rate, resolution, kbps, heard, mic);
        remembered = settings;
        // Live: the mix reads it every 10 ms (see ScreenSound), without restarting the sound.
        ScreenShare.volume = volume;
        if (ScreenShare.current() != null && pos.equals(ScreenClient.server())) {
            ScreenClient.change(settings);
        } else {
            ScreenClient.start(pos, settings, why -> message = Component.literal(why).withStyle(ChatFormatting.RED));
        }
        picking = false;
        rebuildWidgets();
    }

    private void initPreview() {
        if (preview == null) {
            preview = VideoFrame.standalone();
        }
        addRenderableWidget(Button.builder(Component.translatable("create_media_systems.screen_share.change"), b -> {
            picking = true;
            ScreenShare share = ScreenShare.current();
            selected = share == null ? null : share.settings().source();
            if (!loaded) {
                loaded = true;
                load();
            }
            rebuildWidgets();
        }).bounds(width / 2 - 154, height - 30, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("create_media_systems.screen_share.stop").withStyle(ChatFormatting.RED), b -> {
            ScreenClient.stop();
            onClose();
        }).bounds(width / 2 + 4, height - 30, 150, 20).build());
    }

    /** Lists the windows and monitors with a picture of each, and the microphones, off the game thread. */
    private void load() {
        var mc = Minecraft.getInstance();
        long own = GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
        Thread.ofPlatform().daemon().name("CMS screen share sources").start(() -> {
            try {
                Transport.loadNatives(ClientFiles.DIR.resolve("webrtc"));
                List<ScreenShare.Source> found = new ArrayList<>();
                List<NativeImage> pictures = new ArrayList<>();
                WindowCapturer lister = new WindowCapturer();
                List<DesktopSource> listed = lister.getDesktopSources();
                lister.dispose();
                for (DesktopSource window : listed) {
                    if (window.id != own && !window.title.isBlank()) {
                        found.add(new ScreenShare.Source(true, window.id, window.title));
                        pictures.add(thumbnail(new WindowCapturer(), window));
                    }
                }
                ScreenCapturer monitors = new ScreenCapturer();
                List<DesktopSource> displays = monitors.getDesktopSources();
                monitors.dispose();
                for (int i = 0; i < displays.size(); i++) {
                    // ponytail: FFmpeg's monitor_idx is taken to count monitors in WebRTC's order; on one monitor they
                    // agree, on several compare a capture of each against the picture if the wrong one is shared.
                    found.add(new ScreenShare.Source(false, i, "Screen " + (i + 1)));
                    pictures.add(thumbnail(new ScreenCapturer(), displays.get(i)));
                }
                mc.execute(() -> loaded(found, pictures));
            } catch (Exception | LinkageError failed) {
                LogUtils.getLogger().warn("Could not list what to share", failed);
                mc.execute(() -> message = Component.literal(failed.getMessage() == null ? failed.toString() : failed.getMessage())
                        .withStyle(ChatFormatting.RED));
            }
        });
        Thread.ofPlatform().daemon().name("CMS screen share sound devices").start(() -> {
            try {
                List<ScreenSound.Device> listed = ScreenSound.devices(ScreenSound.exe());
                mc.execute(() -> {
                    devices = listed;
                    if (picking) {
                        rebuildWidgets();
                    }
                });
            } catch (java.io.IOException failed) {
                mc.execute(() -> message = Component.translatable("create_media_systems.screen_share.sound_failed",
                        failed.getMessage()).withStyle(ChatFormatting.GOLD));
            }
        });
    }

    /** One picture of {@code source}, small; null if it can't be taken (a window that went). */
    @Nullable
    private static NativeImage thumbnail(DesktopCapturer capturer, DesktopSource source) {
        CompletableFuture<NativeImage> done = new CompletableFuture<>();
        try {
            capturer.selectSource(source);
            capturer.start((result, frame) -> {
                if (result != DesktopCapturer.Result.SUCCESS || frame == null) {
                    done.complete(null);
                    return;
                }
                try {
                    int w = frame.buffer.getWidth(), h = frame.buffer.getHeight();
                    int tw = 192, th = Math.max(2, Math.min(108, h * tw / Math.max(1, w))) & ~1;
                    VideoFrameBuffer small = frame.buffer.cropAndScale(0, 0, w, h, tw, th);
                    ByteBuffer rgba = ByteBuffer.allocateDirect(tw * th * 4);
                    VideoBufferConverter.convertFromI420(small, rgba, FourCC.ABGR);
                    small.release();
                    NativeImage image = new NativeImage(tw, th, false);
                    for (int y = 0; y < th; y++) {
                        for (int x = 0; x < tw; x++) {
                            int at = (y * tw + x) * 4;
                            image.setPixelRGBA(x, y, 0xFF000000 | (rgba.get(at + 2) & 255) << 16
                                    | (rgba.get(at + 1) & 255) << 8 | rgba.get(at) & 255);
                        }
                    }
                    done.complete(image);
                } catch (Exception failed) {
                    done.complete(null);
                }
            });
            capturer.captureFrame();
            return done.get(3, TimeUnit.SECONDS);
        } catch (Exception failed) {
            return null;
        } finally {
            capturer.dispose();
        }
    }

    private void loaded(List<ScreenShare.Source> found, List<NativeImage> pictures) {
        var textures = Minecraft.getInstance().getTextureManager();
        for (int i = 0; i < found.size(); i++) {
            NativeImage picture = pictures.get(i);
            ResourceLocation id = null;
            if (picture != null) {
                id = CreateMediaSystems.id("screen_share/thumbnail_" + i);
                textures.register(id, new DynamicTexture(picture));
            }
            Card card = new Card(found.get(i), id, picture == null ? 16 : picture.getWidth(), picture == null ? 9 : picture.getHeight());
            (found.get(i).window() ? windows : screens).add(card);
        }
    }

    private List<Card> cards() {
        return windowsTab ? windows : screens;
    }

    private int columns() {
        return Math.max(1, (width - 40 + GAP) / (CARD_WIDTH + GAP));
    }

    private int gridLeft() {
        return (width - (columns() * (CARD_WIDTH + GAP) - GAP)) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        if (!Platform.isWindows()) {
            g.drawCenteredString(font, Component.translatable("create_media_systems.screen_share.windows_only"), width / 2, height / 2, 0xFFAAAA);
            return;
        }
        if (takenByAnother()) {
            g.drawCenteredString(font, Component.translatable("create_media_systems.drive.host", entity().hostName()), width / 2, height / 2, 0xFFAAAA);
            return;
        }
        if (picking) {
            renderPicker(g, mouseX, mouseY);
        } else {
            renderPreview(g);
        }
        g.drawCenteredString(font, message, width / 2, height - 66, 0xFFFFFF);
        if (picking && micsOpen) {
            renderMics(g, mouseX, mouseY);
        }
    }

    private void renderPicker(GuiGraphics g, int mouseX, int mouseY) {
        int top = 52, bottom = height - 70;
        List<Card> cards = cards();
        if (cards.isEmpty()) {
            g.drawCenteredString(font, Component.translatable(loaded && (windows.size() + screens.size()) > 0
                    ? "create_media_systems.screen_share.none" : "create_media_systems.screen_share.loading"), width / 2, top + 30, 0xAAAAAA);
        }
        g.enableScissor(0, top, width, bottom);
        int columns = columns(), left = gridLeft();
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            int x = left + i % columns * (CARD_WIDTH + GAP), y = top + i / columns * (CARD_HEIGHT + GAP) - scroll;
            if (y + CARD_HEIGHT < top || y > bottom) {
                continue;
            }
            boolean chosen = card.source().equals(selected);
            boolean hover = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT && mouseY >= top && mouseY < bottom;
            g.fill(x - 2, y - 2, x + CARD_WIDTH + 2, y + CARD_HEIGHT + 2, chosen ? 0xFF5865F2 : hover ? 0xFF4E5058 : 0xFF2B2D31);
            g.fill(x, y, x + CARD_WIDTH, y + THUMB_HEIGHT, 0xFF111214);
            if (card.thumbnail() != null) {
                float scale = Math.min((float) CARD_WIDTH / card.width(), (float) THUMB_HEIGHT / card.height());
                int w = Math.round(card.width() * scale), h = Math.round(card.height() * scale);
                g.blit(card.thumbnail(), x + (CARD_WIDTH - w) / 2, y + (THUMB_HEIGHT - h) / 2, w, h, 0, 0,
                        card.width(), card.height(), card.width(), card.height());
            }
            g.drawString(font, font.plainSubstrByWidth(card.source().title(), CARD_WIDTH - 4), x + 2, y + THUMB_HEIGHT + 3, 0xFFFFFF);
        }
        g.disableScissor();
        if (windowsTab) {
            g.drawString(font, Component.translatable("create_media_systems.screen_share.minimized"), gridLeft(), bottom + 2, 0x888888);
        }
        g.drawString(font, Component.translatable("create_media_systems.screen_share.fps"), width / 2 - 206 + 104, height - 48, 0xFFFFFF);
    }

    private void renderPreview(GuiGraphics g) {
        ScreenShare share = ScreenShare.current();
        if (share != previewing) {
            if (previewing != null) {
                previewing.preview(previewSink, false);
            }
            previewing = share;
            if (share != null) {
                share.preview(previewSink, true);
            }
        }
        if (share == null) {
            g.drawCenteredString(font, Component.translatable(pos.equals(ScreenClient.server())
                    ? "create_media_systems.screen_share.status.starting" : "create_media_systems.screen_share.ended"),
                    width / 2, height / 2, 0xAAAAAA);
            return;
        }
        int boxW = Math.min(width - 40, (height - 120) * 16 / 9), boxH = boxW * 9 / 16;
        int x = (width - boxW) / 2, y = 26;
        g.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xFF5865F2);
        g.fill(x, y, x + boxW, y + boxH, 0xFF000000);
        ResourceLocation picture = preview == null ? null : preview.texture();
        if (picture != null && preview.width() > 0) {
            float scale = Math.min((float) boxW / preview.width(), (float) boxH / preview.height());
            int w = Math.round(preview.width() * scale), h = Math.round(preview.height() * scale);
            g.blit(picture, x + (boxW - w) / 2, y + (boxH - h) / 2, w, h, 0, 0, preview.width(), preview.height(),
                    preview.width(), preview.height());
        }
        ScreenShare.Settings settings = share.settings();
        int line = y + boxH + 6;
        g.drawCenteredString(font, Component.translatable("create_media_systems.screen_share.live", settings.source().title())
                .withStyle(ChatFormatting.GREEN), width / 2, line, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("create_media_systems.screen_share.stats",
                String.format(Locale.ROOT, "%.0f/%d", share.capturedFps, settings.fps()),
                String.format(Locale.ROOT, "%.0f", share.encodedFps), share.kbps, share.viewers), width / 2, line + 11, 0xCCCCCC);
        String status = share.status;
        Component told = status.isEmpty() ? Component.empty()
                : status.startsWith("failed:") ? Component.translatable("create_media_systems.screen_share.status.failed", status.substring(7))
                : Component.translatable("create_media_systems.screen_share.status." + status);
        g.drawCenteredString(font, told, width / 2, line + 22, 0xFFCC66);
        if (!share.soundStatus.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("create_media_systems.screen_share.sound_failed", share.soundStatus),
                    width / 2, line + 33, 0xFFCC66);
        } else if (settings.sound() != ScreenShare.Sound.NONE || settings.mic() != null) {
            int meter = 120;
            g.fill(width / 2 - meter / 2, line + 35, width / 2 + meter / 2, line + 39, 0xFF2B2D31);
            g.fill(width / 2 - meter / 2, line + 35, width / 2 - meter / 2 + Math.round(meter * Math.min(1, share.soundLevel())),
                    line + 39, 0xFF3BA55C);
        }
        ScreenServerBlock.Entity server = entity();
        if (server != null && !server.feeds()) {
            g.drawCenteredString(font, Component.translatable("create_media_systems.screen_share.no_player"), width / 2, line + 44, 0xAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (picking && micsOpen) {
            // A click anywhere closes the list, choosing what it was on.
            int option = micAt(mouseX, mouseY);
            if (option >= 0) {
                mic = option == 0 ? null : devices.get(option - 1).id();
                micButton.setMessage(micLabel());
            }
            micsOpen = false;
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (!picking || mouseY < 52 || mouseY > height - 70) {
            return false;
        }
        List<Card> cards = cards();
        int columns = columns(), left = gridLeft();
        for (int i = 0; i < cards.size(); i++) {
            int x = left + i % columns * (CARD_WIDTH + GAP), y = 52 + i / columns * (CARD_HEIGHT + GAP) - scroll;
            if (mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT) {
                selected = cards.get(i).source();
                if (sound == ScreenShare.Sound.APP && !selected.window()) {
                    message = Component.translatable("create_media_systems.screen_share.app_sound_window").withStyle(ChatFormatting.GOLD);
                } else {
                    message = Component.empty();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (picking && micsOpen) {
            micScroll = Math.clamp(micScroll - (int) Math.signum(scrollY), 0, devices.size() + 1 - micRows());
            return true;
        }
        if (picking) {
            int rows = (cards().size() + columns() - 1) / columns();
            int most = Math.max(0, rows * (CARD_HEIGHT + GAP) - (height - 70 - 52));
            scroll = Math.clamp(scroll - (int) (scrollY * 20), 0, most);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        if (previewing != null) {
            previewing.preview(previewSink, false);
            previewing = null;
        }
        VideoFrame shown = preview;
        preview = null;
        if (shown != null) {
            shown.close();
        }
        var textures = Minecraft.getInstance().getTextureManager();
        for (List<Card> list : List.of(windows, screens)) {
            for (Card card : list) {
                if (card.thumbnail() != null) {
                    textures.release(card.thumbnail());
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
