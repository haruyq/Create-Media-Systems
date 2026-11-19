package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.client;

import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxBlock;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxBlock.Band;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxFeature;
import org.haruyq.createmediasystems.blocks.boombox.zsrs81bt.BoomboxKey;
import org.haruyq.createmediasystems.blocks.component.m700.HifiStackBlock;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.client.QuadOutput;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.Shapes;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.DiscTransport;
import org.haruyq.createmediasystems.stream.MediaItem;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the CD radio from code, in its block's space (see BoomboxKey): a body rounded at both ends with a speaker in
 * each, the panel between them with its display and keys, the keys and the lid of the disc on its top, and its handle.
 */
public final class BoomboxRenderer implements BlockEntityRenderer<BoomboxBlock.Entity> {
    /** How near (blocks) the camera is for legends and display text to be drawn. */
    private static final double TEXT_RANGE = 12;
    /** The body's ends are half circles of this radius about this height. */
    private static final float END = 3.2f, AXIS = 3.4f;
    private static final int ARC = 6;
    /** How far (degrees) the lid swings up: short of the handle over it. */
    private static final float LID_SWING = 42;
    private static final float DISC = 2.55f;
    private static final float SPEAKER = 2.85f;
    private static final int BODY = 0x1A1A1C;
    private static final int PANEL = 0x26262A;
    private static final int BUTTON = 0x47474E;
    private static final int SILVER = 0xB4B8BE;
    private static final int LEGEND = 0xD2D2D2;
    private static final int FAINT = 0x9A9AA0;
    private static final int LCD_OFF = 0x2B3330;
    private static final int LCD_ON = 0xD6DEE0;
    private static final int LCD_INK = 0x10181C;
    private static final int AMBER = 0xFFB347;
    private static final int GREEN = 0x66FF88;
    private static final int HOVER = 0x8AEAFF;
    private static final int LEFT = 0, MIDDLE = 1, RIGHT = 2;

    /** The body's outline at stations across it: its top and bottom, and its back and front. */
    private static final float[] X, UPPER, LOWER, REAR, FACE;

    static {
        int stations = 2 * (ARC + 1);
        X = new float[stations];
        UPPER = new float[stations];
        LOWER = new float[stations];
        REAR = new float[stations];
        FACE = new float[stations];
        for (int i = 0; i <= ARC; i++) {
            double angle = i * Math.PI / 2 / ARC;
            float across = END - END * (float) Math.cos(angle), rise = END * (float) Math.sin(angle);
            // Seen from above, the ends are drawn in a little too.
            float drawn = (float) Math.pow(1 - Math.sin(angle), 2);
            for (int station : new int[]{i, stations - 1 - i}) {
                X[station] = station == i ? across : 16 - across;
                UPPER[station] = AXIS + rise;
                LOWER[station] = AXIS - rise;
                REAR[station] = BoomboxKey.BACK + drawn;
                FACE[station] = BoomboxKey.FRONT - .6f * drawn;
            }
        }
    }

    @Override
    public void render(BoomboxBlock.Entity set, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
            int light, int overlay) {
        BlockPos pos = set.getBlockPos();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean near = camera.distanceToSqr(SableCompat.toRenderWorld(set.getLevel(), pos, Vec3.atCenterOf(pos)))
                < TEXT_RANGE * TEXT_RANGE;
        draw(set, set.getBlockState().getValue(BoomboxBlock.FACING), partialTick, poseStack, buffers, light, near);
    }

    private static void draw(BoomboxBlock.Entity set, Direction facing, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, boolean text) {
        Pen pen = new Pen(pose, buffers, light, text);
        BoomboxKey hovered = BoomboxClient.hovered(set);
        BoomboxKey pressed = BoomboxClient.pressed(set);
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-0.5, 0, -0.5);
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);

        // Shapes' slab has its front at the lesser z, where this set has its back.
        Shapes.slab(pose, pen.solid, X, UPPER, LOWER, REAR, FACE, .35f, x -> BODY, x -> BODY, x -> 0x202023, light);
        for (float x : new float[]{2.6f, 12.2f}) {
            pen.box(x, 0, 5, x + 1.2f, .3f, 11, 0x101012);
        }
        // Its aerial, folded down along its back.
        pen.box(4, BoomboxKey.TOP, 3.75f, 14, BoomboxKey.TOP + .22f, 3.97f, SILVER);
        handle(pen);

        pose.pushPose();
        pose.translate(0, 0, BoomboxKey.FRONT);
        front(pen, set, pressed);
        if (hovered != null && hovered.face == BoomboxKey.Face.FRONT) {
            pen.outline(hovered, .3f);
        }
        pose.popPose();

        // The top: across from its left, from its front edge to its back, and up out of it.
        pose.pushPose();
        pose.translate(0, BoomboxKey.TOP, 16);
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        top(pen, set, pressed, Mth.lerp(partialTick, set.lastLid, set.lid));
        if (hovered != null && hovered.face == BoomboxKey.Face.TOP) {
            pen.outline(hovered, .3f);
        }
        pose.popPose();
        pose.popPose();
    }

    /** A broad flat band, two arms and the grip between them, leant back a little about where it is hinged. */
    private static void handle(Pen pen) {
        pen.pose.pushPose();
        pen.pose.translate(0, 5.4f, 7);
        pen.pose.mulPose(Axis.XP.rotationDegrees(-15));
        pen.box(1.2f, 0, -.8f, 1.7f, 4.6f, .8f, 0x161618);
        pen.box(14.3f, 0, -.8f, 14.8f, 4.6f, .8f, 0x161618);
        pen.box(1.7f, 4.1f, -.8f, 14.3f, 4.6f, .8f, 0x1C1C1F);
        pen.pose.popPose();
    }

    private static void front(Pen pen, BoomboxBlock.Entity set, @Nullable BoomboxKey pressed) {
        for (float x : new float[]{END, 16 - END}) {
            // A grille as tall as the body, proud of its drawn-in end, with a bright rim round its lower half.
            pen.pose.pushPose();
            pen.pose.translate(x, AXIS, -.3f);
            Shapes.cylinder(pen.pose, pen.solid, SPEAKER, .34f, 32, BODY, 0x0C0C0E, pen.light);
            pen.pose.popPose();
            Mesh.ring(pen.pose, pen.solid, x, AXIS, .06f, SPEAKER, SPEAKER - .24f, 32, 1,
                    angle -> angle > Math.PI ? SILVER : 0x34343A, pen.light);
            Mesh.ring(pen.pose, pen.solid, x, AXIS, .06f, 1.95f, 1.8f, 32, 1, 0x26262A, pen.light);
            Mesh.ring(pen.pose, pen.solid, x, AXIS, .06f, .7f, 0, 16, 1, 0x1E1E21, pen.light);
        }
        pen.box(5.95f, .6f, 0, 10.05f, 6.1f, .06f, PANEL);
        pen.text(8, 5.5f, .07f, .36f, "SUNNY", LEGEND, false, MIDDLE, 3);
        pen.text(8, .72f, .07f, .2f, "ZS-RS81BT", FAINT, false, MIDDLE, 3);
        display(pen, set);

        boolean on = set.power();
        DiscTransport transport = set.transport();
        key(pen, BoomboxKey.CD, pressed, "CD", on && set.band() == Band.CD ? AMBER : 0);
        key(pen, BoomboxKey.FM, pressed, "FM", on && set.band() == Band.FM ? AMBER : 0);
        key(pen, BoomboxKey.PLAY, pressed, "PLAY", on && transport.playing() && !transport.paused() ? GREEN : 0);
        key(pen, BoomboxKey.STOP, pressed, "STOP", 0);
        key(pen, BoomboxKey.PREVIOUS, pressed, "<<", 0);
        key(pen, BoomboxKey.NEXT, pressed, ">>", 0);
    }

    /** Its display: lit while it is on, with the band, the volume, and the station or the disc's track. */
    private static void display(Pen pen, BoomboxBlock.Entity set) {
        float left = 6.25f, right = 9.75f, bottom = 3.75f;
        pen.glow(left, bottom, right, 5.25f, .07f, set.power() ? LCD_ON : LCD_OFF);
        if (!set.power()) {
            return;
        }
        float z = .09f;
        boolean fm = set.band() == Band.FM;
        pen.lcd(left + .15f, bottom + 1.14f, z, .26f, fm ? "FM" : "CD", LEFT, 1);
        pen.lcd(right - .15f, bottom + 1.14f, z, .26f, "VOL " + set.volumeStep(), RIGHT, 1.6f);
        DiscTransport transport = set.transport();
        List<String> playlist = set.playlist();
        if (fm) {
            pen.lcd(right - .95f, bottom + .52f, z, .52f, RadioFeature.mhz(set.frequency()), RIGHT, 2.2f);
            pen.lcd(right - .15f, bottom + .52f, z, .26f, "MHz", RIGHT, .7f);
            if (set.signal() > 0) {
                pen.lcd(left + .15f, bottom + .1f, z, .22f, "TUNED", LEFT, 1.4f);
            }
        } else if (set.lidOpen()) {
            pen.lcd(8, bottom + .52f, z, .52f, "OPEN", MIDDLE, 3);
        } else if (playlist.isEmpty()) {
            pen.lcd(8, bottom + .52f, z, .52f, "NO DISC", MIDDLE, 3);
        } else {
            pen.lcd(left + .15f, bottom + .52f, z, .52f, String.format(Locale.ROOT, "%02d", set.shownIndex() + 1), LEFT, 1.1f);
            pen.lcd(left + 1.15f, bottom + .52f, z, .26f, "/" + playlist.size(), LEFT, .5f);
            pen.lcd(right - .15f, bottom + .52f, z, .52f, HifiStackBlock.Entity.clock(transport.clock().position()), RIGHT, 1.5f);
            pen.lcd(left + .15f, bottom + .1f, z, .22f, !transport.playing() ? "STOP" : transport.paused() ? "PAUSE"
                    : set.loading() ? "LOAD" : "PLAY", LEFT, 1.4f);
        }
    }

    private static void top(Pen pen, BoomboxBlock.Entity set, @Nullable BoomboxKey pressed, float open) {
        key(pen, BoomboxKey.POWER, pressed, "POWER", 0);
        key(pen, BoomboxKey.OPEN, pressed, "OPEN", 0);
        key(pen, BoomboxKey.VOLUME_DOWN, pressed, "-", 0);
        key(pen, BoomboxKey.VOLUME_UP, pressed, "+", 0);
        pen.text(11.4f, BoomboxKey.VOLUME_UP.top + .1f, .01f, .2f, "VOL", FAINT, false, MIDDLE, 1.5f);

        BoomboxKey lid = BoomboxKey.LID;
        float x = lid.centerX(), y = (lid.bottom + lid.top) / 2;
        if (open > 0) {
            // The well the disc lies in, on its spindle.
            pen.box(lid.left + .15f, lid.bottom + .15f, 0, lid.right - .15f, lid.top - .15f, .02f, 0x08080A);
            Mesh.ring(pen.pose, pen.solid, x, y, .1f, .42f, 0, 12, 1, 0x3A3A40, pen.light);
            DiscKind kind = MediaItem.kindOf(set.disc());
            if (kind != null) {
                int color = kind == DiscKind.CUSTOM_DISC ? 0x55555E : 0xD0D6DE;
                // Brighter in four sectors, as light runs across a disc.
                Mesh.ring(pen.pose, pen.solid, x, y, .06f, DISC, .42f, 32, 1,
                        angle -> Rgb.mix(color, 0xFFFFFF, .35f * (float) Math.abs(Math.cos(angle * 2))), pen.light);
            }
        }
        pen.pose.pushPose();
        pen.pose.translate(0, lid.top, .08f);
        pen.pose.mulPose(Axis.XP.rotationDegrees(-open * LID_SWING));
        float depth = lid.top - lid.bottom;
        pen.box(lid.left, -depth, 0, lid.right, 0, .16f, 0x232327);
        Mesh.ring(pen.pose, pen.solid, x, -depth / 2, .17f, DISC + .1f, DISC, 32, 1, 0x34343A, pen.light);
        pen.text(x, -depth + .3f, .17f, .24f, "CD", FAINT, false, MIDDLE, 2);
        pen.pose.popPose();
    }

    private static void key(Pen pen, BoomboxKey key, @Nullable BoomboxKey pressed, String label, int lamp) {
        float depth = key == pressed ? .09f : .2f;
        float from = key.face == BoomboxKey.Face.FRONT ? .06f : 0;
        pen.box(key.left, key.bottom, from, key.right, key.top, depth, BUTTON);
        float height = label.length() == 1 ? .4f : .2f;
        pen.text(key.centerX(), (key.bottom + key.top - height) / 2, depth + .01f, height, label, LEGEND, false, MIDDLE,
                key.right - key.left - .12f);
        if (lamp != 0) {
            pen.glow(key.left + .12f, key.top - .11f, key.right - .12f, key.top - .04f, depth + .012f, lamp);
        }
    }

    /** Draws on the face the pose stands on: x across it, y up it, z out of it. */
    private static final class Pen {
        final PoseStack pose;
        final MultiBufferSource buffers;
        final VertexConsumer solid;
        final VertexConsumer ink;
        final int light;
        final boolean text;

        Pen(PoseStack pose, MultiBufferSource buffers, int light, boolean text) {
            this.pose = pose;
            this.buffers = buffers;
            this.light = light;
            this.text = text;
            solid = new QuadOutput(buffers, Mesh.solid());
            ink = new QuadOutput(buffers, QuadOutput.FLAT);
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            Mesh.box(pose, solid, x0, y0, z0, x1, y1, z1, color, light);
        }

        /** A flat rectangle on the face, {@code z} out of it, as bright as it is whatever the light. */
        void glow(float x0, float y0, float x1, float y1, float z, int color) {
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            Matrix4f at = pose.last().pose();
            ink.addVertex(at, x0, y0, z).setColor(r, g, b, 255);
            ink.addVertex(at, x1, y0, z).setColor(r, g, b, 255);
            ink.addVertex(at, x1, y1, z).setColor(r, g, b, 255);
            ink.addVertex(at, x0, y1, z).setColor(r, g, b, 255);
        }

        /** {@code line} standing on {@code y}, {@code height} tall and {@code width} wide at most; only from near. */
        void text(float x, float y, float z, float height, String line, int color, boolean bright, int align, float width) {
            if (!text) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int wide = Math.max(1, font.width(line));
            float scale = Math.min(height / 7, width / wide);
            Matrix4f at = new Matrix4f(pose.last().pose())
                    .translate(x - (align == LEFT ? 0 : align == MIDDLE ? wide * scale / 2 : wide * scale), y + 7 * scale, z)
                    .scale(scale, -scale, scale);
            font.drawInBatch(line, 0, 0, color | 0xFF000000, false, at, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                    bright ? LightTexture.FULL_BRIGHT : light);
        }

        /** Dark on the lit display. */
        void lcd(float x, float y, float z, float height, String line, int align, float width) {
            text(x, y, z, height, line, LCD_INK, true, align, width);
        }

        void outline(BoomboxKey key, float z) {
            float edge = .06f;
            glow(key.left - edge, key.bottom - edge, key.right + edge, key.bottom, z, HOVER);
            glow(key.left - edge, key.top, key.right + edge, key.top + edge, z, HOVER);
            glow(key.left - edge, key.bottom, key.left, key.top, z, HOVER);
            glow(key.right, key.bottom, key.right + edge, key.top, z, HOVER);
        }
    }

    /** The set as an item: switched off, its lid shut. */
    static final class Item extends BlockEntityWithoutLevelRenderer {
        private BoomboxBlock.Entity set;

        Item() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack item, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                int light, int overlay) {
            if (set == null) {
                set = new BoomboxBlock.Entity(BlockPos.ZERO, BoomboxFeature.BLOCK.get().defaultBlockState());
            }
            poseStack.pushPose();
            // Lifted to the middle of its slot: it is 10 of 16 tall with its handle.
            poseStack.translate(0, 3 / 16f, 0);
            draw(set, Direction.NORTH, 0, poseStack, buffers, light, false);
            poseStack.popPose();
        }
    }
}
