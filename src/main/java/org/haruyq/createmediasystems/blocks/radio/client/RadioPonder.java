package org.haruyq.createmediasystems.blocks.radio.client;

import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanFeature;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;
import org.haruyq.createmediasystems.lighting.LightingFeature;

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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The radio station's Ponder scene: a transmitter, a tower grown mast by mast, the wrench's two links, and a listener
 * with a Loudman. It borrows the 5x8x5 steam whistle schematic, since Ponder only places blocks inside the
 * schematic's bounds.
 */
public final class RadioPonder {
    private RadioPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(RadioFeature.TRANSMITTER_ITEM.getId(), RadioFeature.MAST_ITEM.getId(),
                        RadioFeature.ANTENNA_ITEM.getId(), LoudmanFeature.BLACK.getId(), LoudmanFeature.BLUE.getId(),
                        LoudmanFeature.PINK.getId(), LightingFeature.LIGHTING_LINK.getId())
                .addStoryBoard(Create.asResource("steam_whistle"), RadioPonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "radio_station", "Running a Radio Station");
        BlockPos transmitter = util.grid().at(1, 1, 2);
        BlockPos reader = util.grid().at(1, 1, 3);
        BlockPos foot = util.grid().at(3, 1, 2);
        BlockPos antenna = foot.above(3);
        ItemStack wrench = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        // The borrowed schematic's hidden whistle, tank and campfire go.
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 4, 7, 4), Blocks.AIR.defaultBlockState(), false);

        scene.world().setBlock(transmitter, RadioFeature.TRANSMITTER.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), false);
        scene.world().showSection(util.select().position(transmitter), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(transmitter), Pointing.DOWN, 50).rightClick();
        say(scene, 120, util.vector().topOf(transmitter), "A Radio Transmitter sends a sound source out on an FM frequency. Right-click it with an empty hand to set the frequency, from 76.0 to 108.0 MHz.");

        for (int y = 0; y < 3; y++) {
            scene.world().setBlock(foot.above(y), RadioFeature.MAST.get().defaultBlockState(), false);
            scene.world().showSection(util.select().position(foot.above(y)), Direction.DOWN);
            scene.idle(8);
        }
        scene.overlay().showControls(util.vector().centerOf(foot), Pointing.RIGHT, 50).rightClick()
                .withItem(new ItemStack(RadioFeature.MAST_ITEM.get()));
        say(scene, 120, util.vector().centerOf(foot.above()), "Radio Masts stack into a tower. Right-click any mast with another one to add it to the top, like extending a shaft.");

        scene.world().setBlock(antenna, RadioFeature.ANTENNA.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(antenna), Direction.DOWN);
        scene.idle(15);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, antenna, new AABB(antenna), 110);
        say(scene, 110, util.vector().centerOf(antenna), "A Radio Antenna goes on top. A mast used on a finished tower still makes it taller: the antenna moves up one block.");

        scene.overlay().showControls(util.vector().topOf(transmitter), Pointing.DOWN, 30).rightClick().withItem(wrench);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, transmitter, new AABB(transmitter), 70);
        scene.idle(40);
        scene.overlay().showControls(util.vector().centerOf(foot.above()), Pointing.RIGHT, 30).rightClick().withItem(wrench);
        say(scene, 120, util.vector().centerOf(foot.above()), "Hold a Tuning Wrench and right-click the transmitter to pick it. Then right-click any block of the tower to connect it to the antenna.");

        scene.world().setBlock(reader, AudioFeature.DISC_READER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 40).rightClick().withItem(wrench);
        say(scene, 130, util.vector().topOf(reader), "With the transmitter still picked, right-click what it should play: a Disc Reader, a jukebox, a Blu-ray Player, a hi-fi stack or a Line Mixer, within 128 blocks of it.");

        notes(scene, util.vector().topOf(antenna), 130);
        say(scene, 130, util.vector().centerOf(antenna), PonderPalette.GREEN, "The station reaches 64 blocks from the antenna, plus 32 more for each mast under it, unless the server changes it. It is received even beyond the simulation distance.");

        ItemStack loudman = new ItemStack(LoudmanFeature.BLUE.get());
        loudman.set(LoudmanFeature.TUNING.get(), new LoudmanItem.Tuning(RadioFeature.DEFAULT_FREQUENCY, LoudmanItem.MAX_VOLUME / 2, true));
        holder(scene, util.vector().of(2.5, 1, 0.5), 180, loudman);
        scene.idle(15);
        Vec3 hand = util.vector().of(2.2, 2.3, 0.3);
        scene.overlay().showControls(hand, Pointing.DOWN, 50).rightClick();
        say(scene, 130, hand, "A Sunny Loudman receives it. Right-click with it to open its controls, turn it on, and tune it to the station's frequency. Left and right on its pad tune, up and down set the volume.");

        notes(scene, util.vector().of(2.5, 2.9, 0.5), 120);
        say(scene, 120, hand, "While it is on, anywhere in the inventory, only the player carrying it hears the station. Where two stations share a frequency, it receives the stronger one. Towards the edge of a station's reach, and where it receives none, it plays static.");

        say(scene, 100, util.vector().topOf(transmitter), "Through Engineer's Goggles the transmitter shows its frequency, how tall its tower is, and how far it reaches.");
    }
}
