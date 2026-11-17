package org.haruyq.createmediasystems.items.megaphone.megaphone30.client;

import static org.haruyq.createmediasystems.client.PonderScenes.hold;
import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.items.megaphone.megaphone30.MegaphoneFeature;
import org.haruyq.createmediasystems.items.power.PowerFeature;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** The megaphone's Ponder scene: a horn held by a player stand-in, its menu, and where its sound goes. */
public final class MegaphonePonder {
    private MegaphonePonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(MegaphoneFeature.MEGAPHONE_WHITE.getId(), MegaphoneFeature.MEGAPHONE_BLACK.getId())
                .addStoryBoard(Create.asResource("redstone_link"), MegaphonePonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "megaphone", "The Megaphone");
        // A player stand-in on the west side, facing east along the plate, the megaphone raised to its face.
        ElementLink<EntityElement> player = holder(scene, util.vector().of(1, 1, 2.5), -90,
                new ItemStack(MegaphoneFeature.MEGAPHONE_WHITE.get()));
        scene.idle(15);
        Vec3 horn = util.vector().of(1.6, 2.6, 2.5);
        Vec3 front = util.vector().of(3.2, 2.6, 2.5);
        Vec3 farFront = util.vector().of(4.5, 2.5, 2.5);
        Vec3 behind = util.vector().of(0.3, 2.6, 2.5);

        scene.overlay().showControls(horn, Pointing.DOWN, 60).rightClick();
        say(scene, 120, horn, "The SBL Megaphone 30 is a hand-held horn, in White or Black, that plays music through its own small amplifier. Hold it and right-click to open its playback controls.");

        scene.overlay().showControls(horn, Pointing.DOWN, 60).withItem(new ItemStack(AudioFeature.CUSTOM_DISC.get()));
        say(scene, 130, horn, "Its DISC / URL slot takes a music disc, a Custom Disc, a CD, a DVD or a Blu-ray. It plays the disc's tracks in order and stops after the last.");

        scene.overlay().showControls(horn, Pointing.DOWN, 60).withItem(new ItemStack(PowerFeature.BATTERY.get()));
        say(scene, 130, horn, "Its BATTERY slot takes a PartyBox 68 Wh replaceable battery, charged by rotation or FE in a Disc Cell Energy Injector. It plays only while the battery has charge, and a higher output drains it faster.");

        hold(scene, player, new ItemStack(MegaphoneFeature.MEGAPHONE_BLACK.get()));
        say(scene, 130, horn, "The screen has play/pause, previous and next track, a seek bar with -10s and +10s, the OUTPUT from 0 to 32, and the HORN setting: STANDARD, LOUD or OVERLOAD, each more distorted than the last.");

        notes(scene, front, 120);
        notes(scene, farFront, 120);
        notes(scene, behind, 20);
        scene.overlay().showText(40).pointAt(farFront).placeNearTarget().text("Front");
        scene.overlay().showText(40).pointAt(behind).placeNearTarget().text("Behind");
        scene.idle(50);
        say(scene, 130, front, PonderPalette.GREEN, "The sound comes from just in front of the holder's face. It is heard up to 112 blocks away, loudest in the direction the holder looks, much quieter behind, and fainter with distance.");

        hold(scene, player, ItemStack.EMPTY);
        say(scene, 110, horn, "It plays only while held in either hand. Switching to another item pauses it, and while paused it uses no charge.");
    }
}
