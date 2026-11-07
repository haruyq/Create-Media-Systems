package org.haruyq.createmediasystems.lighting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.SableCompat;
import org.joml.Matrix4f;

import dev.ryanhcode.sable.companion.SableCompanion;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class LightingIllumination {
    private static final Map<StageLightBlock.Entity, Beam> BEAMS = new WeakHashMap<>();
    private static WeakReference<Level> knownLevel = new WeakReference<>(null);
    private static final RenderType SURFACE = RenderType.create("create_media_systems_spot_surface",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, true))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false));

    private LightingIllumination() {
    }

    private static final class Beam {
        Vec3 origin;
        Vec3 direction;
        double tangent;
        float range;
        float strength;
        long at;
        long scanned = Long.MIN_VALUE;
        BlockPos center;
        List<Face> faces = List.of();
    }

    private record Face(BlockPos pos, BlockState state, Vec3[] points, float[] uv, Vec3 normal, int tint) {
    }

    static float exposure(Vec3 offset, Vec3 direction, double tangent, double range, double margin) {
        double along = offset.dot(direction);
        if (along <= 0 || along >= range) {
            return 0;
        }
        double radius = Math.max(0.08, along * tangent);
        double radial = Math.max(0, Math.sqrt(Math.max(0, offset.lengthSqr() - along * along)) - margin) / radius;
        float edge = Mth.clamp((float) ((1 - radial) / 0.35), 0, 1);
        float distance = Mth.clamp((float) ((range - along) / Math.max(1, range * 0.2)), 0, 1);
        return edge * edge * (3 - 2 * edge) * distance;
    }

    static int packedLight(int original, float intensity) {
        int block = Math.round(15 * (float) Math.sqrt(Mth.clamp(intensity * 4, 0, 1)));
        return LightTexture.pack(Math.max(LightTexture.block(original), block), LightTexture.sky(original));
    }

    public static int light(Entity entity, float partialTick, int original) {
        if (knownLevel.get() != entity.level() || LightTexture.block(original) == 15 || LightingRenderer.shadowPass()) {
            return original;
        }
        Vec3 target = SableCompanion.INSTANCE.getEyePositionInterpolated(entity, partialTick)
                .add(0, entity.getBbHeight() * 0.5 - entity.getEyeHeight(), 0);
        float intensity = 0;
        long now = entity.level().getGameTime();
        for (var entry : BEAMS.entrySet()) {
            StageLightBlock.Entity lamp = entry.getKey();
            Beam beam = entry.getValue();
            if (lamp.isRemoved() || now < beam.at || now - beam.at > 2) {
                continue;
            }
            var console = lamp.controller();
            if (console == null || !console.formed || !console.settings.enabled || console.settings.master <= 0
                    || console.settings.groups[lamp.group].brightness <= 0) {
                continue;
            }
            Vec3 local = SableCompat.toRenderBlockSpace(entity.level(), lamp.getBlockPos(), target)
                    .subtract(Vec3.atLowerCornerOf(lamp.getBlockPos()));
            float value = exposure(local.subtract(beam.origin), beam.direction, beam.tangent, beam.range,
                    entity.getBbWidth() * 0.35) * beam.strength;
            Vec3 origin = SableCompat.toRenderWorld(entity.level(), lamp.getBlockPos(),
                    beam.origin.add(Vec3.atLowerCornerOf(lamp.getBlockPos())));
            if (value > 0 && visible(new LightingRaycast(entity.level(), lamp.getBlockPos(), true), origin, target)) {
                intensity += value;
            }
        }
        return packedLight(original, intensity);
    }

    public static void render(StageLightBlock.Entity lamp, Vec3 origin, Vec3 direction, double halfAngle, float range,
                              float strength, int color, LightingRaycast.Hit hit, LightingRaycast rays,
                              Matrix4f matrix, MultiBufferSource buffers) {
        Level level = lamp.getLevel();
        if (knownLevel.get() != level) {
            BEAMS.clear();
            knownLevel = new WeakReference<>(level);
        }
        Beam beam = BEAMS.computeIfAbsent(lamp, ignored -> new Beam());
        beam.origin = origin;
        beam.direction = direction;
        beam.tangent = Math.tan(halfAngle);
        beam.range = range;
        beam.strength = strength * Math.max(color >> 16 & 255, Math.max(color >> 8 & 255, color & 255)) / 255;
        beam.at = level.getGameTime();
        if (hit.block().getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos center = hit.block().getBlockPos();
        Vec3 offset = Vec3.atLowerCornerOf(lamp.getBlockPos());
        Vec3 impact = SableCompat.toRenderBlockSpace(level, lamp.getBlockPos(), hit.point()).subtract(offset);
        if (!center.equals(beam.center) || beam.at < beam.scanned || beam.at - beam.scanned >= 8) {
            int radius = Mth.ceil(Math.min(8, origin.distanceTo(impact) * beam.tangent)) + 1;
            beam.faces = faces(lamp, center, radius, hit.block().getDirection().getAxis());
            beam.center = center;
            beam.scanned = beam.at;
        }
        Vec3 worldOrigin = SableCompat.toRenderWorld(level, lamp.getBlockPos(), origin.add(offset));
        VertexConsumer out = buffers.getBuffer(SURFACE);
        for (Face face : beam.faces) {
            if (!level.isLoaded(face.pos) || level.getBlockState(face.pos) != face.state) {
                continue;
            }
            Vec3[] points = new Vec3[4];
            Vec3[] world = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                world[i] = SableCompat.toRenderWorld(level, face.pos, face.points[i]);
                points[i] = SableCompat.toRenderBlockSpace(level, lamp.getBlockPos(), world[i]).subtract(offset);
            }
            double winding = Math.signum(face.points[1].subtract(face.points[0])
                    .cross(face.points[3].subtract(face.points[0])).dot(face.normal));
            Vec3 normal = points[1].subtract(points[0]).cross(points[3].subtract(points[0])).normalize().scale(winding);
            Vec3 worldNormal = world[1].subtract(world[0]).cross(world[3].subtract(world[0])).normalize().scale(winding);
            Vec3 middle = points[0].lerp(points[2], 0.5);
            Vec3 incoming = origin.subtract(middle).normalize();
            float facing = (float) Math.max(0, normal.dot(incoming));
            if (facing < 0.01 || exposure(middle.subtract(origin), direction, beam.tangent, range, 0.8) <= 0) {
                continue;
            }
            int r = (color >> 16 & 255) * (face.tint >> 16 & 255) / 255;
            int g = (color >> 8 & 255) * (face.tint >> 8 & 255) / 255;
            int b = (color & 255) * (face.tint & 255) / 255;
            byte[] visibility = new byte[9];
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    Vec3 cell = sample(points, (x + 0.5f) / 2, (y + 0.5f) / 2);
                    if (exposure(cell.subtract(origin), direction, beam.tangent, range, 0.4) <= 0
                            || !visible(rays, worldOrigin, sample(world, (x + 0.5f) / 2, (y + 0.5f) / 2)
                            .add(worldNormal.scale(0.001)))) {
                        continue;
                    }
                    boolean clear = true;
                    for (int corner = 0; corner < 4; corner++) {
                        int cx = x + (corner == 1 || corner == 2 ? 1 : 0);
                        int cy = y + (corner >= 2 ? 1 : 0);
                        int index = cy * 3 + cx;
                        if (visibility[index] == 0) {
                            visibility[index] = (byte) (visible(rays, worldOrigin,
                                    sample(world, cx / 2f, cy / 2f).add(worldNormal.scale(0.001))) ? 1 : -1);
                        }
                        clear &= visibility[index] > 0;
                    }
                    if (!clear) {
                        continue;
                    }
                    for (int corner = 0; corner < 4; corner++) {
                        float u = (x + (corner == 1 || corner == 2 ? 1 : 0)) * 0.5f;
                        float v = (y + (corner >= 2 ? 1 : 0)) * 0.5f;
                        Vec3 point = sample(points, u, v);
                        float alpha = Mth.clamp(exposure(point.subtract(origin), direction, beam.tangent, range, 0)
                                * strength * facing * 3, 0, 1);
                        float s = Mth.lerp(v, Mth.lerp(u, face.uv[0], face.uv[2]), Mth.lerp(u, face.uv[6], face.uv[4]));
                        float t = Mth.lerp(v, Mth.lerp(u, face.uv[1], face.uv[3]), Mth.lerp(u, face.uv[7], face.uv[5]));
                        out.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                                .setUv(s, t).setColor(r, g, b, Math.round(alpha * 255));
                    }
                }
            }
        }
    }

    private static Vec3 sample(Vec3[] points, float u, float v) {
        return points[0].lerp(points[1], u).lerp(points[3].lerp(points[2], u), v);
    }

    private static boolean visible(LightingRaycast rays, Vec3 from, Vec3 to) {
        LightingRaycast.Hit hit = rays.clip(from, to);
        return hit.block().getType() == HitResult.Type.MISS || hit.point().distanceToSqr(to) < 1e-8;
    }

    private static List<Face> faces(StageLightBlock.Entity lamp, BlockPos center, int radius, Direction.Axis axis) {
        int x = axis == Direction.Axis.X ? 2 : radius;
        int y = axis == Direction.Axis.Y ? 2 : radius;
        int z = axis == Direction.Axis.Z ? 2 : radius;
        var mc = Minecraft.getInstance();
        var level = lamp.getLevel();
        var owner = SableCompanion.INSTANCE.getContaining(level, center);
        RandomSource random = RandomSource.create(42);
        List<Face> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-x, -y, -z), center.offset(x, y, z))) {
            if (!level.isLoaded(pos) || pos.equals(lamp.getBlockPos())
                    || SableCompanion.INSTANCE.getContaining(level, pos) != owner) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            var model = mc.getBlockRenderer().getBlockModel(state);
            Vec3 offset = Vec3.atLowerCornerOf(pos).add(state.getOffset(level, pos));
            for (int side = 0; side <= 6; side++) {
                Direction direction = side == 6 ? null : Direction.values()[side];
                if (direction != null && !Block.shouldRenderFace(state, level, pos, direction, pos.relative(direction))) {
                    continue;
                }
                random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, direction, random, ModelData.EMPTY, null)) {
                    int tint = quad.isTinted() ? mc.getBlockColors().getColor(state, level, pos, quad.getTintIndex()) : 0xFFFFFF;
                    int[] data = quad.getVertices();
                    Vec3[] points = new Vec3[4];
                    float[] uv = new float[8];
                    for (int i = 0; i < 4; i++) {
                        int base = i * IQuadTransformer.STRIDE;
                        points[i] = offset.add(Float.intBitsToFloat(data[base + IQuadTransformer.POSITION]),
                                Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 1]),
                                Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 2]));
                        uv[i * 2] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0]);
                        uv[i * 2 + 1] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]);
                    }
                    result.add(new Face(pos.immutable(), state, points, uv, Vec3.atLowerCornerOf(quad.getDirection().getNormal()), tint));
                }
            }
        }
        return result;
    }
}
