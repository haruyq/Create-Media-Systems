package org.haruyq.createmediasystems.blocks.steelpan.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.steelpan.SteelpanBlock;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanFeature;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The steelpan's Ponder scene: placing it, striking its fields by hand, and playing it from its screen. */
public final class SteelpanPonder {
    private SteelpanPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(SteelpanFeature.ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), SteelpanPonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "steelpan", "The Steelpan");
        BlockPos pan = util.grid().at(2, 1, 2);
        scene.world().setBlock(pan, SteelpanFeature.BLOCK.get().defaultBlockState()
                .setValue(SteelpanBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(pan), Direction.DOWN);
        scene.idle(20);
        Vec3 bowl = util.vector().topOf(pan).add(0, -0.2, 0);

        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, pan, new AABB(pan), 90);
        say(scene, 100, bowl, "The Low C Lead Steelpan stands on its own stand and takes up a single block. It is placed with its playing side turned toward you.");

        scene.overlay().showControls(bowl, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        melody(scene, pan, bowl);
        say(scene, 120, bowl, "Right-click a tone field with an empty hand to strike it with the mallets. Each of its 29 fields plays its own note, from C4 up to E6.");

        scene.overlay().showControls(bowl, Pointing.DOWN, 40).rightClick().whileSneaking();
        scene.idle(10);
        say(scene, 130, bowl, "Sneak-right-click it to open its screen. There, click a field with the mouse or press the key shown on it. A real MIDI keyboard plugged into the computer plays it too.");
        say(scene, 100, bowl, "On the screen, a click lower down in a field strikes the note harder, and a key pressed while holding Shift strikes it softer.");

        melody(scene, pan, bowl);
        say(scene, 110, bowl, PonderPalette.GREEN, "Anyone within 8 blocks can play it, and everyone within 32 blocks hears it, more quietly the farther away they are.");
        say(scene, 100, bowl, "It can also be built into a moving contraption, where it is played in the same ways.");
    }

    /** A rising C major arpeggio: the mallets strike and music notes rise from the bowl. */
    private static void melody(CreateSceneBuilder scene, BlockPos pan, Vec3 bowl) {
        notes(scene, bowl, 40);
        for (int midi : new int[] {60, 64, 67, 72}) {
            scene.world().modifyBlockEntity(pan, SteelpanBlock.Entity.class,
                    entity -> SteelpanClient.state(entity).strike(midi, 0.8f, System.nanoTime()));
            scene.idle(8);
        }
    }
}
