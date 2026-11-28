package org.haruyq.createmediasystems.lighting;

import java.util.List;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayWrench;
import org.haruyq.createmediasystems.blocks.display.DisplayWrenchHandler;
import org.haruyq.createmediasystems.blocks.radio.RadioLink;
import org.haruyq.createmediasystems.client.Quill;

import org.jetbrains.annotations.Nullable;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The lighting link's controls, like the range quill's: right-click a lighting console to pick it, then
 * right-click fixtures to link them to its selected group (again, or with Shift, to unlink) and media players to
 * have it follow them. What a right-click would do is said once, when a block comes into view; the picked console
 * and its fixtures are outlined. Radio stations (RadioLink) and screens (DisplayWrench) come first; right-clicks on
 * anything else are left to the game.
 */
final class LightingLinkHandler {
    private static final Object CONSOLE_OUTLINE = new Object();
    private static final Object TARGET_OUTLINE = new Object();
    private static final Object FIXTURE_OUTLINE = new Object();

    /** The block a right-click links, picks or unlinks, or null if it would do nothing there. */
    @Nullable
    private static BlockPos hovered;
    @Nullable
    private static BlockPos hinted;

    private LightingLinkHandler() {
    }

    static void register() {
        Quill.listen(LightingLinkHandler::tick, delta -> false, LightingLinkHandler::onRightClick);
    }

    private static boolean onRightClick() {
        if (DisplayWrenchHandler.openPanel()) {
            return true;
        }
        if (!isActive() || hovered == null) {
            return false;
        }
        PacketDistributor.sendToServer(new LightingPackets.Link(hovered));
        return true;
    }

    private static void tick() {
        if (!isActive()) {
            hovered = null;
            hinted = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LightingConsoleBlock.Entity picked = LightingLinkItem.selected(mc.player);
        BlockHitResult trace = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY ? null : Quill.pointedAt();
        BlockPos pos = trace == null ? null : trace.getBlockPos();
        Component radio = pos == null ? null : RadioLink.hint(mc.player, pos);
        Component screen = pos == null || radio != null ? null : DisplayWrench.hint(mc.player, pos);
        Component hint = pos == null ? null : radio != null ? radio : screen != null ? screen
                : LinkAction.resolve(mc.player, pos).hint();
        hovered = hint == null ? null : pos;
        if (hovered != null && !hovered.equals(hinted)) {
            mc.player.displayClientMessage(hint, true);
        }
        hinted = hovered;

        BlockPos transmitter = RadioLink.picked(mc.player.getMainHandItem(), mc.level);
        if (transmitter != null) {
            outline(CONSOLE_OUTLINE, new AABB(transmitter), Quill.PICKED, Quill.LINE_WIDTH);
        }
        if (picked != null) {
            outline(CONSOLE_OUTLINE, box(picked), Quill.PICKED, Quill.LINE_WIDTH);
            for (BlockPos fixture : picked.fixtures()) {
                outline(List.of(FIXTURE_OUTLINE, fixture), new AABB(fixture), Quill.SETTING, 1 / 32f);
            }
        }
        // A screen's outline is DisplayWrenchHandler's.
        if (hovered == null || mc.level.getBlockState(hovered).getBlock() instanceof DisplayBlock) {
            return;
        }
        LightingConsoleBlock.Entity console = LightingConsoleBlock.find(mc.level, hovered);
        if (console != null) {
            if (console != picked) {
                outline(TARGET_OUTLINE, box(console), Quill.PICKED, Quill.LINE_WIDTH);
            }
        } else {
            boolean linked = picked != null && (picked.links(hovered)
                    || hovered.equals(picked.audioSource()) || hovered.equals(picked.videoSource()));
            outline(TARGET_OUTLINE, new AABB(hovered), linked ? Quill.SETTING : Quill.PICKED, Quill.LINE_WIDTH);
        }
    }

    private static AABB box(LightingConsoleBlock.Entity root) {
        return ConsoleLayout.bounds(root.getBlockPos(), root.getBlockState().getValue(LightingConsoleBlock.FACING));
    }

    private static void outline(Object slot, AABB box, int color, float width) {
        Outliner.getInstance().showAABB(slot, SableCompat.toWorld(Minecraft.getInstance().level, box))
                .colored(color).lineWidth(width);
    }

    private static boolean isActive() {
        return Quill.held(LightingFeature.LIGHTING_LINK.get());
    }
}
