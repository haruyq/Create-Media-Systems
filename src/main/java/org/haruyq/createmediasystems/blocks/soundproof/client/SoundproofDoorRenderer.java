package org.haruyq.createmediasystems.blocks.soundproof.client;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofDoorBlock;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofDoorBlock.Kind;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofFeature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws a soundproof door's leaf and handle, moving as it opens and closes. The models are of a door facing east
 * (its leaf along the west side of its block, its opening from north to south), one for a hinge on the left (north)
 * and its mirror image for one on the right; the leaf is both halves high. A swinging leaf turns about its hinge
 * once its lever has been pressed down; a sliding one runs along its rail.
 */
public final class SoundproofDoorRenderer implements BlockEntityRenderer<SoundproofDoorBlock.Entity> {
    /** The part of opening in which the lever goes down and comes back, the leaf then only starting to move. */
    private static final float LEVER_PART = .35f;
    private static final float LEVER_DEGREES = 32;
    /** A double door's bar handle hangs at an angle, and is lifted level to open. */
    private static final float BAR_REST_DEGREES = 25;
    private static final float BAR_DEGREES = 55;
    /** How far a sliding leaf runs (sixteenths): its handle stays before its own block, where it can be reached. */
    private static final float SLIDE = 12.5f;
    /** Where the leaf's hinge is, and its lever's and bar's spindles, on a door hinged left (sixteenths). */
    private static final float HINGE_X = 1.5f;
    private static final float HINGE_Z = 1.5f;
    private static final float LEVER_Y = 15.5f;
    private static final float LEVER_Z = 13.5f;
    private static final float BAR_Y = 16;
    private static final float BAR_Z = 14.5f;

    private static final Map<Kind, Map<DoorHingeSide, ModelResourceLocation>> LEAVES = new EnumMap<>(Kind.class);
    private static final Map<DoorHingeSide, ModelResourceLocation> LEVER = sides("lever");
    private static final Map<DoorHingeSide, ModelResourceLocation> BAR = sides("bar");

    static {
        for (Kind kind : Kind.values()) {
            LEAVES.put(kind, sides(kind.name().toLowerCase(java.util.Locale.ROOT) + "_leaf"));
        }
    }

    private static Map<DoorHingeSide, ModelResourceLocation> sides(String name) {
        Map<DoorHingeSide, ModelResourceLocation> sides = new EnumMap<>(DoorHingeSide.class);
        for (DoorHingeSide side : DoorHingeSide.values()) {
            sides.put(side, ModelResourceLocation.standalone(CreateMediaSystems.id(
                    "block/soundproof/door/" + name + "_" + side.getSerializedName())));
        }
        return sides;
    }

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                SoundproofFeature.DOOR_ENTITY.get(), context -> new SoundproofDoorRenderer()));
        modBus.addListener((ModelEvent.RegisterAdditional event) -> {
            LEAVES.values().forEach(sides -> sides.values().forEach(event::register));
            List.of(LEVER, BAR).forEach(sides -> sides.values().forEach(event::register));
        });
    }

    /** The leaf is two blocks high, and a sliding one runs before the block beside its own. */
    @Override
    public AABB getRenderBoundingBox(SoundproofDoorBlock.Entity door) {
        return new AABB(door.getBlockPos()).inflate(1.5, 0, 1.5).expandTowards(0, 1, 0);
    }

    @Override
    public void render(SoundproofDoorBlock.Entity door, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockState state = door.getBlockState();
        if (door.getLevel() == null || !(state.getBlock() instanceof SoundproofDoorBlock block)) return;
        Kind kind = block.kind();
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        // A mirrored door turns, slides and presses its lever the other way.
        int way = hinge == DoorHingeSide.LEFT ? 1 : -1;
        float open = door.open(partialTick);
        float pressed = Mth.sin(Mth.PI * Mth.clamp(open / LEVER_PART, 0, 1));
        float moved = smooth(Mth.clamp((open - LEVER_PART / 2) / (1 - LEVER_PART / 2), 0, 1));
        // A sliding leaf hangs before its block, where the light is that of the block in front.
        int lit = kind == Kind.SLIDING
                ? LevelRenderer.getLightColor(door.getLevel(), door.getBlockPos().relative(facing.getOpposite())) : light;

        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot() - 90));
        pose.translate(-.5, 0, -.5);
        if (kind == Kind.SLIDING) {
            pose.translate(0, 0, -way * SLIDE / 16 * smooth(open));
        } else {
            float z = mirrored(HINGE_Z, way);
            pose.translate(HINGE_X / 16, 0, z / 16);
            pose.mulPose(Axis.YP.rotationDegrees(way * 90 * moved));
            pose.translate(-HINGE_X / 16, 0, -z / 16);
        }
        draw(pose, buffers, LEAVES.get(kind).get(hinge), lit, overlay);
        if (kind == Kind.NORMAL || kind == Kind.GLASS) {
            turned(pose, LEVER_Y, mirrored(LEVER_Z, way), -way * LEVER_DEGREES * pressed);
            draw(pose, buffers, LEVER.get(hinge), lit, overlay);
        } else if (kind == Kind.DOUBLE && hinge == DoorHingeSide.RIGHT) {
            // Only the right leaf has the handle; the left one is held by its bolts.
            turned(pose, BAR_Y, mirrored(BAR_Z, way), way * (BAR_REST_DEGREES + BAR_DEGREES * pressed));
            draw(pose, buffers, BAR.get(hinge), lit, overlay);
        }
        pose.popPose();
    }

    private static float mirrored(float z, int way) {
        return way > 0 ? z : 16 - z;
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /** Turns what is drawn next about the spindle through {@code y}, {@code z} (sixteenths) across the leaf. */
    private static void turned(PoseStack pose, float y, float z, float degrees) {
        pose.translate(0, y / 16, z / 16);
        pose.mulPose(Axis.XP.rotationDegrees(degrees));
        pose.translate(0, -y / 16, -z / 16);
    }

    /**
     * Only the glass is drawn translucent: what is translucent is drawn after the outline of the block looked at, which
     * would show through anything else drawn then.
     */
    private static void draw(PoseStack pose, MultiBufferSource buffers, ModelResourceLocation model, int light, int overlay) {
        VertexConsumer solid = buffers.getBuffer(Sheets.cutoutBlockSheet());
        VertexConsumer glass = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        for (BakedQuad quad : Minecraft.getInstance().getModelManager().getModel(model)
                .getQuads(null, null, RandomSource.create(42), ModelData.EMPTY, null)) {
            (quad.isTinted() ? glass : solid).putBulkData(pose.last(), quad, 1, 1, 1, 1, light, overlay);
        }
    }
}
