package org.haruyq.createmediasystems.client;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What every feature's Ponder scene (hold W over the item) is made of. The scenes borrow Create's own schematics for
 * the base plate and place CMS blocks into them, so no structure files are needed. Their English text is in the
 * scenes; translations are in the lang files under {@code create_media_systems.ponder.<scene>.*}.
 */
public final class PonderScenes {
    private PonderScenes() {
    }

    /** A scene on a 5x5 plate, shown, titled {@code title}. */
    public static CreateSceneBuilder plate(SceneBuilder builder, String id, String title) {
        return plate(builder, id, title, 5);
    }

    /** A scene on a {@code size} by {@code size} plate, for a schematic at least that large, shown. */
    public static CreateSceneBuilder plate(SceneBuilder builder, String id, String title, int size) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, size);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    /** A text box at {@code at}, a key frame, shown {@code ticks} and waited out with a beat after. */
    public static void say(CreateSceneBuilder scene, int ticks, Vec3 at, String text) {
        scene.overlay().showText(ticks).attachKeyFrame().pointAt(at).placeNearTarget().text(text);
        scene.idle(ticks + 10);
    }

    public static void say(CreateSceneBuilder scene, int ticks, Vec3 at, PonderPalette color, String text) {
        scene.overlay().showText(ticks).attachKeyFrame().colored(color).pointAt(at).placeNearTarget().text(text);
        scene.idle(ticks + 10);
    }

    /**
     * An armour stand standing in for a player who holds {@code held} in the main hand, that arm raised forward: its
     * feet at {@code at}, facing {@code yaw} (0 south, 90 west, 180 north toward the camera, -90 east).
     */
    public static ElementLink<EntityElement> holder(CreateSceneBuilder scene, Vec3 at, float yaw, ItemStack held) {
        return holder(scene, at, yaw, held, new Rotations(-80, 0, 0), new Rotations(0, 0, 0));
    }

    /** {@link #holder}, its arms posed as {@code rightArm} and {@code leftArm} (pitch, yaw, roll in degrees). */
    public static ElementLink<EntityElement> holder(CreateSceneBuilder scene, Vec3 at, float yaw, ItemStack held,
            Rotations rightArm, Rotations leftArm) {
        return scene.world().createEntity(level -> {
            ArmorStand stand = new ArmorStand(level, at.x, at.y, at.z);
            stand.setShowArms(true);
            stand.setNoBasePlate(true);
            stand.setYRot(yaw);
            stand.yRotO = yaw;
            stand.setYBodyRot(yaw);
            stand.yBodyRotO = yaw;
            stand.setYHeadRot(yaw);
            stand.yHeadRotO = yaw;
            stand.setRightArmPose(rightArm);
            stand.setLeftArmPose(leftArm);
            stand.setItemSlot(EquipmentSlot.MAINHAND, held.copy());
            return stand;
        });
    }

    /** The armour stand of {@link #holder} at {@code link} now holds {@code held} instead. */
    public static void hold(CreateSceneBuilder scene, ElementLink<EntityElement> link, ItemStack held) {
        scene.world().modifyEntity(link, entity -> ((ArmorStand) entity).setItemSlot(EquipmentSlot.MAINHAND, held.copy()));
    }

    /** Music notes rising from {@code at} for {@code cycles} ticks. */
    public static void notes(CreateSceneBuilder scene, Vec3 at, int cycles) {
        scene.effects().emitParticles(at, scene.effects().simpleParticleEmitter(ParticleTypes.NOTE, Vec3.ZERO), 1, cycles);
    }
}
