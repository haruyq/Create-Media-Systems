package org.haruyq.createmediasystems.client;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.minecraft.core.particles.ParticleTypes;
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
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 5);
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

    /** Music notes rising from {@code at} for {@code cycles} ticks. */
    public static void notes(CreateSceneBuilder scene, Vec3 at, int cycles) {
        scene.effects().emitParticles(at, scene.effects().simpleParticleEmitter(ParticleTypes.NOTE, Vec3.ZERO), 1, cycles);
    }
}
