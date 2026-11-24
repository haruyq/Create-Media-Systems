package org.haruyq.createmediasystems.blocks.stagecustom.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomFeature;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The Ponder scene of both Stage Custom Birch drum kits: hitting the drums and cymbals, and the play screen. */
public final class StageCustomPonder {
    // Pads as the kit's block events number them: the five drums, then hi-hat, crash and ride.
    private static final int KICK = 0, TOM_10 = 1, TOM_12 = 2, FLOOR = 3, SNARE = 4, HI_HAT = 5, CRASH = 6;

    private StageCustomPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(StageCustomFeature.ITEM.getId(), StageCustomFeature.COMPLETE_ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), StageCustomPonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "stage_custom", "The Drum Kits");
        BlockPos root = util.grid().at(3, 1, 2);
        scene.world().setBlock(root, kit(StageCustomFeature.BLOCK.get()), false);
        scene.world().showSection(util.select().position(root), Direction.DOWN);
        scene.idle(20);
        Vec3 kit = util.vector().of(3.0, 2.0, 2.2);

        say(scene, 110, kit, "The Yamaha Stage Custom Birch SBP2F5-DUS is a set of five drums: a kick, two toms, a floor tom and a snare.");
        scene.overlay().showControls(kit, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        notes(scene, kit, 40);
        for (int pad : new int[] {SNARE, TOM_10, TOM_12, FLOOR, KICK}) {
            hit(scene, root, pad);
            scene.idle(6);
        }
        say(scene, 90, kit, "Right-click a drum with an empty hand to hit it.");

        // Through air, so the kit gets a new block entity and its renderer draws the cymbals.
        scene.world().setBlock(root, Blocks.AIR.defaultBlockState(), false);
        scene.idle(1);
        scene.world().setBlock(root, kit(StageCustomFeature.COMPLETE_BLOCK.get()), false);
        scene.idle(10);
        notes(scene, kit, 40);
        for (int pad : new int[] {KICK, HI_HAT, SNARE, HI_HAT, KICK, CRASH}) {
            hit(scene, root, pad);
            scene.idle(6);
        }
        say(scene, 120, kit, "The SBP2F5AZM-DUS adds a hi-hat, a crash and a ride cymbal. Right-click a cymbal or a pedal to play it as well.");
        say(scene, 90, kit, "Right-clicking the hi-hat pedal opens or closes the hi-hat.");

        scene.overlay().showControls(kit, Pointing.DOWN, 50).rightClick().whileSneaking();
        scene.idle(10);
        say(scene, 140, kit, "Sneak and right-click the kit to open its play screen. There, the keys Z, X, C, V and B hit the drums, 1 to 6 the cymbals, and holding Shift hits softer.");
        say(scene, 90, kit, "An electronic drum kit or another MIDI device plugged into the computer plays it too: pick it on the play screen.");
        say(scene, 90, kit, PonderPalette.GREEN, "The kit sounds where it stands, and players nearby hear it. No speakers are needed.");
    }

    private static BlockState kit(Block block) {
        return block.defaultBlockState().setValue(StageCustomBlock.FACING, Direction.NORTH)
                .setValue(StageCustomBlock.LARGE, true);
    }

    /** Plays the strike animation of {@code pad} at the middle of it, as the kit's block event does. */
    private static void hit(CreateSceneBuilder scene, BlockPos root, int pad) {
        int center = pad < 5 ? 7 | 7 << 4 : 3 | 3 << 3;
        scene.world().modifyBlockEntity(root, StageCustomBlock.Entity.class,
                entity -> entity.triggerEvent(pad | (pad & 1) << 3 | 12 << 4, center));
    }
}
