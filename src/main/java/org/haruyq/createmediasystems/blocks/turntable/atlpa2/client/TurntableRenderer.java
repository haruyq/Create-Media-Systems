package org.haruyq.createmediasystems.blocks.turntable.atlpa2.client;

import static org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableBlock;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableFeature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the whole turntable: its deck, and on it the platter turning with its record, the arm swung and lifted, the
 * lift lever and the dust cover as they are now (see TurntableClient). The models are of a turntable facing south;
 * the platter and the record on it are drawn round here. A record is black vinyl with the label of its disc's item:
 * the colours of the label in the middle of the disc's picture tint the record's label.
 */
public final class TurntableRenderer implements BlockEntityRenderer<TurntableBlock.Entity> {
    private static final ModelResourceLocation BASE = model("base"), HUB = model("hub"), ARM = model("arm"),
            CUE = model("cue"), COVER = model("cover"), LAMP = model("lamp");
    private static final ResourceLocation TEXTURE = CreateMediaSystems.id("block/turntable/at_lpa2"),
            VINYL = CreateMediaSystems.id("block/turntable/record"), LABEL = CreateMediaSystems.id("block/turntable/record_label");
    private static final int SEGMENTS = 128, POST_SEGMENTS = 32, WHITE = 0xFFFFFFFF;
    private static final float CX = (float) CENTER_X, CZ = (float) CENTER_Z;
    /** The belt's bottom and top round the platter's rim (sixteenths); gen_at_lpa2.py draws its run to the motor. */
    private static final float[] BELT = {3.18f, 3.32f};
    /**
     * The round posts, drawn smooth here (the item's model has them as boxes, see tools/at_lpa2/gen_at_lpa2.py):
     * x, z, radius, bottom, top (sixteenths), and 1 for black rubber, else aluminium. They are the four feet with
     * their rubber rings, the platter's bearing, the motor and its pulley, the tonearm's pillar, and the spindle.
     */
    private static final double[][] POSTS = {
            {2, 2, .9, 0, 1.25, 0}, {14, 2, .9, 0, 1.25, 0}, {2, 11, .9, 0, 1.25, 0}, {14, 11, .9, 0, 1.25, 0},
            {2, 2, .95, .9, 1.05, 1}, {14, 2, .95, .9, 1.05, 1}, {2, 11, .95, .9, 1.05, 1}, {14, 11, .95, .9, 1.05, 1},
            {CENTER_X, CENTER_Z, .8, 1.3, 2.5, 0}, {1.9, 11.1, .7, 1.3, 2.5, 0}, {1.9, 11.1, .45, 2.5, 3.45, 0},
            {PIVOT_X, PIVOT_Z, .9, 2.5, 3.0, 0}, {PIVOT_X, PIVOT_Z, .4, 3.0, 3.95, 0},
            {CENTER_X, CENTER_Z, .25, 2.72, 4.3, 0}};
    /** A 12-inch record's label is 100 mm across (sixteenths), with a ring of its second colour part way out. */
    private static final float LABEL_RADIUS = 1.82f, RING_IN = .55f, RING_OUT = .66f;
    /** The colours of the label of each disc picture (by its sprite's name): its own and its ring's. */
    private static final Map<ResourceLocation, int[]> LABELS = new HashMap<>();

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(CreateMediaSystems.id("block/turntable/at_lpa2/" + name));
    }

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                TurntableFeature.ENTITY.get(), context -> new TurntableRenderer()));
        modBus.addListener((ModelEvent.RegisterAdditional event) ->
                List.of(BASE, HUB, ARM, CUE, COVER, LAMP).forEach(event::register));
    }

    /** Its open cover stands up into the block above. */
    @Override
    public AABB getRenderBoundingBox(TurntableBlock.Entity turntable) {
        return new AABB(turntable.getBlockPos()).expandTowards(0, 1, 0).inflate(.2);
    }

    @Override
    public void render(TurntableBlock.Entity turntable, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        if (turntable.getLevel() == null) return;
        TurntableClient.Motion motion = TurntableClient.motion(turntable);
        Direction facing = turntable.getBlockState().getValue(TurntableBlock.FACING);
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-.5, 0, -.5);
        draw(pose, buffers, BASE, light, overlay);
        posts(pose, buffers, light, overlay);

        if (turntable.power()) {
            lamp(pose, buffers, LAMPS[0], overlay);
            lamp(pose, buffers, LAMPS[turntable.speed() == TurntableBlock.Speed.RPM_45 ? 2 : 1], overlay);
        }

        // The platter turns clockwise seen from above, with the plate under it and the record on it.
        pose.pushPose();
        turned(pose, CENTER_X, 0, CENTER_Z, Axis.YP, -motion.platter(partialTick));
        draw(pose, buffers, HUB, light, overlay);
        platter(pose, buffers, light, overlay);
        if (!turntable.record().isEmpty()) {
            record(pose, buffers, turntable.record(), light, overlay);
        }
        pose.popPose();

        // The arm swings about its pillar, and its head rises about the pivot's crossways axis.
        pose.pushPose();
        pose.translate(PIVOT_X / 16, PIVOT_Y / 16, PIVOT_Z / 16);
        pose.mulPose(Axis.YP.rotationDegrees(-motion.arm(partialTick)));
        pose.mulPose(Axis.XP.rotationDegrees(-LIFT_DEGREES * TurntableClient.lift(turntable, partialTick)));
        pose.translate(-PIVOT_X / 16, -PIVOT_Y / 16, -PIVOT_Z / 16);
        draw(pose, buffers, ARM, light, overlay);
        pose.popPose();

        pose.pushPose();
        turned(pose, 0, CUE_Y, CUE_Z, Axis.XP, -CUE_DEGREES * TurntableClient.lever(turntable, partialTick));
        draw(pose, buffers, CUE, light, overlay);
        pose.popPose();

        pose.pushPose();
        turned(pose, 0, HINGE_Y, HINGE_Z, Axis.XP, -COVER_DEGREES * smooth(motion.cover(partialTick)));
        draw(pose, buffers, COVER, light, overlay);
        pose.popPose();
        pose.popPose();
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /** Turns what is drawn next by {@code degrees} about {@code axis} through the point (sixteenths). */
    private static void turned(PoseStack pose, double x, double y, double z, Axis axis, float degrees) {
        pose.translate(x / 16, y / 16, z / 16);
        pose.mulPose(axis.rotationDegrees(degrees));
        pose.translate(-x / 16, -y / 16, -z / 16);
    }

    private static void lamp(PoseStack pose, MultiBufferSource buffers, double[] at, int overlay) {
        pose.pushPose();
        pose.translate(at[0] / 16, 0, at[1] / 16);
        draw(pose, buffers, LAMP, LightTexture.FULL_BRIGHT, overlay);
        pose.popPose();
    }

    /** The round posts: aluminium (the texture's third material) or black (its fourth), lit round their sides. */
    private static void posts(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        TextureAtlasSprite sprite = sprite(TEXTURE);
        VertexConsumer out = buffers.getBuffer(Sheets.cutoutBlockSheet());
        for (double[] post : POSTS) {
            float x = (float) post[0], z = (float) post[1], r = (float) post[2];
            float bottom = (float) post[3] / 16, top = (float) post[4] / 16, u = post[5] > 0 ? .875f : .625f;
            ring(pose, out, sprite, x, z, POST_SEGMENTS, u, .125f, .1f / r, 0, r, top, 1, WHITE, light, overlay);
            ring(pose, out, sprite, x, z, POST_SEGMENTS, u, .125f, .1f / r, 0, r, bottom, -1, WHITE, light, overlay);
            float v0 = sprite.getV(.02f), v1 = sprite.getV(.02f + (top - bottom) * 2);
            for (int i = 0; i < POST_SEGMENTS; i++) {
                float[] a = corner(i, POST_SEGMENTS), b = corner(i + 1, POST_SEGMENTS);
                float ua = sprite.getU(u - .1f + .2f * i / POST_SEGMENTS), ub = sprite.getU(u - .1f + .2f * (i + 1) / POST_SEGMENTS);
                vertex(pose, out, (x + a[0] * r) / 16, top, (z + a[1] * r) / 16, ua, v0, a[0], 0, a[1], WHITE, light, overlay);
                vertex(pose, out, (x + b[0] * r) / 16, top, (z + b[1] * r) / 16, ub, v0, b[0], 0, b[1], WHITE, light, overlay);
                vertex(pose, out, (x + b[0] * r) / 16, bottom, (z + b[1] * r) / 16, ub, v1, b[0], 0, b[1], WHITE, light, overlay);
                vertex(pose, out, (x + a[0] * r) / 16, bottom, (z + a[1] * r) / 16, ua, v1, a[0], 0, a[1], WHITE, light, overlay);
            }
        }
    }

    /** The clear platter: a thick acrylic disc, and the thin belt round its rim. */
    private static void platter(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        TextureAtlasSprite sprite = sprite(TEXTURE);
        VertexConsumer out = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        float r = (float) PLATTER_RADIUS, top = (float) PLATTER_TOP / 16, bottom = (float) PLATTER_BOTTOM / 16;
        // Acrylic (the texture's first material) on its faces, its cut edge (the second) on its rim.
        ring(pose, out, sprite, CX, CZ, SEGMENTS, .125f, .125f, .12f / r, 0, r, top, 1, WHITE, light, overlay);
        ring(pose, out, sprite, CX, CZ, SEGMENTS, .125f, .125f, .12f / r, 0, r, bottom, -1, WHITE, light, overlay);
        band(pose, out, sprite, r, bottom, top, .26f, light, overlay);
        // The belt (the texture's fourth material, black) a hair outside the rim, as high as the model's belt.
        band(pose, buffers.getBuffer(Sheets.cutoutBlockSheet()), sprite, r + .02f, BELT[0] / 16, BELT[1] / 16, .76f, light, overlay);
    }

    /** A round band facing out, radius {@code r} (sixteenths) about the spindle, textured from the material at u0. */
    private static void band(PoseStack pose, VertexConsumer out, TextureAtlasSprite sprite, float r, float bottom, float top,
            float u0, int light, int overlay) {
        float u = sprite.getU(u0), u1 = sprite.getU(u0 + .23f), v0 = sprite.getV(.01f), v1 = sprite.getV(.24f);
        float cx = (float) CENTER_X / 16, cz = (float) CENTER_Z / 16, rim = r / 16;
        for (int i = 0; i < SEGMENTS; i++) {
            float[] a = corner(i, SEGMENTS), b = corner(i + 1, SEGMENTS);
            float nx = (a[0] + b[0]) / 2, nz = (a[1] + b[1]) / 2;
            vertex(pose, out, cx + a[0] * rim, top, cz + a[1] * rim, u, v0, nx, 0, nz, WHITE, light, overlay);
            vertex(pose, out, cx + b[0] * rim, top, cz + b[1] * rim, u1, v0, nx, 0, nz, WHITE, light, overlay);
            vertex(pose, out, cx + b[0] * rim, bottom, cz + b[1] * rim, u1, v1, nx, 0, nz, WHITE, light, overlay);
            vertex(pose, out, cx + a[0] * rim, bottom, cz + a[1] * rim, u, v1, nx, 0, nz, WHITE, light, overlay);
        }
    }

    /** The record, both its sides: the vinyl round its label, and the label in its disc's colours. */
    private static void record(PoseStack pose, MultiBufferSource buffers, ItemStack disc, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite picture = mc.getItemRenderer().getModel(disc, mc.level, null, 0).getParticleIcon(ModelData.EMPTY);
        int[] label = LABELS.computeIfAbsent(picture.contents().name(), name -> labelColours(picture));
        VertexConsumer out = buffers.getBuffer(Sheets.cutoutBlockSheet());
        TextureAtlasSprite vinyl = sprite(VINYL), paper = sprite(LABEL);
        float r = (float) RECORD_RADIUS, l = LABEL_RADIUS;
        for (int up : new int[] {1, -1}) {
            float y = (float) (up > 0 ? RECORD_TOP : PLATTER_TOP + .01) / 16;
            ring(pose, out, vinyl, CX, CZ, SEGMENTS, .5f, .5f, .5f / r, l, r, y, up, WHITE, light, overlay);
            ring(pose, out, paper, CX, CZ, SEGMENTS, .5f, .5f, .5f / l, 0, l * RING_IN, y, up, label[0], light, overlay);
            ring(pose, out, paper, CX, CZ, SEGMENTS, .5f, .5f, .5f / l, l * RING_IN, l * RING_OUT, y, up, label[1], light, overlay);
            ring(pose, out, paper, CX, CZ, SEGMENTS, .5f, .5f, .5f / l, l * RING_OUT, l, y, up, label[0], light, overlay);
        }
    }

    /**
     * The label's colour and its ring's, from the middle of the disc's picture: its most common and next most common
     * bright colours there, or a cream label with a darker ring if it has none.
     */
    private static int[] labelColours(TextureAtlasSprite picture) {
        var image = picture.contents().getOriginalImage();
        int w = picture.contents().width(), h = picture.contents().height();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = h * 5 / 16; y < h * 10 / 16; y++) {
            for (int x = w * 4 / 16; x < w * 11 / 16; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int red = abgr & 0xFF, green = abgr >> 8 & 0xFF, blue = abgr >> 16 & 0xFF;
                int max = Math.max(red, Math.max(green, blue)), min = Math.min(red, Math.min(green, blue));
                if ((abgr >>> 24) > 128 && max > 90 && (max - min > max / 3 || min > 200)) {
                    counts.merge(red << 16 | green << 8 | blue, 1, Integer::sum);
                }
            }
        }
        List<Integer> colours = new ArrayList<>(counts.keySet());
        colours.sort(Comparator.comparing(counts::get).reversed());
        int first = colours.isEmpty() ? 0xE8DCC0 : colours.getFirst();
        int second = colours.size() > 1 ? colours.get(1) : first >> 1 & 0x7F7F7F;
        return new int[] {first | 0xFF000000, second | 0xFF000000};
    }

    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    }

    private static float[] corner(int i, int segments) {
        double angle = 2 * Math.PI * i / segments;
        return new float[] {(float) Math.cos(angle), (float) Math.sin(angle)};
    }

    /**
     * A flat round face, or a band of one, at {@code y} (blocks) about {@code x}, {@code z} from radius {@code r0} to
     * {@code r1} (sixteenths) in {@code segments}, facing up ({@code up} 1) or down. Its texture is {@code sprite}
     * round {@code u}, {@code v} (fractions of it), {@code uvPerRadius} of it per sixteenth out from the middle.
     */
    private static void ring(PoseStack pose, VertexConsumer out, TextureAtlasSprite sprite, float x, float z, int segments,
                             float u, float v, float uvPerRadius, float r0, float r1, float y, int up, int colour,
                             int light, int overlay) {
        float cx = x / 16, cz = z / 16;
        for (int i = 0; i < segments; i++) {
            float[] a = corner(i, segments), b = corner(i + 1, segments);
            float[][] quad = up > 0 ? new float[][] {{b[0], b[1], r0}, {b[0], b[1], r1}, {a[0], a[1], r1}, {a[0], a[1], r0}}
                    : new float[][] {{a[0], a[1], r0}, {a[0], a[1], r1}, {b[0], b[1], r1}, {b[0], b[1], r0}};
            for (float[] c : quad) {
                vertex(pose, out, cx + c[0] * c[2] / 16, y, cz + c[1] * c[2] / 16,
                        sprite.getU(u + c[0] * c[2] * uvPerRadius), sprite.getV(v + c[1] * c[2] * uvPerRadius),
                        0, up, 0, colour, light, overlay);
            }
        }
    }

    private static void vertex(PoseStack pose, VertexConsumer out, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int colour, int light, int overlay) {
        out.addVertex(pose.last(), x, y, z).setColor(colour).setUv(u, v).setOverlay(overlay)
                .setLight(light).setNormal(pose.last(), nx, ny, nz);
    }

    /**
     * Only the clear acrylic (its faces carry a tint index) is drawn translucent: what is translucent is drawn after
     * the outline of the block looked at, which would show through anything else drawn then.
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
