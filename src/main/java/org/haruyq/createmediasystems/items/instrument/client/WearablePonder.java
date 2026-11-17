package org.haruyq.createmediasystems.items.instrument.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.hold;
import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.items.instrument.KeytarFinish;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments;
import org.haruyq.createmediasystems.items.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.items.launcher.DiscCellChargerBlock;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;

import com.simibubi.create.AllBlocks;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The Ponder scene of the two hand-held keyboards, the Keytar and the Melodica, with a charger and a crafting table. */
public final class WearablePonder {
    private WearablePonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(WearableInstruments.KEYTAR.getId(), WearableInstruments.MELODICA.getId())
                .addStoryBoard(Create.asResource("redstone_link"), WearablePonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "wearable_instruments", "The Keytar and the Melodica");
        ItemStack keytar = new ItemStack(WearableInstruments.KEYTAR.get());
        ItemStack melodica = new ItemStack(WearableInstruments.MELODICA.get());
        Vec3 stage = util.vector().of(2.3, 2.3, 1.2);
        BlockPos shaft = util.grid().at(3, 1, 3);
        BlockPos charger = shaft.above();
        BlockPos table = util.grid().at(1, 1, 3);
        scene.world().setBlock(shaft, AllBlocks.SHAFT.getDefaultState(), false);
        scene.world().setBlock(charger, LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState(), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(table), Direction.DOWN);
        scene.world().showSection(util.select().position(shaft), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(charger), Direction.DOWN);
        scene.idle(5);
        // A player stand-in at the front, facing the camera with the instrument in its hand.
        ElementLink<EntityElement> player = holder(scene, util.vector().of(2.5, 1, 1.2), 180, keytar,
                new Rotations(-30, 0, 0), new Rotations(-20, 0, 0));
        scene.idle(10);

        scene.overlay().showControls(stage, Pointing.DOWN, 60).rightClick();
        say(scene, 130, stage, "Right-click with the Hayacio Keytar in the main hand to play it. Anywhere in the inventory it hangs on its strap, and held in the main hand it swings round to the front.");
        hold(scene, player, melodica);
        scene.overlay().showControls(stage, Pointing.DOWN, 60).rightClick();
        say(scene, 110, stage, "The Melodica plays from either hand, and is raised to the lips while it is played. It does not open while a Hayacio Keytar is in the main hand.");
        say(scene, 120, stage, "Both are played with the mouse, the computer keys Z to M and Q to P, or a MIDI keyboard. The left and right arrow keys shift the octave.");
        say(scene, 130, stage, "The Melodica sounds only while air flows: hold Space, play it with the mouse, or send MIDI CC2. The Hayacio Keytar has eight tones, a pitch-bend slider, and Space as its sustain pedal instead.");
        notes(scene, stage, 80);
        hold(scene, player, keytar);
        say(scene, 100, stage, PonderPalette.GREEN, "The sound comes from the player playing it, and players nearby hear it too. No speakers are needed.");

        Vec3 top = util.vector().topOf(charger);
        say(scene, 120, top, "The Hayacio Keytar runs on its own battery of 36000 FE, spent only while notes sound. When it is empty, the Keytar falls silent. The Melodica needs no power.");
        scene.overlay().showControls(top, Pointing.DOWN, 40).rightClick().withItem(keytar);
        hold(scene, player, ItemStack.EMPTY);
        scene.idle(10);
        scene.world().modifyBlockEntity(charger, DiscCellCharger.class, entity -> entity.inventory.setStackInSlot(0, keytar.copy()));
        scene.world().setKineticSpeed(util.select().fromTo(shaft, charger), 32);
        scene.world().modifyBlock(charger, state -> state.setValue(DiscCellChargerBlock.CHARGING, true), false);
        say(scene, 130, top, "Right-click a Disc Cell Energy Injector with it to charge it there, from a shaft turning at 16 RPM or more beneath, or from FE. Right-click with an empty hand to take it back.");

        Vec3 tableTop = util.vector().topOf(table);
        ItemStack painted = keytar.copy();
        new KeytarFinish(true, DyeColor.RED).applyTo(painted);
        scene.overlay().showControls(tableTop, Pointing.DOWN, 30).withItem(new ItemStack(Items.RED_DYE));
        scene.idle(20);
        scene.idle(10);
        hold(scene, player, painted);
        say(scene, 130, tableTop, "Craft the Hayacio Keytar with one dye to color its blade, with an ink sac for a black body, or with bone meal for a white body. Its charge carries over.");
    }
}
