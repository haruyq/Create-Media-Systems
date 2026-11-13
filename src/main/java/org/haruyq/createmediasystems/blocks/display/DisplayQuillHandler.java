package org.haruyq.createmediasystems.blocks.display;

import java.util.Optional;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Quill;
import org.haruyq.createmediasystems.playback.VideoFrame;
import org.haruyq.createmediasystems.stream.VideoSource;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllKeys;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The display quill's controls, like the range quill's: right-click a Blu-ray player to pick it, then right-click
 * displays to link them to it (again to unlink). Looking at a display: Ctrl+scroll makes it a block wider or
 * narrower (with Shift, a sixteenth), its height following its ratio; Alt+scroll sets the height alone (and the
 * ratio to free); Shift+right-click steps through the ratios (free, 16:9, 4:3, 21:9, 1:1, the video's or image's
 * own). Image viewers are sized the same; right-clicking one takes its frame away or puts it back.
 */
final class DisplayQuillHandler {
    private static final Object SCREEN_OUTLINE = new Object();
    private static final Object SOURCE_OUTLINE = new Object();

    @Nullable
    private static BlockPos videoPlayer;
    @Nullable
    private static BlockPos hovered;
    /** The display last pointed at, to say what to do with it once, when it comes into view. */
    @Nullable
    private static BlockPos hinted;

    private DisplayQuillHandler() {
    }

    static void register() {
        Quill.listen(DisplayQuillHandler::tick, DisplayQuillHandler::mouseScrolled, DisplayQuillHandler::onRightClick);
    }

    @Nullable
    private static ScreenEntity screen(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return pos != null && level.getBlockEntity(pos) instanceof ScreenEntity screen ? screen : null;
    }

    private static boolean onRightClick() {
        if (!isActive() || hovered == null) {
            return false;
        }
        var mc = Minecraft.getInstance();
        ScreenEntity screen = screen(hovered);
        if (screen == null) {
            if (!(mc.level.getBlockEntity(hovered) instanceof VideoSource)) {
                return false;
            }
            videoPlayer = hovered;
            Quill.status("create_media_systems.display_quill.picked", videoPlayer.getX(), videoPlayer.getY(), videoPlayer.getZ());
            return true;
        }
        if (mc.player.isShiftKeyDown()) {
            DisplayBlock.Ratio ratio = screen.ratio().next();
            resize(screen, screen.widthUnits(), heightFor(screen, ratio, screen.widthUnits()), ratio);
            return true;
        }
        if (!(screen instanceof DisplayBlock.Entity display)) {
            PacketDistributor.sendToServer(new DisplayQuill.ToggleFrame(hovered));
            return true;
        }
        if (videoPlayer == null) {
            Quill.status("create_media_systems.display_quill.pick_first");
            return true;
        }
        boolean linked = linked(display);
        PacketDistributor.sendToServer(new DisplayQuill.LinkDisplay(hovered, linked ? Optional.empty() : Optional.of(videoPlayer)));
        return true;
    }

    private static boolean mouseScrolled(double delta) {
        ScreenEntity display = isActive() ? screen(hovered) : null;
        boolean alt = Screen.hasAltDown();
        if (display == null || !(AllKeys.ctrlDown() || alt)) {
            return false;
        }
        int step = (int) Math.signum(delta) * (Screen.hasShiftDown() ? 1 : DisplayBlock.UNIT);
        if (alt) {
            resize(display, display.widthUnits(), display.heightUnits() + step, DisplayBlock.Ratio.FREE);
        } else {
            int width = DisplayBlock.clampSize(display.widthUnits() + step);
            resize(display, width, heightFor(display, display.ratio(), width), display.ratio());
        }
        return true;
    }

    /** The height (sixteenths) a screen {@code width} wide has at {@code ratio}; as it is now when free. */
    private static int heightFor(ScreenEntity display, DisplayBlock.Ratio ratio, int width) {
        double value = ratio.value;
        if (ratio == DisplayBlock.Ratio.VIDEO) {
            value = pictureRatio(display);
        }
        return value <= 0 ? display.heightUnits() : DisplayBlock.clampSize((int) Math.round(width / value));
    }

    /** Width over height of the picture {@code screen} shows: its image, or its video; 16:9 while it shows none. */
    private static double pictureRatio(ScreenEntity screen) {
        if (screen instanceof ImageViewerBlock.Entity viewer) {
            Images.Picture picture = ImageViewerRenderer.picture(viewer);
            return picture != null ? picture.width / (double) picture.height : DisplayBlock.Ratio.WIDE.value;
        }
        VideoFrame frame = DisplayRenderer.frame((DisplayBlock.Entity) screen);
        return frame != null && frame.height() > 0 ? frame.width() / (double) frame.height() : DisplayBlock.Ratio.WIDE.value;
    }

    private static void resize(ScreenEntity display, int width, int height, DisplayBlock.Ratio ratio) {
        int w = DisplayBlock.clampSize(width);
        int h = DisplayBlock.clampSize(height);
        PacketDistributor.sendToServer(new DisplayQuill.ResizeDisplay(display.getBlockPos(), w, h, ratio.ordinal()));
        Minecraft.getInstance().player.displayClientMessage(DisplayBlock.sizeText(w, h, ratio), true);
    }

    private static void tick() {
        if (!isActive()) {
            hovered = null;
            return;
        }
        BlockHitResult trace = Quill.pointedAt();
        hovered = trace == null ? null : trace.getBlockPos();
        ScreenEntity display = screen(hovered);
        if (display != null && !display.getBlockPos().equals(hinted)) {
            boolean linked = linked(display);
            if (!(display instanceof DisplayBlock.Entity linkable)) {
                Quill.status("create_media_systems.display_quill.hint_frame");
            } else if (videoPlayer == null && linkable.source().isEmpty()) {
                Quill.status("create_media_systems.display_quill.hint_pick");
            } else if (videoPlayer != null && !linked) {
                Quill.status("create_media_systems.display_quill.hint_link");
            } else {
                Minecraft.getInstance().player.displayClientMessage(
                        DisplayBlock.sizeText(display.widthUnits(), display.heightUnits(), display.ratio()), true);
            }
        }
        hinted = display == null ? null : display.getBlockPos();
        if (display != null) {
            Outliner.getInstance().showAABB(SCREEN_OUTLINE, SableCompat.toWorld(Minecraft.getInstance().level, display.box(0, 0)))
                    .colored(!(display instanceof DisplayBlock.Entity) || linked(display) ? Quill.SETTING : Quill.PICKED)
                    .lineWidth(Quill.LINE_WIDTH);
        }
        if (videoPlayer != null) {
            Outliner.getInstance().showAABB(SOURCE_OUTLINE, SableCompat.toWorld(Minecraft.getInstance().level, new AABB(videoPlayer)))
                    .colored(Quill.PICKED).lineWidth(Quill.LINE_WIDTH);
        }
    }

    private static boolean isActive() {
        return Quill.held(DisplayFeature.DISPLAY_QUILL.get());
    }

    private static boolean linked(ScreenEntity screen) {
        return videoPlayer != null && screen instanceof DisplayBlock.Entity display
                && display.source().filter(videoPlayer::equals).isPresent();
    }
}
