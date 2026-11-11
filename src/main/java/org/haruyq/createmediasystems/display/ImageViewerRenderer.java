package org.haruyq.createmediasystems.display;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** An image viewer's picture on its black panel, or (without its frame) alone on the wall, see-through where it is. */
public final class ImageViewerRenderer implements BlockEntityRenderer<ImageViewerBlock.Entity> {
    ImageViewerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ImageViewerBlock.Entity viewer, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        buffers = DisplayRenderer.pictures(viewer, buffers);
        float width = (float) viewer.width();
        float height = (float) viewer.height();
        Matrix4f matrix = pose.last().pose();
        DisplayBlock.Screen screen = new DisplayBlock.Screen(viewer.facing());
        boolean framed = viewer.framed();
        if (framed) {
            DisplayRenderer.panel(buffers.getBuffer(RenderType.textBackground()), matrix, screen, width, height);
        }
        Images.Picture picture = picture(viewer);
        if (picture != null) {
            DisplayRenderer.fit(viewer, buffers.getBuffer(RenderType.textPolygonOffset(picture.texture)), matrix, screen,
                    width, height, picture.width, picture.height, picture.texture, framed ? 0 : -(float) DisplayBlock.DEPTH);
        }
    }

    /** The picture {@code viewer} shows, or null while it has none (or it isn't loaded yet). */
    @Nullable
    static Images.Picture picture(ImageViewerBlock.Entity viewer) {
        return viewer.image().isEmpty() ? null : Images.get(viewer.image());
    }

    @Override
    public boolean shouldRenderOffScreen(ImageViewerBlock.Entity viewer) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return DisplayRenderer.viewDistance();
    }

    @Override
    public boolean shouldRender(ImageViewerBlock.Entity viewer, Vec3 camera) {
        return DisplayRenderer.inSight(viewer, camera);
    }

    @Override
    public AABB getRenderBoundingBox(ImageViewerBlock.Entity viewer) {
        return viewer.box(0, 0.1f).inflate(0.1);
    }
}
