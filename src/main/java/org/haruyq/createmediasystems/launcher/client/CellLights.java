package org.haruyq.createmediasystems.launcher.client;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.client.PointLights;
import org.haruyq.createmediasystems.client.PointLights.Light;
import org.haruyq.createmediasystems.launcher.CellLighting;
import org.haruyq.createmediasystems.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.launcher.LauncherMechanics;
import org.haruyq.createmediasystems.power.ChargeableItem;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** The light energy cells give: in a held launcher, in a hand, on the ground and in a charger. */
final class CellLights implements PointLights.Source {
    @Override
    public void collect(PointLights.View view, Consumer<Light> lights) {
        float partial = view.partial();
        for (var entity : view.entities()) {
            if (entity instanceof Player player) {
                ItemStack held = player.getMainHandItem();
                if (held.is(LauncherFeature.DISC_LAUNCHER.get())) launcher(view, player, held, lights);
                else cell(lights, held, player.getEyePosition(partial).add(player.getViewVector(partial).scale(.4)).add(0, -.5, 0), .8f);
                cell(lights, player.getOffhandItem(), player.getEyePosition(partial).add(0, -.6, 0), .7f);
            } else if (entity instanceof ItemEntity item) {
                cell(lights, item.getItem(), entity.getPosition(partial).add(0, .15, 0), .7f);
            }
        }
        Vec3 camera = view.camera().getPosition();
        int reach = (int) Math.ceil(PointLights.REACH);
        for (int x = SectionPos.blockToSectionCoord(camera.x - reach); x <= SectionPos.blockToSectionCoord(camera.x + reach); x++) {
            for (int z = SectionPos.blockToSectionCoord(camera.z - reach); z <= SectionPos.blockToSectionCoord(camera.z + reach); z++) {
                if (!(view.level().getChunkSource().getChunk(x, z, false) instanceof LevelChunk chunk)) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof DiscCellCharger charger) || charger.isRemoved()) continue;
                    Vec3 position = Vec3.atCenterOf(charger.getBlockPos()).add(0, .06, 0);
                    if (position.distanceToSqr(camera) < PointLights.REACH * PointLights.REACH) cell(lights, charger.cell(), position, 1.1f);
                }
            }
        }
    }

    private static void cell(Consumer<Light> lights, ItemStack cell, Vec3 position, float exposure) {
        if (cell.getItem() instanceof DiscCellItem item) {
            lights.accept(new Light(position, item.tier.color, CellLighting.strength(ChargeableItem.charge(cell)) * exposure));
        }
    }

    /** The cell in a launcher, where the gun model draws it: through the first-person hand pose, or near the eye. */
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
            position = player.getEyePosition(partial).add(player.getViewVector(partial).scale(.4))
                    .add(0, -.35 + (path.y() - LauncherMechanics.DOCK_Y) / 24, 0);
        }
        lights.accept(new Light(position, visual.color(), visual.strength() * (.55f + motion.latch() * 1.15f)));
    }
}
