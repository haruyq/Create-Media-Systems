package org.haruyq.createmediasystems.blocks.turntable.atlpa2.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableBlock;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableBlock.Speed;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableFeature;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout;

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
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** The turntable's Ponder scene: how its real counterpart is worked, part by part. */
final class TurntablePonder {
    /** The record the scene plays: music disc "cat", 185 s long. */
    private static final double LENGTH = 185;

    private TurntablePonder() {
    }

    static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(TurntableFeature.ITEM.getId()).addStoryBoard(Create.asResource("redstone_link"), TurntablePonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "turntable", "The audio-techtech AT-LPA2 Turntable");
        // Its parts are small: seen from nearer than a scene usually is.
        scene.scaleSceneView(1.4f);
        BlockPos pos = util.grid().at(2, 1, 2);
        ItemStack disc = new ItemStack(Items.MUSIC_DISC_CAT);
        scene.world().setBlock(pos, TurntableFeature.BLOCK.get().defaultBlockState()
                .setValue(TurntableBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        scene.idle(15);

        Vec3 cover = point(pos, 8, TurntableLayout.COVER_TOP, 8);
        say(scene, 120, cover, PonderPalette.GREEN, "A fully manual turntable of clear acrylic. Point at a part and right-click it; the part you point at is named above the hotbar.");
        scene.overlay().showControls(cover, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        scene.world().modifyBlock(pos, state -> state.setValue(TurntableBlock.OPEN, true), false);
        say(scene, 100, cover, "Right-click the dust cover to open or close it. A record plays on with the cover shut.");

        Vec3 platter = point(pos, TurntableLayout.CENTER_X - 3, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z + 2);
        scene.overlay().showControls(platter, Pointing.DOWN, 40).rightClick().withItem(disc);
        scene.idle(15);
        arrange(scene, pos, disc, false, false, Speed.RPM_33, true, true, 0);
        say(scene, 130, platter, "With the arm on its rest, right-click the platter with a music disc or a Custom Disc to put the record on. An empty hand takes it off again. Speakers linked to the turntable with a Jukebox Range Quill play a Custom Disc with it.");

        Vec3 power = point(pos, TurntableLayout.KEYS[0][0], TurntableLayout.UNIT_TOP, TurntableLayout.UNIT_Z1 - .3);
        Vec3 start = point(pos, TurntableLayout.KEYS[3][0], TurntableLayout.UNIT_TOP, TurntableLayout.UNIT_Z1 - .3);
        scene.overlay().showControls(power, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        arrange(scene, pos, disc, true, false, Speed.RPM_33, true, true, 0);
        scene.overlay().showControls(start, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        arrange(scene, pos, disc, true, true, Speed.RPM_33, true, true, 0);
        say(scene, 150, start, "The control unit in front: POWER, then 33 or 45 for the speed, and START/STOP turns the platter. Music discs are LPs, cut at 33; at 45 they play fast and high. A Custom Disc plays at its own speed.");

        Vec3 arm = point(pos, TurntableLayout.PIVOT_X, TurntableLayout.PIVOT_Y + .25, 9);
        scene.overlay().showControls(arm, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        arrange(scene, pos, disc, true, true, Speed.RPM_33, false, false, 0);
        notes(scene, point(pos, TurntableLayout.CENTER_X, 6, TurntableLayout.CENTER_Z), 150);
        say(scene, 150, arm, "Right-click the tonearm to take it off its rest and set the needle down in the record's first groove: the record plays while the platter turns. Right-click it again to put it back on its rest.");

        Vec3 lever = point(pos, 15, TurntableLayout.CUE_Y + .25, 6.8);
        scene.overlay().showControls(lever, Pointing.RIGHT, 30).rightClick();
        scene.idle(10);
        arrange(scene, pos, disc, true, true, Speed.RPM_33, false, true, 4);
        say(scene, 120, lever, "Right-click the lift lever to raise the needle: the record stops, the arm staying where it is. Right-click it again to set the needle down there.");

        Vec3 groove = point(pos, TurntableLayout.CENTER_X, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z + TurntableLayout.radiusAt(.55));
        scene.overlay().showControls(groove, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        arrange(scene, pos, disc, true, true, Speed.RPM_33, false, false, .55 * LENGTH);
        notes(scene, point(pos, TurntableLayout.CENTER_X, 6, TurntableLayout.CENTER_Z), 140);
        say(scene, 140, groove, "To play from elsewhere, right-click that spot of the record: the arm is lifted, moved over the groove there and set down. The outer edge is the start of the record, the inner edge its end.");

        say(scene, 120, groove, PonderPalette.GREEN, "There is no auto-return: at the run-out the music ends and the needle stays in the groove. Raise the lever and put the arm back on its rest.");
    }

    /** A point of the turntable (sixteenths of its space) in the scene. */
    private static Vec3 point(BlockPos pos, double x, double y, double z) {
        return Vec3.atLowerCornerOf(pos).add(HifiLayout.toBlock(Direction.NORTH, x, y, z));
    }

    private static void arrange(CreateSceneBuilder scene, BlockPos pos, ItemStack record, boolean power, boolean motor,
                                Speed speed, boolean onRest, boolean raised, double at) {
        scene.world().modifyBlockEntity(pos, TurntableBlock.Entity.class,
                turntable -> turntable.arrange(record.copy(), power, motor, speed, onRest, raised, at, LENGTH));
    }
}
