package org.haruyq.createmediasystems.blocks.stagecustom.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock.Entity.CymbalStrike;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock.Entity.Strike;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Cymbal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Pedal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Plate;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;

final class StageCustomCompleteClient {
    private static final ModelResourceLocation HARDWARE = StageCustomClient.part("complete_hardware");
    private static final ModelResourceLocation BEATER = StageCustomClient.part("kick_beater");
    private static final ModelResourceLocation KICK_BOARD = StageCustomClient.part("kick_footboard");
    private static final ModelResourceLocation HAT_BOARD = StageCustomClient.part("hat_footboard");
    private static final ModelResourceLocation CLUTCH = StageCustomClient.part("hat_clutch");
    private static final List<ModelResourceLocation> MODELS = List.of(HARDWARE, BEATER, KICK_BOARD, HAT_BOARD, CLUTCH);
    private static final Cymbal[] CYMBALS = Cymbal.values();
    private static final int SEGMENTS = 64;
    private static final float[] RADII = {.034f,.094f,.16f,.208f,.253f,.31f,.41f,.54f,.68f,.81f,.92f,1};
    private static final float[] COS = wave(true), SIN = wave(false);
    private static final Vec3 KICK_HINGE = StageCustomCymbals.hinge(Pedal.KICK);
    private static final Vec3 HAT_HINGE = StageCustomCymbals.hinge(Pedal.HI_HAT);
    private static final Vec3 BEATER_AXLE = StageCustomCymbals.beaterAxle();

    private StageCustomCompleteClient() {}

    static void register(IEventBus bus) {
        bus.addListener((ModelEvent.RegisterAdditional event) -> MODELS.forEach(event::register));
    }

    static void render(StageCustomBlock.Entity kit, Motion motion, Strike bass, long now, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        double gap = motion.advance(kit.hatOpen(), now);
        StageCustomClient.baked(HARDWARE, pose, buffers, light, overlay);
        double age = bass == null ? Double.POSITIVE_INFINITY : Math.max(0, (now - bass.time()) / 1e9);
        float down = age >= .26 ? 0 : (float) Math.exp(-age * 18);
        pivot(KICK_BOARD, KICK_HINGE, -10 * down, pose, buffers, light, overlay);
        pivot(BEATER, BEATER_AXLE, -32.2f * down, pose, buffers, light, overlay);
        pivot(HAT_BOARD, HAT_HINGE, (float) (-12 * (1 - gap / StageCustomCymbals.openGap())), pose, buffers, light, overlay);
        pose.pushPose();
        pose.translate(0, gap, 0);
        StageCustomClient.baked(CLUTCH, pose, buffers, light, overlay);
        pose.popPose();

        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(CreateMediaSystems.id("block/stage_custom/cymbal_bronze"));
        var out = buffers.getBuffer(Sheets.solidBlockSheet());
        for (Cymbal cymbal : CYMBALS) {
            Plate plate = StageCustomCymbals.plate(cymbal);
            pose.pushPose();
            pose.translate(.5 + plate.x(), plate.y(), .5 + plate.z());
            pose.mulPose(Axis.XP.rotationDegrees((float) plate.tilt()));
            var strike = motion.latest[cymbal.ordinal()];
            double ring = ring(cymbal, strike, now);
            if (cymbal == Cymbal.HI_HAT) {
                if (strike != null && strike.voice() == Voice.HAT_OPEN && !kit.hatOpen()) {
                    ring *= Math.pow(gap / StageCustomCymbals.openGap(), 2);
                }
                cymbal(plate, pose, out, atlas, 3, -.00145, -1, strike, ring * .21, light, overlay);
                cymbal(plate, pose, out, atlas, 0, gap, 1, strike, ring, light, overlay);
            } else {
                cymbal(plate, pose, out, atlas, cymbal.ordinal(), 0, 1, strike, ring, light, overlay);
            }
            pose.popPose();
        }
    }

    private static void pivot(ModelResourceLocation model, Vec3 axle, float angle, PoseStack pose, MultiBufferSource buffers,
                              int light, int overlay) {
        pose.pushPose();
        pose.translate(.5 + axle.x, axle.y, .5 + axle.z);
        pose.mulPose(Axis.XP.rotationDegrees(angle));
        pose.translate(-.5 - axle.x, -axle.y, -.5 - axle.z);
        StageCustomClient.baked(model, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static double ring(Cymbal cymbal, CymbalStrike strike, long now) {
        if (strike == null) return 0;
        double age = Math.max(0, (now - strike.time()) / 1e9);
        if (age > .79) return 0;
        double size = switch (cymbal) {
            case HI_HAT -> strike.voice() == Voice.HAT_OPEN ? .009 : .004;
            case CRASH -> .026;
            case RIDE -> strike.voice() == Voice.RIDE_BELL ? .006 : .013;
        };
        double decay = cymbal == Cymbal.HI_HAT && strike.voice() != Voice.HAT_OPEN ? .055 : .190;
        double wave = -.58 * Math.exp(-age / .024) + .91 * Math.exp(-age / decay) * Math.sin(age * 66);
        return size * strike.strength() * wave;
    }

    private static void cymbal(Plate plate, PoseStack pose, VertexConsumer out, TextureAtlasSprite atlas, int tile,
                               double offset, int bowlDirection, CymbalStrike strike, double ring, int light, int overlay) {
        float u = strike == null ? 0 : strike.u(), v = strike == null ? 0 : strike.v();
        for (int i = 1; i < RADII.length; i++) {
            float r0 = RADII[i - 1], r1 = RADII[i];
            double y0 = offset + bowlDirection * plate.profile(r0 * plate.radius());
            double y1 = offset + bowlDirection * plate.profile(r1 * plate.radius());
            double slope = (y1 - y0) / ((r1 - r0) * plate.radius());
            for (int j = 0; j < SEGMENTS; j++) {
                vertex(plate, pose, out, atlas, tile, r0, j+1, y0, slope, u, v, ring, 1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r1, j+1, y1, slope, u, v, ring, 1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r1, j, y1, slope, u, v, ring, 1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r0, j, y0, slope, u, v, ring, 1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r0, j, y0, slope, u, v, ring, -1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r1, j, y1, slope, u, v, ring, -1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r1, j+1, y1, slope, u, v, ring, -1, light, overlay);
                vertex(plate, pose, out, atlas, tile, r0, j+1, y0, slope, u, v, ring, -1, light, overlay);
            }
        }
    }

    private static void vertex(Plate plate, PoseStack pose, VertexConsumer out, TextureAtlasSprite atlas, int tile,
                               float r, int segment, double height, double slope, float hitU, float hitV, double ring,
                               int face, int light, int overlay) {
        float c = COS[segment], s = SIN[segment], x = r * c, z = r * s;
        double flex = r * r * (.56 + .44 * (c * hitU + s * hitV));
        float textureX = (tile % 2) * .5f + .25f + x * .246f;
        float textureY = (tile / 2) * .5f + .25f + z * .246f;
        float normal = (float) (face / Math.sqrt(1 + slope * slope));
        int shade = face == 1 ? 255 : 201;
        out.addVertex(pose.last(), (float) (x * plate.radius()), (float) (height + ring * flex + face * .00065),
                        (float) (z * plate.radius()))
                .setColor(shade, shade, shade, 255).setUv(atlas.getU(textureX), atlas.getV(textureY)).setOverlay(overlay)
                .setLight(light).setNormal(pose.last(), (float) (-slope * c) * normal, normal, (float) (-slope * s) * normal);
    }

    private static float[] wave(boolean cosine) {
        float[] data = new float[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS;
            data[i] = (float) (cosine ? Math.cos(a) : Math.sin(a));
        }
        return data;
    }

    static final class Motion {
        final CymbalStrike[] latest = new CymbalStrike[CYMBALS.length];
        private double gap;
        private long lastFrame;

        Motion(boolean open) {
            gap = open ? StageCustomCymbals.openGap() : 0;
        }

        double advance(boolean open, long now) {
            if (lastFrame != 0) {
                double target = open ? StageCustomCymbals.openGap() : 0;
                gap += (target - gap) * -Math.expm1(-Math.max(0, now - lastFrame) / 26_000_000.0);
            }
            lastFrame = now;
            return gap;
        }
    }
}
