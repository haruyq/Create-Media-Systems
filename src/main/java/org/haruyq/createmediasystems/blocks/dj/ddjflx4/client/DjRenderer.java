package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.Kind;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.PadMode;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjState;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.client.DjClient.View;
import org.haruyq.createmediasystems.client.QuadOutput;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.screen.ScreenMonitorBlock;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the DDJ-FLX4: its shapes from code, each control where Control places it and as the controller's state has it
 * (knobs turned, faders pushed, lamps lit, the jog wheels' middles going round as their decks play), and what is
 * printed on it from a picture of its top. It is drawn in the top view's own lie, x across from the left and y from
 * its front edge to its back, z up out of its top. It also draws rekordbox's decks (see DjDisplay) on each Monitor
 * beside the controller or on top of it.
 */
public final class DjRenderer implements BlockEntityRenderer<DdjFlx4Block.Entity> {
    private static final float S = DdjFlx4Block.SCALE, DEPTH = DdjFlx4Block.DEPTH;
    /** How thick its body is over its feet, how round its corners are, and how much of its edge is cut away above and below. */
    private static final float BODY = DdjFlx4Block.HEIGHT - .12f, ROUND = 13 * S, CHAMFER = .045f, UNDER = .32f;
    /** How wide (pixels) it is seen from which its round parts are drawn with all their faces, and down to which with half. */
    private static final float FINE = 260, COARSE = 70;
    /** Set in a turned shape's colour: every other face of that band is darker, as a grip's grooves are. */
    private static final int RIBBED = 1 << 24;
    private static final int SIDE = 0x2B2B2E, EDGE = 0x3C3C40, BELOW = 0x19191B, FOOT = 0x0C0C0D, RIM = 0x161618, JOG_SIDE = 0x1C1C1F,
            JOG_SLOPE = 0x2E2E32, SILVER = 0x9A9AA2, KNOB = 0x1B1B1D, KNOB_TOP = 0x242427, POINTER = 0xEDEDED, CAP = 0x1D1D20,
            CAP_SLOPE = 0x2C2C30, HOLE = 0x050506, ORANGE = 0xFF8A1E, HOVER = 0x8AEAFF;
    private static final int[] METER = {0x4CE06A, 0x4CE06A, 0xFFB030, 0xFFB030, 0xF04A3A};
    /**
     * The picture of its top (tools/ddj_flx4/gen_top.py draws it from Control's places), at each size it is drawn
     * from: all of the top with no lamp lit in its upper half, and
     * with every lamp lit in its lower. Smoothed between its texels, which entityCutoutNoCull's are not; it has no
     * mipmaps, so the smallest size still at least 1.5 times as wide as the controller is seen is drawn from (a size
     * too small is blurred; a size too large only shimmers a little).
     */
    private static final int[] PRINT_WIDTHS = {2048, 1024, 512, 256, 128};
    private static final RenderType[] PRINTS = new RenderType[PRINT_WIDTHS.length];
    /** Its outline at the edge of its top, round its sides, and round its underside: x, y pairs, the same number in each. */
    private static final float[] TOP = outline(CHAMFER), WAIST = outline(0), FLOOR = outline(UNDER);
    /** The Monitors it draws on, from its own block. */
    private static final BlockPos[] MONITORS = {BlockPos.ZERO.above(), BlockPos.ZERO.north(), BlockPos.ZERO.south(),
            BlockPos.ZERO.east(), BlockPos.ZERO.west()};
    /** The screen on a Monitor's model, as screen.ScreenMonitor has it: facing north, in blocks. */
    private static final float SCREEN_X0 = .5f / 16, SCREEN_X1 = 15.5f / 16, SCREEN_Y0 = 5f / 16, SCREEN_Y1 = 13f / 16,
            SCREEN_Z = 7f / 16 - .002f;

    static {
        for (int level = 0; level < PRINTS.length; level++) {
            PRINTS[level] = RenderType.create("create_media_systems_dj_print_" + level, DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 1536, true, false, RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(
                                    CreateMediaSystems.id("textures/dj/ddj_flx4_top_" + level + ".png"), true, false))
                            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setOverlayState(RenderStateShard.OVERLAY)
                            .createCompositeState(true));
        }
    }

    @Override
    public void render(DdjFlx4Block.Entity controller, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
            int overlay) {
        BlockPos pos = controller.getBlockPos();
        Minecraft client = Minecraft.getInstance();
        double away = Math.sqrt(client.gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(SableCompat.toRenderWorld(controller.getLevel(), pos, Vec3.atBottomCenterOf(pos).add(0, DdjFlx4Block.HEIGHT / 16, 0))));
        // It is a block wide, lying flat on its block's floor (not at the block's middle): how wide that is seen from
        // where the camera is. In a Ponder scene or on a contraption the camera's place says nothing of that: there it is drawn as from a few blocks off.
        float wide = controller.getLevel() != client.level ? FINE : (float) (client.getWindow().getHeight()
                / (2 * Math.max(away, .1) * Math.tan(Math.toRadians(client.options.fov().get() / 2.0))));
        View view = DjClient.view(controller);
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-controller.facing().toYRot()));
        pose.translate(-.5, 0, -.5);
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        draw(view, pose, buffers, light, wide, -1);
        pose.popPose();
        for (BlockPos beside : MONITORS) {
            BlockState monitor = controller.getLevel().getBlockState(pos.offset(beside));
            if (monitor.getBlock() instanceof ScreenMonitorBlock) {
                pose.pushPose();
                pose.translate(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
                pose.mulPose(Axis.YP.rotationDegrees(180 - monitor.getValue(ScreenMonitorBlock.FACING).toYRot()));
                pose.translate(-.5, 0, -.5);
                // The display's x runs to the right as it is looked at, its y down, its z out towards the eye.
                pose.translate(SCREEN_X1, SCREEN_Y1, SCREEN_Z);
                float unit = (SCREEN_X1 - SCREEN_X0) / DjDisplay.WIDTH;
                pose.scale(-unit, -(SCREEN_Y1 - SCREEN_Y0) / DjDisplay.HEIGHT, -unit);
                // ponytail: as far off as the controller beside it is, which is a block out at the most.
                DjDisplay.draw(view, pose, buffers, true, wide * unit);
                pose.popPose();
            }
        }
    }

    @Override
    public AABB getRenderBoundingBox(DdjFlx4Block.Entity controller) {
        return new AABB(controller.getBlockPos()).inflate(1);
    }

    /**
     * The controller in its block's sixteenths, its front towards +z, seen {@code wide} pixels wide, with the control
     * in slot {@code hovered} (-1: none) outlined.
     */
    static void draw(View view, PoseStack pose, MultiBufferSource buffers, int light, float wide, int hovered) {
        DjState state = view.state();
        int level = PRINTS.length - 1;
        while (level > 0 && PRINT_WIDTHS[level] < wide * 1.5f) {
            level--;
        }
        pose.pushPose();
        pose.translate(0, DdjFlx4Block.HEIGHT, 16 - DdjFlx4Block.REAR);
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        Pen pen = new Pen(pose, light, wide);
        // Each kind of its faces together: the shapes, then what is printed, then what glows.
        for (pen.pass = Pen.SOLID; pen.pass <= Pen.GLOW; pen.pass++) {
            pen.out = buffers.getBuffer(pen.pass == Pen.SOLID ? Mesh.solid() : pen.pass == Pen.PRINT ? PRINTS[level] : QuadOutput.FLAT);
            body(pen);
            for (Control control : Control.values()) {
                for (int deck = 0; deck < control.count(); deck++) {
                    control(pen, view, control, deck);
                    if (control.slot(deck) == hovered) {
                        float w = control.width * S / 2 + .05f, d = control.depth * S / 2 + .05f;
                        pen.outline(x(control.x(deck)) - w, y(control.y) - d, x(control.x(deck)) + w, y(control.y) + d, .7f);
                    }
                }
            }
            for (int deck = 0; deck < 2; deck++) {
                // A channel's five lamps, between the two channels' knobs: their windows are printed, dark.
                int lit = view.playing(deck) ? Math.round(view.level() * 5 * state.place(Control.CHANNEL_FADER, deck)) : 0;
                for (int step = 0; step < lit; step++) {
                    float y = y(446 - step * 16), x = x(487 + deck * 16);
                    pen.glow(x - 2.6f * S, y - 4.2f * S, x + 2.6f * S, y + 4.2f * S, .02f, METER[step]);
                }
            }
        }
        pose.popPose();
    }

    /** Across the top, in sixteenths, of {@code px} of the top view; and from the front edge towards the back, of {@code py}. */
    private static float x(float px) {
        return (px - Control.LEFT) * S;
    }

    private static float y(float py) {
        return (Control.FRONT - py) * S;
    }

    /** The top's outline {@code in} inside its edge: a rectangle with round corners, anticlockwise from its left side. */
    private static float[] outline(float in) {
        int steps = 5;
        float radius = Math.max(.02f, ROUND - in), middle = in + radius;
        float[] outline = new float[8 * (steps + 1)];
        for (int corner = 0, at = 0; corner < 4; corner++) {
            float cx = corner == 1 || corner == 2 ? 16 - middle : middle, cy = corner >= 2 ? DEPTH - middle : middle;
            for (int step = 0; step <= steps; step++) {
                double angle = Math.PI * (1 + corner * .5 + step * .5 / steps);
                outline[at++] = cx + radius * (float) Math.cos(angle);
                outline[at++] = cy + radius * (float) Math.sin(angle);
            }
        }
        return outline;
    }

    /** Its body: the top with what is printed on it, the edge cut away round it, its sides, its underside drawn in, its feet and sockets. */
    private static void body(Pen pen) {
        pen.fan(TOP, 0, true, 0);
        pen.band(TOP, 0, WAIST, -CHAMFER, .7071f, EDGE);
        pen.band(WAIST, -CHAMFER, WAIST, UNDER * .6f - BODY, 0, SIDE);
        pen.band(WAIST, UNDER * .6f - BODY, FLOOR, -BODY, -.8f, BELOW);
        pen.fan(FLOOR, -BODY, false, BELOW);
        for (int foot = 0; foot < 4; foot++) {
            pen.lathe(foot % 2 == 0 ? 1.3f : 14.7f, foot < 2 ? 1.1f : DEPTH - 1.1f, -DdjFlx4Block.HEIGHT, 12,
                    new float[]{.34f, .12f, .34f, 0}, FOOT);
        }
        if (!pen.fine) {
            return;
        }
        // ponytail: the sockets are where a photograph from above shows something at its back, not measured.
        pen.pose.pushPose();
        pen.pose.translate(0, DEPTH, -BODY * .5f);
        pen.pose.mulPose(Axis.XP.rotationDegrees(-90));
        // The microphone's socket: a nut round a hole.
        pen.lathe(x(295), 0, 0, 12, new float[]{.17f, 0, .17f, .05f, .11f, .05f, .11f, .1f, .07f, .1f, .07f, 0, 0, 0},
                RIM, RIM, SILVER, SILVER, HOLE, HOLE);
        // The master output's two sockets.
        for (int px = 357; px <= 377; px += 20) {
            pen.lathe(x(px), 0, 0, 12, new float[]{.1f, 0, .1f, .04f, .075f, .04f, .075f, .2f, .05f, .2f, .05f, .08f, 0, .08f},
                    SILVER, SILVER, SILVER, SILVER, HOLE, px == 357 ? 0xB0B0B0 : 0xA02A24);
        }
        // The two USB sockets.
        for (int px = 505; px <= 535; px += 30) {
            pen.box(x(px) - .15f, -.055f, 0, x(px) + .15f, .055f, .012f, HOLE);
        }
        pen.pose.popPose();
        // The headphones' socket, in front.
        pen.pose.pushPose();
        pen.pose.translate(x(84), 0, -BODY * .45f);
        pen.pose.mulPose(Axis.XP.rotationDegrees(90));
        pen.lathe(0, 0, 0, 12, new float[]{.1f, 0, .1f, .025f, .055f, .025f, .055f, .005f, 0, .005f}, SILVER, SILVER, HOLE, HOLE);
        pen.pose.popPose();
    }

    private static void control(Pen pen, View view, Control control, int deck) {
        DjState state = view.state();
        float x = x(control.x(deck)), y = y(control.y), w = control.width * S, d = control.depth * S;
        boolean down = control.kind.pressed() && state.down(control, deck), lit = lit(view, control, deck);
        switch (control.kind) {
            case BUTTON, PAD -> {
                // A round button is as wide as it is deep; a pad is square. Each shows its own piece of the print on top.
                boolean round = control.kind == Kind.BUTTON && control.width == control.depth;
                float top = (control.kind == Kind.PAD ? .1f : !round ? .07f : control.width > 30 ? .12f : .09f) * (down ? .35f : 1);
                if (round) {
                    float radius = w / 2;
                    pen.lathe(x, y, 0, pen.around(24), new float[]{radius, 0, radius, top - .02f, radius - .02f, top}, RIM, RIM);
                    pen.disc(x, y, 0, radius - .02f, top, pen.around(24), lit, 0);
                } else {
                    pen.walls(x - w / 2, y - d / 2, x + w / 2, y + d / 2, top, RIM);
                    pen.face(x - w / 2, y - d / 2, x + w / 2, y + d / 2, top, lit);
                }
            }
            case KNOB, ENCODER -> {
                float radius = w / 2, top = control == Control.BROWSE && state.down(control, deck) ? .46f : .56f;
                // A skirt, a grooved grip narrowing upwards, a shoulder and a flat top.
                pen.lathe(x, y, 0, pen.around(24), new float[]{radius, 0, radius, .06f, radius * .86f, .1f, radius * .78f, top - .05f,
                        radius * .68f, top, 0, top}, KNOB, KNOB, KNOB | RIBBED, KNOB_TOP, KNOB_TOP);
                if (control.kind == Kind.KNOB) {
                    pen.pose.pushPose();
                    pen.pose.translate(x, y, 0);
                    pen.pose.mulPose(Axis.ZP.rotationDegrees(-(state.place(control, deck) - .5f) * Control.KNOB_SWEEP));
                    // Its line: across its top from near the middle, and down its side.
                    pen.box(-.022f, radius * .12f, top, .022f, radius * .69f, top + .012f, POINTER);
                    pen.quad(-.022f, radius * .69f + .006f, top + .012f, .022f, radius * .69f + .006f, top + .012f,
                            .022f, radius * .86f + .006f, .1f, -.022f, radius * .86f + .006f, .1f, 0, .96f, .28f, POINTER);
                    pen.pose.popPose();
                }
            }
            case FADER, CROSSFADER -> {
                // Its slot and scale are printed; its cap rises from both ends of its travel to a ridge with a white line.
                boolean across = control.kind == Kind.CROSSFADER;
                float cap = .26f, travel = (across ? w : d) - cap, place = (state.place(control, deck) - .5f) * travel;
                float half = (across ? d : w) / 2, end = cap / 2, ridge = .028f, low = .13f, high = .36f;
                pen.pose.pushPose();
                pen.pose.translate(across ? x + place : x, across ? y : y + place, 0);
                if (across) {
                    pen.pose.mulPose(Axis.ZP.rotationDegrees(90));
                }
                pen.box(-.04f, -.04f, 0, .04f, .04f, .05f, HOLE);
                pen.box(-half, -end, .05f, half, end, low, CAP);
                for (int side = -1; side <= 1; side += 2) {
                    pen.quad(-half, side * end, low, half, side * end, low, half, side * ridge, high, -half, side * ridge, high,
                            0, side * .7071f, .7071f, CAP_SLOPE);
                    pen.quad(side * half, -end, low, side * half, -ridge, high, side * half, ridge, high, side * half, end, low,
                            side, 0, 0, CAP);
                }
                pen.quad(-half, -ridge, high, half, -ridge, high, half, ridge, high, -half, ridge, high, 0, 0, 1, POINTER);
                pen.pose.popPose();
            }
            case SWITCH -> {
                // Its slot is printed.
                float at = x + (state.value(control, deck) - 1) * w / 3;
                pen.box(at - w / 9, y - d / 4, 0, at + w / 9, y + d / 4, .15f, CAP);
                pen.box(at - w / 30, y - d / 4, .15f, at + w / 30, y + d / 4, .19f, CAP_SLOPE);
            }
            case JOG -> jog(pen, x, y, w / 2, view.seconds()[deck], state.down(control, deck));
        }
    }

    /** A jog wheel: its middle goes round as a record would under it, and its ring is lit while it is touched. */
    private static void jog(Pen pen, float x, float y, float radius, double seconds, boolean touched) {
        float top = .34f, plate = radius * .885f, ring = radius * .375f, hub = radius * .31f;
        int faces = pen.around(60);
        // A foot, a side dimpled for the fingers, and the slope up to its top.
        pen.lathe(x, y, 0, faces, new float[]{radius, 0, radius, .05f, radius * .985f, .08f, radius * .965f, top - .07f, plate, top},
                JOG_SIDE, RIM, JOG_SIDE | RIBBED, JOG_SLOPE);
        pen.disc(x, y, ring, plate, top, faces, false, 0);
        if (touched) {
            pen.ring(x, y, top, ring, hub, faces, ORANGE);
        } else {
            pen.disc(x, y, hub, ring, top, faces, false, 0);
        }
        float turn = (float) (-seconds * DjClient.TURNS_PER_SECOND % 1 * 2 * Math.PI);
        pen.disc(x, y, 0, hub, top, faces, false, turn);
        pen.pose.pushPose();
        pen.pose.translate(x, y, top);
        pen.pose.mulPose(Axis.ZP.rotation(turn));
        pen.box(-.03f, radius * .11f, 0, .03f, radius * .215f, .008f, POINTER);
        pen.pose.popPose();
    }

    /** Whether a button's or a pad's lamp is lit. */
    private static boolean lit(View view, Control control, int deck) {
        DjState state = view.state();
        if (state.down(control, deck) || state.lamp(control, deck)) {
            return true;
        }
        PadMode mode = state.padMode[deck];
        return switch (control) {
            // Blinking while a loaded deck waits.
            case PLAY -> view.playing(deck) || view.loaded(deck) && Util.getMillis() % 1000 < 500;
            case CUE, LOOP_IN, LOOP_OUT -> view.loaded(deck);
            case MODE_HOT_CUE, MODE_PAD_FX, MODE_BEAT_JUMP, MODE_SAMPLER -> mode.button == control;
            case PAD_1, PAD_2, PAD_3, PAD_4, PAD_5, PAD_6, PAD_7, PAD_8 -> {
                DjState.Track track = view.tracks()[deck];
                // A hot cue's pad is lit once it is set; the pads of the other modes always do something.
                yield mode != PadMode.HOT_CUE ? mode != PadMode.KEYBOARD && mode != PadMode.KEY_SHIFT
                        : track != null && track.cues()[control.number] >= 0;
            }
            default -> false;
        };
    }

    /**
     * Draws on the top: x across it, y from its front edge to its back, z out of it. It is gone over once for each
     * kind of face, and each of its strokes draws only in the pass of its own kind.
     */
    private static final class Pen {
        static final int SOLID = 0, PRINT = 1, GLOW = 2;
        final PoseStack pose;
        final int light;
        /** Whether it is seen wide enough for its smallest parts. */
        final boolean fine;
        private final float wide;
        int pass;
        VertexConsumer out;

        Pen(PoseStack pose, int light, float wide) {
            this.pose = pose;
            this.light = light;
            this.wide = wide;
            fine = wide >= FINE;
        }

        /** How many faces a round part that has {@code faces} from near has from here. */
        int around(int faces) {
            return fine ? faces : wide >= COARSE ? faces / 2 : Math.max(6, faces / 4);
        }

        void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy,
                float dz, float nx, float ny, float nz, int color) {
            if (pass == SOLID) {
                Mesh.quad(pose, out, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz, color, light);
            }
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            if (pass == SOLID) {
                Mesh.box(pose, out, x0, y0, z0, x1, y1, z1, color, light);
            }
        }

        /** The four sides of a box standing on the top, without its top. */
        void walls(float x0, float y0, float x1, float y1, float z, int color) {
            quad(x0, y0, 0, x1, y0, 0, x1, y0, z, x0, y0, z, 0, -1, 0, color);
            quad(x0, y1, 0, x1, y1, 0, x1, y1, z, x0, y1, z, 0, 1, 0, color);
            quad(x0, y0, 0, x0, y1, 0, x0, y1, z, x0, y0, z, -1, 0, 0, color);
            quad(x1, y0, 0, x1, y1, 0, x1, y1, z, x1, y0, z, 1, 0, 0, color);
        }

        /** A flat ring of one colour, lit by itself. */
        void ring(float x, float y, float z, float outer, float inner, int faces, int color) {
            if (pass == SOLID) {
                Mesh.ring(pose, out, x, y, z, outer, inner, faces, 1, color, Mesh.FULL_BRIGHT);
            }
        }

        /**
         * A shape turned about the upright through (x, y), standing on z: {@code rings} are radius and height pairs
         * from its foot outwards and up to its top, each joined to the next by a band of its colour.
         */
        void lathe(float x, float y, float z, int faces, float[] rings, int... colors) {
            if (pass != SOLID) {
                return;
            }
            for (int face = 0; face < faces; face++) {
                double a = face * 2 * Math.PI / faces, b = (face + 1) * 2 * Math.PI / faces, middle = (a + b) / 2;
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
                for (int band = 0; band * 2 + 3 < rings.length; band++) {
                    float r0 = rings[band * 2], z0 = z + rings[band * 2 + 1], r1 = rings[band * 2 + 2], z1 = z + rings[band * 2 + 3];
                    float length = (float) Math.hypot(r1 - r0, z1 - z0), outwards = (z1 - z0) / length, up = (r0 - r1) / length;
                    int color = colors[band];
                    if ((color & RIBBED) != 0 && (face & 1) != 0) {
                        color = (color >> 2 & 0x3F3F3F) * 3;
                    }
                    quad(x + r0 * ca, y + r0 * sa, z0, x + r0 * cb, y + r0 * sb, z0, x + r1 * cb, y + r1 * sb, z1,
                            x + r1 * ca, y + r1 * sa, z1, outwards * (float) Math.cos(middle), outwards * (float) Math.sin(middle), up,
                            color);
                }
            }
        }

        /** The faces between two outlines of the body, one at each height, leaning {@code up} (the sine of how far). */
        void band(float[] high, float highZ, float[] low, float lowZ, float up, int color) {
            float flat = (float) Math.sqrt(1 - up * up);
            for (int at = 0; at < low.length; at += 2) {
                int next = (at + 2) % low.length;
                float run = WAIST[next] - WAIST[at], rise = WAIST[next + 1] - WAIST[at + 1], length = (float) Math.hypot(run, rise);
                quad(high[at], high[at + 1], highZ, high[next], high[next + 1], highZ, low[next], low[next + 1], lowZ,
                        low[at], low[at + 1], lowZ, flat * rise / length, -flat * run / length, up, color);
            }
        }

        /** All that is inside an outline of the body, at z: showing what is printed there, or of one colour and facing down. */
        void fan(float[] outline, float z, boolean print, int color) {
            for (int at = 0; at < outline.length; at += 4) {
                int next = (at + 2) % outline.length, last = (at + 4) % outline.length;
                if (!print) {
                    quad(8, DEPTH / 2, z, outline[at], outline[at + 1], z, outline[next], outline[next + 1], z,
                            outline[last], outline[last + 1], z, 0, 0, -1, color);
                } else if (pass == PRINT) {
                    printed(8, DEPTH / 2, z, 8, DEPTH / 2, false);
                    printed(outline[at], outline[at + 1], z, outline[at], outline[at + 1], false);
                    printed(outline[next], outline[next + 1], z, outline[next], outline[next + 1], false);
                    printed(outline[last], outline[last + 1], z, outline[last], outline[last + 1], false);
                }
            }
        }

        /** A flat rectangle at z showing what is printed at its own place: with its lamp lit, as bright as it is whatever the light. */
        void face(float x0, float y0, float x1, float y1, float z, boolean lit) {
            if (pass == PRINT) {
                printed(x0, y0, z, x0, y0, lit);
                printed(x1, y0, z, x1, y0, lit);
                printed(x1, y1, z, x1, y1, lit);
                printed(x0, y1, z, x0, y1, lit);
            }
        }

        /** A flat ring at z (a disc with {@code inner} 0) showing what is printed at its own place, turned {@code turn} about its middle. */
        void disc(float x, float y, float inner, float outer, float z, int faces, boolean lit, float turn) {
            if (pass != PRINT) {
                return;
            }
            for (int face = 0; face < faces; face++) {
                double a = face * 2 * Math.PI / faces, b = (face + 1) * 2 * Math.PI / faces;
                around(x, y, outer, a, turn, z, lit);
                around(x, y, outer, b, turn, z, lit);
                around(x, y, inner, b, turn, z, lit);
                around(x, y, inner, a, turn, z, lit);
            }
        }

        private void around(float x, float y, float radius, double angle, float turn, float z, boolean lit) {
            printed(x + radius * (float) Math.cos(angle + turn), y + radius * (float) Math.sin(angle + turn), z,
                    x + radius * (float) Math.cos(angle), y + radius * (float) Math.sin(angle), lit);
        }

        /** A corner at (x, y, z) showing what is printed at ({@code fromX}, {@code fromY}) of the top. */
        private void printed(float x, float y, float z, float fromX, float fromY, boolean lit) {
            Mesh.vertex(pose, out, x, y, z, fromX / 16, (1 - fromY / DEPTH + (lit ? 1 : 0)) / 2, 0xFFFFFF, 0, 0, 1,
                    lit ? Mesh.FULL_BRIGHT : light, OverlayTexture.NO_OVERLAY);
        }

        /** A flat rectangle {@code z} out of the top, as bright as it is whatever the light. */
        void glow(float x0, float y0, float x1, float y1, float z, int color) {
            if (pass != GLOW) {
                return;
            }
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            Matrix4f at = pose.last().pose();
            out.addVertex(at, x0, y0, z).setColor(r, g, b, 255);
            out.addVertex(at, x1, y0, z).setColor(r, g, b, 255);
            out.addVertex(at, x1, y1, z).setColor(r, g, b, 255);
            out.addVertex(at, x0, y1, z).setColor(r, g, b, 255);
        }

        void outline(float x0, float y0, float x1, float y1, float z) {
            float edge = .05f;
            glow(x0 - edge, y0 - edge, x1 + edge, y0, z, HOVER);
            glow(x0 - edge, y1, x1 + edge, y1 + edge, z, HOVER);
            glow(x0 - edge, y0, x0, y1, z, HOVER);
            glow(x1, y0, x1 + edge, y1, z, HOVER);
        }
    }

    /** The controller as an item: as it comes out of its box, nothing lit. */
    static final class Item extends BlockEntityWithoutLevelRenderer {
        private final View view = new View(new DjState(), new double[2], new DjState.Track[2], 0);

        Item() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack item, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light,
                int overlay) {
            pose.pushPose();
            // Lifted to the middle of its slot: it is flat.
            pose.translate(0, 7 / 16f, 0);
            pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
            // In a slot it is a few dozen pixels wide; in a hand, a few hundred.
            draw(view, pose, buffers, light, context == ItemDisplayContext.GUI ? 64 : 400, -1);
            pose.popPose();
        }
    }
}
