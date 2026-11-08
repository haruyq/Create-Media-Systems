package org.haruyq.createmediasystems.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Quill;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.MediaSource;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllKeys;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.foundation.utility.RaycastHelper.PredicateTraceResult;
import com.simibubi.create.foundation.utility.RaycastHelper;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The range quill's controls, mirroring Create's schematic and quill: right-click a jukebox (or disc reader) to
 * pick it as the source, then right-click two corners (Ctrl targets the air, Ctrl+scroll pushes the highlighted
 * face), right-click again to set the volume and save; Shift+right-click discards the selection. Right-clicking
 * the picked jukebox with no selection opens the same screen to change only the volume or reset the range.
 * The selection is made in the picked source's block space, so a range for a jukebox on a moving structure (see
 * SableCompat) is picked and drawn where the structure is now.
 * Right-clicking a speaker opens its screen: volume, and a link to the picked source. Adapted from Create's
 * {@code SchematicAndQuillHandler} (MIT).
 */
public final class RangeQuillHandler {
    private static final Object SELECTION_OUTLINE = new Object();
    private static final Object SOURCE_OUTLINE = new Object();
    private static final Object CURRENT_RANGE_OUTLINE = new Object();

    /** The picked jukebox or disc reader: range settings apply to it, speakers get linked to it. */
    private static BlockPos source;
    /** The picked jukebox's saved settings, as last reported by the server; null until then, or for a reader. */
    private static JukeboxSettings current;
    /** Speaker whose screen opens once the server reports its settings. */
    private static BlockPos pendingSpeaker;
    /** Corners and the one pointed at, in the source's block space. */
    private static BlockPos firstPos;
    private static BlockPos secondPos;
    private static BlockPos selectedPos;
    private static BlockPos hoveredBlock;
    private static Direction selectedFace;
    private static int range = 10;

    private RangeQuillHandler() {
    }

    public static void register() {
        Quill.listen(RangeQuillHandler::tick, RangeQuillHandler::mouseScrolled, RangeQuillHandler::onRightClick);
    }

    public static void onSettings(RangeQuill.JukeboxSettingsInfo info) {
        if (info.jukebox().equals(source)) {
            current = info.settings();
        }
    }

    public static void onSpeakerInfo(RangeQuill.SpeakerInfo info) {
        Minecraft mc = Minecraft.getInstance();
        if (info.speaker().equals(pendingSpeaker) && isActive()) {
            pendingSpeaker = null;
            mc.setScreen(RangeQuillScreen.forSpeaker(info, source));
        }
    }

    @Nullable
    static JukeboxSettings current() {
        return current;
    }

    /** Size of the pending selection, or null when saving would keep the current range. */
    @Nullable
    static BoundingBox selection() {
        return firstPos == null || secondPos == null ? null : BoundingBox.fromCorners(firstPos, secondPos);
    }

    /** Saves {@code volume} with the selected range, the current range if none is selected, or the default. */
    static void save(float volume, boolean resetRange) {
        if (source == null) {
            return;
        }
        Optional<BoundingBox> area = resetRange ? Optional.empty() : Optional.ofNullable(selection());
        PacketDistributor.sendToServer(new RangeQuill.SetJukeboxSettings(source, area,
                !resetRange && area.isEmpty(), volume));
        firstPos = null;
        secondPos = null;
    }

    static void discard() {
        firstPos = null;
        secondPos = null;
        Quill.status("create_media_systems.range.abort");
    }

    private static boolean mouseScrolled(double delta) {
        if (!isActive() || !AllKeys.ctrlDown()) {
            return false;
        }
        if (secondPos == null) {
            range = (int) Mth.clamp(range + delta, 1, 100);
        }
        if (selectedFace == null) {
            return true;
        }

        AABB bb = new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(secondPos));
        Vec3i vec = selectedFace.getNormal();
        Vec3 projectedView = toSource(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        if (bb.contains(projectedView)) {
            delta *= -1;
        }
        int intDelta = (int) (delta > 0 ? Math.ceil(delta) : Math.floor(delta));
        int x = vec.getX() * intDelta;
        int y = vec.getY() * intDelta;
        int z = vec.getZ() * intDelta;

        AxisDirection axisDirection = selectedFace.getAxisDirection();
        if (axisDirection == AxisDirection.NEGATIVE) {
            bb = bb.move(-x, -y, -z);
        }
        double maxX = Math.max(bb.maxX - x * axisDirection.getStep(), bb.minX);
        double maxY = Math.max(bb.maxY - y * axisDirection.getStep(), bb.minY);
        double maxZ = Math.max(bb.maxZ - z * axisDirection.getStep(), bb.minZ);
        bb = new AABB(bb.minX, bb.minY, bb.minZ, maxX, maxY, maxZ);

        firstPos = BlockPos.containing(bb.minX, bb.minY, bb.minZ);
        secondPos = BlockPos.containing(bb.maxX, bb.maxY, bb.maxZ);
        Quill.status("create_media_systems.range.dimensions", (int) bb.getXsize() + 1, (int) bb.getYsize() + 1, (int) bb.getZsize() + 1);
        return true;
    }

    private static boolean onRightClick() {
        if (!isActive()) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player.isShiftKeyDown()) {
            discard();
            return true;
        }
        if (secondPos != null) {
            if (source == null || !Minecraft.getInstance().level.getBlockState(source).is(Blocks.JUKEBOX)) {
                Quill.status("create_media_systems.range.no_jukebox");
            } else {
                Minecraft.getInstance().setScreen(RangeQuillScreen.forJukebox());
            }
            return true;
        }
        BlockState hovered = hoveredBlock == null ? null : player.level().getBlockState(hoveredBlock);
        if (firstPos == null && hovered != null && hovered.is(CreateMediaSystems.SPEAKER.get())) {
            pendingSpeaker = hoveredBlock;
            PacketDistributor.sendToServer(new RangeQuill.QueryBlockSettings(hoveredBlock));
            return true;
        }
        if (firstPos == null && hovered != null && player.level().getBlockEntity(hoveredBlock) instanceof MediaSource) {
            if (!hoveredBlock.equals(source)) {
                source = hoveredBlock;
                current = null;
                PacketDistributor.sendToServer(new RangeQuill.QueryBlockSettings(source));
                Quill.status("create_media_systems.range.linked", source.getX(), source.getY(), source.getZ());
            } else if (hovered.is(Blocks.JUKEBOX)) {
                Minecraft.getInstance().setScreen(RangeQuillScreen.forJukebox());
            } else {
                Quill.status("create_media_systems.range.reader_has_no_range");
            }
            return true;
        }
        if (selectedPos == null) {
            Quill.status("create_media_systems.range.no_target");
            return true;
        }
        if (firstPos != null) {
            secondPos = selectedPos;
            Quill.status("create_media_systems.range.second_pos");
            return true;
        }
        firstPos = selectedPos;
        Quill.status("create_media_systems.range.first_pos");
        return true;
    }

    private static void tick() {
        if (!isActive()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        BlockHitResult trace = Quill.pointedAt();
        hoveredBlock = trace == null ? null : trace.getBlockPos();
        if (AllKeys.ACTIVATE_TOOL.isPressed()) {
            float pt = AnimationTickHolder.getPartialTicks();
            selectedPos = BlockPos.containing(toSource(player.getEyePosition(pt).add(player.getLookAngle().scale(range))));
        } else if (hoveredBlock != null) {
            BlockPos hit = hoveredBlock;
            boolean replaceable = player.level().getBlockState(hit)
                    .canBeReplaced(new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, trace)));
            if (trace.getDirection().getAxis().isVertical() && !replaceable) {
                hit = hit.relative(trace.getDirection());
            }
            selectedPos = toSource(hit);
        } else {
            selectedPos = null;
        }

        selectedFace = null;
        if (secondPos != null) {
            AABB bb = new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(secondPos))
                    .expandTowards(1, 1, 1).inflate(.45f);
            Vec3 projectedView = toSource(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
            boolean inside = bb.contains(projectedView);
            PredicateTraceResult result = RaycastHelper.rayTraceUntil(player, Reach.QUILL,
                    pos -> inside ^ bb.contains(toSource(VecHelper.getCenterOf(pos))));
            selectedFace = result.missed() ? null : inside ? result.getFacing().getOpposite() : result.getFacing();
        }

        AABB selection = currentSelectionBox();
        if (selection != null) {
            Outliner.getInstance().chaseAABB(SELECTION_OUTLINE, toWorld(selection))
                    .colored(Quill.SETTING)
                    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                    .lineWidth(Quill.LINE_WIDTH)
                    .highlightFace(selectedFace);
        }
        if (source != null) {
            Outliner.getInstance().showAABB(SOURCE_OUTLINE, toWorld(new AABB(source)))
                    .colored(Quill.PICKED)
                    .lineWidth(Quill.LINE_WIDTH);
        }
        if (firstPos == null && current != null && source != null) {
            current.areaAt(source).ifPresent(area -> Outliner.getInstance()
                    .showAABB(CURRENT_RANGE_OUTLINE, toWorld(area))
                    .colored(Quill.PICKED)
                    .lineWidth(1 / 32f));
        }
    }

    private static Vec3 toSource(Vec3 world) {
        return source == null ? world : SableCompat.toBlockSpace(Minecraft.getInstance().level, source, world);
    }

    /** The block at {@code pos} (on a moving structure or not) as the block of the source's space it covers. */
    private static BlockPos toSource(BlockPos pos) {
        return BlockPos.containing(toSource(SableCompat.toWorld(Minecraft.getInstance().level, pos.getCenter())));
    }

    // ponytail: on a turned structure the outlines are the upright boxes around the range, not the range itself;
    // drawing its 12 edges as lines would be exact, but loses Create's face highlight.
    private static AABB toWorld(AABB box) {
        return SableCompat.toWorld(Minecraft.getInstance().level, box);
    }

    private static boolean isActive() {
        return Quill.held(CreateMediaSystems.RANGE_QUILL.get());
    }

    private static AABB currentSelectionBox() {
        if (secondPos == null) {
            if (firstPos == null) {
                return selectedPos == null ? null : new AABB(selectedPos);
            }
            return selectedPos == null ? new AABB(firstPos)
                    : new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(selectedPos)).expandTowards(1, 1, 1);
        }
        return new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(secondPos)).expandTowards(1, 1, 1);
    }


}
