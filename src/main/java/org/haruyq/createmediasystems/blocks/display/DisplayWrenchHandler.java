package org.haruyq.createmediasystems.blocks.display;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Quill;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.playback.VideoFrame;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllKeys;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Tuning Wrench's controls on screens, beside its right-clicks (DisplayWrench, sent by LightingLinkHandler).
 * Looking at a display or image viewer: scroll makes it a block wider or narrower (with Shift, a sixteenth), its height
 * following its ratio; Ctrl+scroll steps through the ratios (free, 16:9, 4:3, 21:9, 1:1, the video's or image's own),
 * keeping the width; Alt+scroll sets the height alone (and the ratio to free). Ctrl+right-click on a display opens
 * its lighting panel. The screen looked at and the picked player are outlined.
 */
public final class DisplayWrenchHandler {
    private static final Object SCREEN_OUTLINE = new Object();
    private static final Object SOURCE_OUTLINE = new Object();

    @Nullable
    private static BlockPos hovered;

    private DisplayWrenchHandler() {
    }

    static void register() {
        Quill.listen(DisplayWrenchHandler::tick, DisplayWrenchHandler::mouseScrolled, () -> false);
    }

    @Nullable
    private static ScreenEntity screen(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return pos != null && level.getBlockEntity(pos) instanceof ScreenEntity screen ? screen : null;
    }

    /** On Ctrl+right-click at a display, opens its lighting panel; false if this right-click is not that. */
    public static boolean openPanel() {
        if (!isActive() || !AllKeys.ctrlDown() || !(screen(hovered) instanceof DisplayBlock.Entity display)) {
            return false;
        }
        Minecraft.getInstance().setScreen(new DisplayLightingScreen(display));
        return true;
    }

    private static boolean mouseScrolled(double delta) {
        ScreenEntity display = isActive() ? screen(hovered) : null;
        if (display == null) {
            return false;
        }
        int direction = (int) Math.signum(delta);
        if (direction == 0) {
            return true;
        }
        int step = direction * (Screen.hasShiftDown() ? 1 : DisplayBlock.UNIT);
        if (Screen.hasAltDown()) {
            resize(display, display.widthUnits(), display.heightUnits() + step, DisplayBlock.Ratio.FREE);
        } else if (AllKeys.ctrlDown()) {
            DisplayBlock.Ratio ratio = display.ratio().step(direction);
            resize(display, display.widthUnits(), heightFor(display, ratio, display.widthUnits()), ratio);
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
        if (frame != null && frame.height() > 0) {
            return frame.width() / (double) frame.height();
        }
        Images.Picture still = DisplayRenderer.image((DisplayBlock.Entity) screen);
        return still != null ? still.width / (double) still.height : DisplayBlock.Ratio.WIDE.value;
    }

    private static void resize(ScreenEntity display, int width, int height, DisplayBlock.Ratio ratio) {
        int w = DisplayBlock.clampSize(width);
        int h = DisplayBlock.clampSize(height);
        PacketDistributor.sendToServer(new DisplayWrench.ResizeDisplay(display.getBlockPos(), w, h, ratio.ordinal()));
        Minecraft.getInstance().player.displayClientMessage(DisplayBlock.sizeText(w, h, ratio), true);
    }

    private static void tick() {
        if (!isActive()) {
            hovered = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult trace = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY ? null : Quill.pointedAt();
        hovered = trace == null ? null : DisplayBlock.anchor(mc.level, trace.getBlockPos());
        BlockPos picked = DisplayWrench.picked(mc.player.getMainHandItem(), mc.level);
        ScreenEntity display = screen(hovered);
        if (display == null) {
            hovered = null;
        } else {
            boolean linked = !(display instanceof DisplayBlock.Entity linkable)
                    || picked != null && linkable.source().filter(picked::equals).isPresent();
            Outliner.getInstance().showAABB(SCREEN_OUTLINE, SableCompat.toWorld(mc.level, display.box(0, 0)))
                    .colored(linked ? Quill.SETTING : Quill.PICKED).lineWidth(Quill.LINE_WIDTH);
        }
        if (picked != null) {
            Outliner.getInstance().showAABB(SOURCE_OUTLINE, SableCompat.toWorld(mc.level, new AABB(picked)))
                    .colored(Quill.PICKED).lineWidth(Quill.LINE_WIDTH);
        }
    }

    private static boolean isActive() {
        return Quill.held(LightingFeature.LIGHTING_LINK.get());
    }
}
