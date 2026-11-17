package org.haruyq.createmediasystems.blocks.broadcast.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.broadcast.BroadcastFeature;
import org.haruyq.createmediasystems.blocks.broadcast.MovingBroadcastPackets;
import org.haruyq.createmediasystems.items.power.PowerFeature;

import com.simibubi.create.AllBlocks;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The broadcast tower's Ponder scene: built bottom-up on a motor, linked to a Disc Reader, then each head in turn. It
 * borrows the 5x8x5 steam whistle schematic, since Ponder only places blocks inside the schematic's bounds.
 */
public final class BroadcastPonder {
    private BroadcastPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(BroadcastFeature.BASE_ITEM.getId(), BroadcastFeature.MAST_ITEM.getId(),
                        BroadcastFeature.HORN_ITEM.getId(), BroadcastFeature.DIGITAL_HORN_ITEM.getId(),
                        BroadcastFeature.ARRAY_ITEM.getId())
                .addStoryBoard(Create.asResource("steam_whistle"), BroadcastPonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "broadcast_tower", "Building a Broadcast Tower");
        BlockPos motor = util.grid().at(2, 1, 2);
        BlockPos base = motor.above();
        BlockPos head = base.above(3);
        Vec3 mouth = MovingBroadcastPackets.Frame.mouth(head);
        // The borrowed schematic's hidden whistle, tank and campfire go.
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 4, 7, 4), Blocks.AIR.defaultBlockState(), false);

        scene.world().setBlock(motor, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), false);
        scene.world().setBlock(base, BroadcastFeature.BASE.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(motor, base), Direction.DOWN);
        scene.world().setKineticSpeed(util.select().fromTo(motor, base), 32);
        scene.idle(15);
        say(scene, 110, util.vector().centerOf(base), "A broadcast tower stands on a Broadcast Power Base. A shaft turning at 16 RPM or faster into its bottom powers it.");

        for (int y = 1; y <= 2; y++) {
            BlockPos mast = base.above(y);
            scene.world().setBlock(mast, BroadcastFeature.MAST.get().defaultBlockState(), false);
            scene.world().showSection(util.select().position(mast), Direction.DOWN);
            scene.idle(8);
        }
        say(scene, 100, util.vector().centerOf(base.above(2)), "Broadcast Masts stack on top of the base. Any number of them can stand between the base and the head.");

        scene.world().setBlock(head, BroadcastFeature.HORN.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(head), Direction.DOWN);
        scene.idle(15);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, head, new AABB(head), 110);
        say(scene, 110, mouth, "A broadcast head goes on top, here an Analog Broadcast Horn. It finds its base by looking down through the masts below it.");

        BlockPos reader = util.grid().at(4, 1, 1);
        scene.world().setBlock(reader, AudioFeature.DISC_READER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 30).rightClick()
                .withItem(new ItemStack(AudioFeature.CUSTOM_DISC.get()));
        scene.idle(40);
        ItemStack quill = new ItemStack(AudioFeature.RANGE_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, reader, new AABB(reader), 60);
        scene.idle(35);
        scene.overlay().showControls(mouth, Pointing.DOWN, 30).rightClick().withItem(quill);
        say(scene, 120, mouth, "With the Jukebox Range Quill, pick a source such as a Disc Reader, then right-click the head to link it and set its volume.");

        notes(scene, mouth, 120);
        say(scene, 120, mouth, PonderPalette.GREEN, "While the base has power, the horns play the source in all four directions. Players up to 400 blocks away hear it, even beyond the simulation distance.");

        scene.world().setKineticSpeed(util.select().fromTo(motor, base), 0);
        scene.overlay().showControls(util.vector().blockSurface(base, Direction.NORTH), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(PowerFeature.BATTERY.get()));
        notes(scene, mouth, 130);
        say(scene, 130, util.vector().centerOf(base), "Without a turning shaft, the base runs on its energy reserve, which Forge Energy or a charged battery used on it fills. On a moving contraption, only the reserve powers it.");

        swapHead(scene, head, BroadcastFeature.DIGITAL_HORN.get().defaultBlockState());
        notes(scene, mouth, 130);
        say(scene, 130, mouth, "The Digital Broadcast Horn reaches 500 blocks with a fuller sound. The analog horn cuts off the lows and highs and distorts a little.");

        swapHead(scene, head, BroadcastFeature.ARRAY.get().defaultBlockState());
        notes(scene, mouth, 140);
        say(scene, 140, mouth, "The Digital Broadcast Column Array reaches 800 blocks with the clearest highs. Its beam is flatter, so it is quieter for listeners far above or below it, and it uses the most energy.");
    }

    /** Air first, so the level drops the old head's block entity, whose cached state picks the mesh. */
    private static void swapHead(CreateSceneBuilder scene, BlockPos head, BlockState next) {
        scene.world().setBlock(head, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(head, next, false);
    }
}
