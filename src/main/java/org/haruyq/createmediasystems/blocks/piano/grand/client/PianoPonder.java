package org.haruyq.createmediasystems.blocks.piano.grand.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.piano.grand.GrandPianoBlock;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoFeature;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The concert grand's Ponder scene, on a 7x7 plate, since the piano stands on 4 by 6 blocks. */
public final class PianoPonder {
    private PianoPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(PianoFeature.PIANO_ITEM.getId())
                .addStoryBoard(Create.asResource("rose_quartz_lamp"), PianoPonder::piano);
    }

    private static void piano(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "grand_piano", "The Concert Grand Piano", 7);
        BlockState state = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.FULL_SIZE, true);
        BlockPos root = util.grid().at(3, 1, 1);
        AABB footprint = new AABB(root);
        for (GrandPianoBlock.Part part : GrandPianoBlock.parts(state)) {
            BlockPos at = part.at(root, state);
            scene.world().setBlock(at, state.setValue(GrandPianoBlock.PART, part), false);
            footprint = footprint.minmax(new AABB(at));
        }
        for (GrandPianoBlock.Part part : GrandPianoBlock.parts(state)) {
            scene.world().showSection(util.select().position(part.at(root, state)), Direction.DOWN);
        }
        scene.idle(20);
        Vec3 keys = util.vector().topOf(root).add(0, -0.2, 0);
        Vec3 body = util.vector().centerOf(GrandPianoBlock.Part.TOP_C3.at(root, state));
        Vec3 soundboard = GrandPianoBlock.soundboard(root, state);

        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, root, footprint.setMaxY(root.getY() + 1), 100);
        say(scene, 110, keys, "The Concert Grand Piano stands on a floor of 4 by 6 blocks, and its three legs need solid ground beneath them.");

        scene.overlay().showControls(keys, Pointing.DOWN, 50).rightClick();
        say(scene, 130, keys, "Right-click it to play with the mouse, the computer's keyboard, or a real MIDI keyboard plugged into the computer. Ctrl, Shift and Space work its three pedals.");
        notes(scene, soundboard, 80);
        say(scene, 100, soundboard, PonderPalette.GREEN, "Several players can play it at once, and everyone nearby hears what is played.");

        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick().whileSneaking();
        scene.idle(10);
        scene.world().modifyBlock(root, piano -> piano.setValue(GrandPianoBlock.OPEN, true), false);
        say(scene, 110, body, "Sneak-right-click its body to raise or lower the main lid. Raised, it sounds brighter.");

        scene.overlay().showControls(keys, Pointing.DOWN, 40).rightClick().whileSneaking();
        scene.idle(10);
        scene.world().modifyBlock(root, piano -> piano.setValue(GrandPianoBlock.KEYBOARD_OPEN, false), false);
        say(scene, 120, keys, "Sneak-right-click it at the keys to close the key cover. Closed on someone still playing, it catches their fingers.");
    }
}
