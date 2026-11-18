package org.haruyq.createmediasystems.screen;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.haruyq.createmediasystems.playback.ScreenPlayer;
import org.haruyq.createmediasystems.playback.VideoFrame;
import org.haruyq.createmediasystems.stream.DiscUrl;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;

import dev.onvoid.webrtc.media.video.VideoTrackSink;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The share on a server's monitor, drawn by the server (see ScreenMonitorBlock): its host sees what it captures, the
 * others the share as it comes over P2P, joining its host's session for it. Picture only; its sound plays from a
 * Blu-ray Player beside the server. Game thread.
 */
final class ScreenMonitor implements BlockEntityRenderer<ScreenServerBlock.Entity> {
    /** The screen on the model, facing north: x from its right edge to its left as seen from the front, then y, in blocks. */
    private static final float X0 = 0.5f / 16, X1 = 15.5f / 16, Y0 = 5f / 16, Y1 = 13f / 16;
    /** Just off the panel's front, at z 7. */
    private static final float Z = 7f / 16 - 0.002f;
    /** A monitor out of sight this long lets its share go. */
    private static final long LET_GO_NANOS = 3_000_000_000L;
    /** A share that couldn't be opened, or was lost, is tried again after this long. */
    private static final long RETRY_NANOS = 5_000_000_000L;
    /** The share each monitor in sight shows, by its server. */
    private static final Map<BlockPos, Watch> WATCHING = new HashMap<>();

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(ScreenFeature.SERVER_ENTITY.get(), context -> new ScreenMonitor()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            long now = System.nanoTime();
            WATCHING.values().removeIf(watch -> now - watch.seen > LET_GO_NANOS && watch.close());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            WATCHING.values().forEach(Watch::close);
            WATCHING.clear();
        });
    }

    @Override
    public void render(ScreenServerBlock.Entity server, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
                       int overlay) {
        BlockState monitor = server.getLevel().getBlockState(server.getBlockPos().above());
        if (!server.live() || !(monitor.getBlock() instanceof ScreenMonitorBlock)) {
            return;
        }
        long now = System.nanoTime();
        BlockPos at = server.getBlockPos();
        Watch watch = WATCHING.get(at);
        if (watch == null || !watch.link.equals(server.link()) || watch.lost(now)) {
            if (watch != null) {
                watch.close();
            }
            watch = new Watch(server.link(), now);
            WATCHING.put(at.immutable(), watch);
        }
        watch.seen = now;
        ResourceLocation texture = watch.frame.texture();
        if (texture == null) {
            // The model's own screen, black, till the first picture.
            return;
        }
        pose.pushPose();
        pose.translate(0.5, 1, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - monitor.getValue(ScreenMonitorBlock.FACING).toYRot()));
        pose.translate(-0.5, 0, -0.5);
        // As large as fits uncropped, in the middle.
        float scale = Math.min((X1 - X0) / watch.frame.width(), (Y1 - Y0) / watch.frame.height());
        float w = watch.frame.width() * scale, h = watch.frame.height() * scale;
        float left = (X0 + X1 + w) / 2, right = left - w, bottom = (Y0 + Y1 - h) / 2, top = bottom + h;
        // ponytail: drawn plain, unlike a display's picture (DisplayRenderer): under a shader pack it is graded as the
        // world is, and Iris may draw it behind translucent things; give it DisplayRenderer's passes if that shows.
        VertexConsumer out = buffers.getBuffer(RenderType.textPolygonOffset(texture));
        Matrix4f matrix = pose.last().pose();
        vertex(out, matrix, left, bottom, 0, 1);
        vertex(out, matrix, right, bottom, 1, 1);
        vertex(out, matrix, right, top, 1, 0);
        vertex(out, matrix, left, top, 0, 0);
        pose.popPose();
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, float x, float y, float u, float v) {
        out.addVertex(matrix, x, y, Z).setColor(255, 255, 255, 255).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
    }

    @Override
    public AABB getRenderBoundingBox(ScreenServerBlock.Entity server) {
        return new AABB(server.getBlockPos()).expandTowards(0, 1, 0);
    }

    /** One monitor's share, opened off the game thread. */
    private static final class Watch {
        final String link;
        final VideoFrame frame = VideoFrame.standalone();
        private final VideoTrackSink pictures = frame::put;
        private final long opened;
        long seen;
        @Nullable
        private ScreenPlayer.Feed feed;
        private boolean failed;
        private boolean closed;

        Watch(String link, long now) {
            this.link = link;
            opened = now;
            DiscUrl url = DiscUrl.parse(link);
            Thread.ofPlatform().daemon().name("CMS screen monitor").start(() -> {
                try {
                    if (url == null) {
                        throw new IOException("Not a screen share: " + link);
                    }
                    ScreenPlayer.Feed opening = ScreenClient.open(url);
                    synchronized (this) {
                        if (!closed) {
                            feed = opening;
                            opening.watch(pictures, null);
                        }
                    }
                } catch (IOException e) {
                    LogUtils.getLogger().debug("A screen share monitor could not show {}: {}", link, e.getMessage());
                    synchronized (this) {
                        failed = true;
                    }
                }
            });
        }

        /** It couldn't be opened, or this client lost it, a while ago: time to try again. */
        synchronized boolean lost(long now) {
            return now - opened > RETRY_NANOS && (failed || feed != null && feed.ended());
        }

        /** Always true, for removeIf. */
        synchronized boolean close() {
            closed = true;
            if (feed != null) {
                feed.unwatch(pictures, null);
            }
            frame.close();
            return true;
        }
    }
}
