package org.haruyq.createmediasystems.items.instrument.client;

import static org.haruyq.createmediasystems.client.PonderScenes.hold;
import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.items.instrument.GuitarPedalBlock;
import org.haruyq.createmediasystems.items.instrument.InstrumentFeature;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The Ponder scenes of the two-handed instruments: the Stratocaster with its pedals, and the flute and trumpet. */
public final class InstrumentPonder {
    private InstrumentPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        List<ResourceLocation> guitar = new ArrayList<>();
        for (var item : List.of(InstrumentFeature.CORAL_RED, InstrumentFeature.POLAR_WHITE, InstrumentFeature.SUNBURST,
                InstrumentFeature.BLACK)) guitar.add(item.getId());
        InstrumentFeature.PEDALS.forEach(pedal -> guitar.add(pedal.getId()));
        helper.forComponents(guitar).addStoryBoard(Create.asResource("redstone_link"), InstrumentPonder::guitar);
        helper.forComponents(InstrumentFeature.FLUTE.getId(), InstrumentFeature.TRUMPET.getId())
                .addStoryBoard(Create.asResource("redstone_link"), InstrumentPonder::wind);
    }

    private static void guitar(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "stratocaster", "The Electric Guitar and Its Pedals");
        ItemStack guitar = new ItemStack(InstrumentFeature.CORAL_RED.get());
        BlockPos wah = util.grid().at(1, 1, 2);
        BlockPos chorus = util.grid().at(2, 1, 2);
        BlockPos metalZone = util.grid().at(3, 1, 2);
        pedal(scene, wah, InstrumentFeature.WAH.get());
        pedal(scene, chorus, InstrumentFeature.CHORUS.get());
        pedal(scene, metalZone, InstrumentFeature.METAL_ZONE.get());

        ElementLink<EntityElement> player = holder(scene, util.vector().of(2.5, 1, 3.6), 180, guitar,
                new Rotations(-30, 0, 0), new Rotations(-20, 0, 0));
        scene.idle(15);
        Vec3 body = util.vector().of(2.5, 2.2, 3.4);
        scene.overlay().showControls(body, Pointing.DOWN, 60).rightClick().withItem(guitar);
        say(scene, 110, body, "Hold the Fender Player II Stratocaster with the other hand empty and right-click to play it.");
        notes(scene, body, 60);
        say(scene, 130, body, "In its screen, the lower row of keys from Z and the upper row from Q play notes, and the left and right arrow keys change the octave. A MIDI keyboard plugged into the computer plays it too.");
        say(scene, 110, body, "The screen also sets the pickup, volume, tone and amp drive, and can take a real guitar from an audio input.");

        scene.world().showSection(util.select().fromTo(wah, metalZone), Direction.DOWN);
        scene.idle(15);
        Vec3 pedals = util.vector().topOf(chorus);
        hold(scene, player, ItemStack.EMPTY);
        scene.overlay().showControls(pedals, Pointing.DOWN, 60).rightClick().withItem(guitar);
        for (BlockPos pedal : List.of(wah, chorus, metalZone)) {
            scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, pedal, new AABB(pedal), 120);
            scene.idle(5);
        }
        say(scene, 130, pedals, PonderPalette.GREEN, "Right-click a pedal with the guitar to link it, up to 4 pedals. Right-clicking a linked pedal again unlinks it.");
        hold(scene, player, guitar);
        say(scene, 110, pedals, "Linked pedals change the sound only while the player is within 12 blocks of them.");
        scene.overlay().showControls(body, Pointing.DOWN, 50).rightClick().whileSneaking().withItem(guitar);
        say(scene, 90, body, "Sneak and right-click with the guitar in the air to clear all of its links.");

        Vec3 wahTop = util.vector().topOf(wah);
        scene.overlay().showControls(wahTop, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        scene.world().modifyBlock(wah, state -> state.setValue(BlockStateProperties.LIT, false), false);
        say(scene, 100, wahTop, "Right-click a pedal with an empty hand to bypass it, and again to turn it back on.");
        scene.world().modifyBlock(wah, state -> state.setValue(BlockStateProperties.LIT, true), false);
        scene.overlay().showControls(util.vector().topOf(chorus), Pointing.DOWN, 40).rightClick().whileSneaking();
        say(scene, 100, util.vector().topOf(chorus), "Sneak and right-click an auto pedal to step its depth from 1 to 3.");

        Vec3 knobs = util.vector().topOf(metalZone).add(0, -0.6, 0);
        scene.overlay().showControls(knobs, Pointing.DOWN, 60).rightClick();
        for (int position = 55; position <= 95; position += 5) {
            int at = position;
            scene.world().modifyBlockEntity(metalZone, MetalZoneBlock.Entity.class,
                    entity -> entity.turn(MetalZoneBlock.Knob.DIST, at));
            scene.idle(3);
        }
        say(scene, 130, knobs, "To turn a knob of the BOSS MT-2 Metal Zone, point at it with an empty hand, hold right-click and turn your view. Sneak while grabbing a double knob to turn its outer ring.");
    }

    private static void pedal(CreateSceneBuilder scene, BlockPos at, Block pedal) {
        scene.world().setBlock(at, pedal.defaultBlockState().setValue(GuitarPedalBlock.FACING, Direction.SOUTH), false);
    }

    private static void wind(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "wind_instruments", "The Flute and the Trumpet");
        ItemStack flute = new ItemStack(InstrumentFeature.FLUTE.get());
        ItemStack trumpet = new ItemStack(InstrumentFeature.TRUMPET.get());
        ElementLink<EntityElement> player = holder(scene, util.vector().of(2.5, 1, 2.5), 180, flute,
                new Rotations(-60, 0, 0), new Rotations(-40, 0, 0));
        scene.idle(15);
        Vec3 stage = util.vector().of(2.5, 2.4, 2.3);

        scene.overlay().showControls(stage, Pointing.DOWN, 60).rightClick().withItem(flute);
        say(scene, 120, stage, "Hold the Yamaha YFL-212 Flute or the YTR-2330 Trumpet with the other hand empty and right-click to play it.");
        hold(scene, player, trumpet);
        scene.overlay().showControls(stage, Pointing.DOWN, 60).withItem(trumpet);
        notes(scene, stage, 60);
        say(scene, 130, stage, "In its screen, the lower row of keys from Z and the upper row from Q play notes, and the left and right arrow keys change the octave. A MIDI keyboard plugged into the computer plays it too.");
        say(scene, 120, stage, "Both play at full breath. The Breath slider on the screen, or MIDI CC2 from a breath controller, sets how hard the player blows.");
        hold(scene, player, flute);
        say(scene, 100, stage, PonderPalette.GREEN, "The sound comes from the player playing it, and players nearby hear it too. No speakers are needed.");
    }
}
