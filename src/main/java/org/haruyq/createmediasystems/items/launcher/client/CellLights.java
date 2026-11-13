package org.haruyq.createmediasystems.items.launcher.client;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.ContraptionSurface;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.PointLights;
import org.haruyq.createmediasystems.client.PointLights.Light;
import org.haruyq.createmediasystems.items.launcher.CellLighting;
import org.haruyq.createmediasystems.items.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.items.launcher.DiscCellItem;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;
import org.haruyq.createmediasystems.items.launcher.LauncherMechanics;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CellLights implements PointLights.Source {
    @Override
    public void collect(PointLights.View view, Consumer<Light> lights) {
        float partial = view.partial();
        for (var entity : view.entities()) {
            if (entity instanceof Player player) {
                ItemStack held = player.getMainHandItem();
                if (held.is(LauncherFeature.DISC_LAUNCHER.get())) launcher(view, player, held, lights);
                else cell(lights, held, SableCompat.eye(player, partial).add(player.getViewVector(partial).scale(.4)).add(0, -.5, 0), .8f);
                cell(lights, player.getOffhandItem(), SableCompat.eye(player, partial).add(0, -.6, 0), .7f);
            } else if (entity instanceof ItemEntity item) {
                cell(lights, item.getItem(), entity.getPosition(partial).add(0, .15, 0), .7f);
            }
        }
        Vec3 camera = view.camera().getPosition();
        var bounds = new AABB(camera, camera).inflate(PointLights.REACH);
        chargers(view, lights, bounds, camera);
        for (var ship : SableCompat.subLevels(view.level(), bounds)) {
            var pose = SableCompat.renderPose(ship);
            if (pose != null) chargers(view, lights, SableCompat.transform(bounds, pose::transformPositionInverse), camera);
        }
        for (var carrier : ContraptionSurface.carriers(view.level(), bounds)) {
            var contraption = carrier.getContraption();
            if (contraption == null) continue;
            for (var actor : contraption.getActors()) {
                var block = actor.left;
                if (!block.state().is(LauncherFeature.DISC_CELL_CHARGER.get())
                        || !(contraption.getBlockEntityClientSide(block.pos()) instanceof DiscCellCharger charger)) continue;
                Vec3 point = carrier.toGlobalVector(block.pos().getCenter().add(0, .06, 0), partial);
                charger(lights, charger, SableCompat.toRenderWorld(view.level(), carrier.blockPosition(), point), camera);
            }
        }
    }

    private static void chargers(PointLights.View view, Consumer<Light> lights, AABB bounds, Vec3 camera) {
        for (int x = SectionPos.blockToSectionCoord(bounds.minX); x <= SectionPos.blockToSectionCoord(bounds.maxX); x++) {
            for (int z = SectionPos.blockToSectionCoord(bounds.minZ); z <= SectionPos.blockToSectionCoord(bounds.maxZ); z++) {
                if (!(view.level().getChunkSource().getChunk(x, z, false) instanceof LevelChunk chunk)) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof DiscCellCharger charger) || charger.isRemoved()) continue;
                    var pos = charger.getBlockPos();
                    charger(lights, charger, SableCompat.toRenderWorld(view.level(), pos, pos.getCenter().add(0, .06, 0)), camera);
                }
            }
        }
    }

    private static void charger(Consumer<Light> lights, DiscCellCharger charger, Vec3 position, Vec3 camera) {
        if (position.distanceToSqr(camera) < PointLights.REACH * PointLights.REACH) {
            cell(lights, charger.cell(), position, 1.1f);
        }
    }

    private static void cell(Consumer<Light> lights, ItemStack cell, Vec3 position, float exposure) {
        if (cell.getItem() instanceof DiscCellItem item) {
            lights.accept(new Light(position, item.tier.color, CellLighting.strength(ChargeableItem.charge(cell)) * exposure));
        }
    }

    private static void launcher(PointLights.View view, Player player, ItemStack gun, Consumer<Light> lights) {
        var mc = Minecraft.getInstance();
        float partial = view.partial();
        var visual = LauncherClient.cellVisual(gun, view.level(), partial);
        if (visual.strength() <= 0) return;
        var motion = visual.motion();
        var path = visual.pose();
        Vec3 position;
        if (player == mc.player && mc.options.getCameraType().isFirstPerson()) {
            PoseStack local = new PoseStack();
            LauncherClient.gunSpace(local, motion, player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1, LauncherClient.bob(player, partial));
            Vector3f point = local.last().pose().transformPosition(new Vector3f(path.x() / 16, path.y() / 16, path.z() / 16));
            point.rotate(view.camera().rotation());
            position = view.camera().getPosition().add(point.x, point.y, point.z);
        } else {
            position = SableCompat.eye(player, partial).add(player.getViewVector(partial).scale(.4))
                    .add(0, -.35 + (path.y() - LauncherMechanics.DOCK_Y) / 24, 0);
        }
        lights.accept(new Light(position, visual.color(), visual.strength() * (.55f + motion.latch() * 1.15f)));
    }
}
