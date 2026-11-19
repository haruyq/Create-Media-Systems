package org.haruyq.createmediasystems.items.walkman.nws310.client;

import static org.haruyq.createmediasystems.client.mesh.Mesh.box;

import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;

/** The Loudman as an item: a slim slab, glass and screen above, a brushed deck with its five-way pad below. */
final class LoudmanMesh {
    /** Half its width, height and depth, in blocks. */
    private static final float W = .13f, H = .29f, D = .018f;
    /** Where its pad's centre is, up its front. */
    private static final float PAD_Y = -.155f;

    private LoudmanMesh() {
    }

    /** {@code rgb} with each channel times {@code factor}. */
    static int shade(int rgb, float factor) {
        int r = Math.clamp(Math.round((rgb >> 16 & 0xFF) * factor), 0, 255);
        int g = Math.clamp(Math.round((rgb >> 8 & 0xFF) * factor), 0, 255);
        int b = Math.clamp(Math.round((rgb & 0xFF) * factor), 0, 255);
        return r << 16 | g << 8 | b;
    }

    /** Draws it around the origin, its front towards +z, in body colour {@code color}, set to {@code tuning}. */
    static void draw(PoseStack p, MultiBufferSource buffers, int light, int color, LoudmanItem.Tuning tuning) {
        VertexConsumer v = buffers.getBuffer(Mesh.solid());
        boolean on = tuning.on();
        // The body, its corners cut, and the dark band its halves meet at.
        box(p, v, -W + .012f, -H, -D, W - .012f, H, D, color, light);
        box(p, v, -W, -H + .012f, -D, W, H - .012f, D, color, light);
        box(p, v, -W - .001f, .008f, -D - .001f, W + .001f, .014f, D + .001f, shade(color, .55f), light);
        // Glass over the upper half, and the screen in it.
        box(p, v, -W + .01f, .02f, D, W - .01f, H - .01f, D + .003f, 0x0B0B0E, light);
        box(p, v, -.082f, .04f, D + .003f, .082f, .215f, D + .004f, on ? 0x16222E : 0x030304, on ? Mesh.FULL_BRIGHT : light);
        // The five-way pad: a ring to tilt, and its centre key.
        Mesh.ring(p, v, 0, PAD_Y, D + .001f, .078f, .074f, 40, 1, shade(color, .6f), light);
        Mesh.ring(p, v, 0, PAD_Y, D + .001f, .074f, .03f, 40, 1, angle -> shade(color, .92f + .1f * (float) Math.cos(2 * angle)), light);
        Mesh.ring(p, v, 0, PAD_Y, D + .002f, .03f, 0, 28, 1, shade(color, .82f), light);
        // The hold switch and volume keys on its side, and the earphone jack below.
        box(p, v, W, .07f, -.008f, W + .005f, .17f, .008f, shade(color, .6f), light);
        box(p, v, -W - .005f, .1f, -.006f, -W, .14f, .006f, shade(color, .6f), light);
        box(p, v, -.085f, -H - .004f, -.01f, -.055f, -H, .01f, 0x101012, light);

        // Texts go last, after the mesh's vertices have all been submitted.
        Font font = Minecraft.getInstance().font;
        text(p, buffers, font, "SUNNY", 0, .268f, .0016f, 0xFFDADADA, light);
        text(p, buffers, font, "LOUDMAN", 0, -H + .03f, .0011f, 0xFF000000 | shade(color, color == 0x1B1C20 ? 3.2f : .5f), light);
        if (on) {
            text(p, buffers, font, "FM", 0, .2f, .0014f, 0xFF7FD6FF, Mesh.FULL_BRIGHT);
            text(p, buffers, font, RadioFeature.mhz(tuning.frequency()), 0, .16f, .0036f, 0xFFFFFFFF, Mesh.FULL_BRIGHT);
            text(p, buffers, font, "MHz", 0, .075f, .0014f, 0xFFB8C4D0, Mesh.FULL_BRIGHT);
        }
    }

    /** {@code text} centred on x, its top at y, just proud of the front, {@code scale} blocks to the font's pixel. */
    private static void text(PoseStack p, MultiBufferSource buffers, Font font, String text, float x, float y, float scale,
            int argb, int light) {
        p.pushPose();
        p.translate(x, y, D + .0045f);
        p.scale(scale, -scale, scale);
        font.drawInBatch(text, -font.width(text) / 2f, 0, argb, false, p.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        p.popPose();
    }
}
