package org.haruyq.createmediasystems.items.partybox.stage320.client;

import static org.haruyq.createmediasystems.client.PonderScenes.hold;
import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.items.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.items.launcher.DiscCellChargerBlock;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxEntity;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxFeature;
import org.haruyq.createmediasystems.items.power.BatteryItem;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.items.power.PowerFeature;

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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Ponder scenes for the placed PartyBox trolley and the replaceable battery it and other devices run on. */
public final class PartyBoxPonder {
    private PartyBoxPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(PartyBoxFeature.PARTYBOX.getId())
                .addStoryBoard(Create.asResource("redstone_link"), PartyBoxPonder::partybox);
        helper.forComponents(PartyBoxFeature.PARTYBOX.getId(), PowerFeature.BATTERY.getId())
                .addStoryBoard(Create.asResource("redstone_link"), PartyBoxPonder::battery);
    }

    /** An empty box standing at {@code at}, its speakers toward the camera. */
    private static PartyBoxEntity box(Level level, Vec3 at) {
        PartyBoxEntity box = new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(), level);
        box.setPos(at.x, at.y, at.z);
        return box;
    }

    private static ItemStack cell(int energy) {
        ItemStack battery = new ItemStack(PowerFeature.BATTERY.get());
        ChargeableItem.setEnergy(battery, energy);
        return battery;
    }

    /** What a disc and a charged battery put into the box do: it is loaded the way a packed item is, then played. */
    private static void load(Entity entity) {
        if (!(entity instanceof PartyBoxEntity box)) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Disc", new ItemStack(Items.MUSIC_DISC_CAT).save(box.level().registryAccess()));
        tag.put("Battery", cell(BatteryItem.CAPACITY).save(box.level().registryAccess()));
        tag.putInt("Energy", BatteryItem.CAPACITY);
        tag.putInt("Volume", 18);
        tag.putInt("Lights", 1);
        box.loadPortable(tag);
        box.play();
    }

    private static void lights(CreateSceneBuilder scene, ElementLink<EntityElement> link, int mode) {
        scene.world().modifyEntity(link, entity -> {
            if (entity instanceof PartyBoxEntity box) {
                box.setLights(mode);
            }
        });
    }

    private static void partybox(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "partybox", "The Party Speaker");
        Vec3 floor = util.vector().topOf(2, 0, 2);
        Vec3 body = floor.add(0, .4, 0);
        Vec3 speakers = floor.add(0, .4, -.25);
        // A player stand-in west of the spot, facing east toward it, carrying the PartyBox.
        ElementLink<EntityElement> player = holder(scene, util.vector().of(0.9, 1, 2.5), -90,
                new ItemStack(PartyBoxFeature.PARTYBOX.get()));
        scene.idle(15);

        scene.overlay().showControls(floor, Pointing.DOWN, 40).rightClick();
        scene.idle(15);
        hold(scene, player, ItemStack.EMPTY);
        ElementLink<EntityElement> link = scene.world().createEntity(level -> box(level, floor));
        scene.idle(15);
        say(scene, 120, body, "Right-click the top of a block with the SBL PartyBox Stage 320 to set it down there. It can also be set down on top of an assembled contraption.");

        hold(scene, player, new ItemStack(Items.MUSIC_DISC_CAT));
        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick();
        say(scene, 130, body, "Right-click it with a disc to load it. It takes a music disc, a Custom Disc, a CD, a DVD or a Blu-ray, and plays the disc's tracks in order.");

        hold(scene, player, cell(BatteryItem.CAPACITY));
        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick();
        scene.idle(20);
        hold(scene, player, ItemStack.EMPTY);
        scene.world().modifyEntity(link, PartyBoxPonder::load);
        notes(scene, speakers, 140);
        say(scene, 130, speakers, PonderPalette.GREEN, "Right-click it with a charged PartyBox 68 Wh replaceable battery to power it, and it plays until the last track ends or the battery runs flat. Putting in another disc or battery hands back the old one.");

        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick();
        say(scene, 110, body, "Right-click it with an empty hand to take its handle and pull it along behind you. Right-click it again to let go.");

        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick().whileSneaking();
        say(scene, 130, body, "Sneak-right-click it to open its controls: play and pause, previous and next track, a seek bar with -10s and +10s, the volume from 0 to 32, the bass and the lights.");

        notes(scene, speakers, 140);
        scene.overlay().showText(130).attachKeyFrame().pointAt(speakers).placeNearTarget()
                .text("The bass can be OFF, DEEP or PUNCHY, and the lights FLOW, PULSE, STARS or OFF. A higher volume, more bass and lights all drain the battery faster.");
        for (int mode : new int[] {2, 3, 0, 1}) {
            scene.idle(35);
            lights(scene, link, mode);
        }
        scene.idle(15);

        hold(scene, player, new ItemStack(VideoFeature.REMOTE.get()));
        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick().whileSneaking();
        say(scene, 100, body, "Sneak-right-click it while holding a Remote to pair the Remote with it.");

        hold(scene, player, ItemStack.EMPTY);
        scene.overlay().showControls(body, Pointing.DOWN, 30).leftClick();
        scene.idle(10);
        scene.world().modifyEntity(link, Entity::discard);
        scene.world().createItemEntity(body, new Vec3(0, .2, 0), new ItemStack(PartyBoxFeature.PARTYBOX.get()));
        say(scene, 130, body, "PACK UP in its controls puts it into your inventory as an item that keeps its disc, battery and settings. Hitting it drops it as that item instead. It can't be packed up while someone pulls it.");
    }

    private static void battery(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "battery", "The Replaceable Battery");
        BlockPos motor = util.grid().at(1, 1, 2);
        BlockPos injector = motor.above();
        Vec3 top = util.vector().topOf(injector);

        scene.world().setBlock(motor, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), false);
        scene.world().setBlock(injector, LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(motor, injector), Direction.DOWN);
        scene.world().setKineticSpeed(util.select().fromTo(motor, injector), 32);
        ElementLink<EntityElement> player = holder(scene, util.vector().of(3.2, 1, 1.4), 180, cell(0));
        scene.idle(15);
        say(scene, 130, util.vector().centerOf(injector), "A PartyBox 68 Wh replaceable battery is charged in a Disc Cell Energy Injector. The Injector runs on a shaft turning at 16 RPM or faster into its bottom, or on FE.");

        scene.overlay().showControls(top, Pointing.DOWN, 40).rightClick().withItem(cell(0));
        scene.idle(10);
        hold(scene, player, ItemStack.EMPTY);
        scene.world().modifyBlockEntity(injector, DiscCellCharger.class, charger -> charger.insert(cell(0)));
        scene.world().modifyBlock(injector, state -> state.setValue(DiscCellChargerBlock.CHARGING, true), false);
        say(scene, 110, top, "A newly crafted battery is empty. Right-click the Injector with it to put it in to charge.");

        scene.overlay().showControls(top, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        scene.world().modifyBlockEntity(injector, DiscCellCharger.class, DiscCellCharger::take);
        hold(scene, player, cell(BatteryItem.CAPACITY));
        scene.world().modifyBlock(injector, state -> state.setValue(DiscCellChargerBlock.CHARGING, false), false);
        say(scene, 110, top, PonderPalette.GREEN, "Its bar shows how full it is. Right-click the Injector with an empty hand to take it back out.");

        say(scene, 110, top, "Other FE item chargers can fill it as well. It only takes FE in and never gives any out.");

        scene.overlay().showControls(top, Pointing.DOWN, 40).rightClick().whileSneaking().withItem(cell(BatteryItem.CAPACITY));
        say(scene, 130, top, "Sneak-right-clicking the Injector with a charged battery moves as much of its charge into the Injector's reserve as the reserve has room for. The reserve charges the item inside whenever the shaft turns slower than 16 RPM.");

        Vec3 floor = util.vector().topOf(3, 0, 2);
        Vec3 body = floor.add(0, .4, 0);
        scene.world().createEntity(level -> box(level, floor));
        scene.idle(15);
        scene.overlay().showControls(body, Pointing.DOWN, 40).rightClick().withItem(cell(BatteryItem.CAPACITY));
        say(scene, 140, body, "The battery goes into the SBL PartyBox Stage 320 and the SBL Megaphone 30. Right-clicking the Hayacio PX-S1100WE MIDI Keyboard or a Broadcast Power Base with it moves as much of its charge into them as they have room for.");
    }
}
