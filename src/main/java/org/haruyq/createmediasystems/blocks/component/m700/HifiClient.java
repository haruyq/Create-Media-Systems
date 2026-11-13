package org.haruyq.createmediasystems.blocks.component.m700;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import java.util.Arrays;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.disc.DiscFeature;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

public final class HifiClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        HifiControls.register();
        HifiRenderer.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(Stream.concat(Arrays.stream(Unit.values()).map(unit -> BuiltInRegistries.ITEM.getKey(HifiFeature.hifiUnit(unit))),
                        Stream.of(HifiFeature.HIFI_SPEAKER.getId(), HifiFeature.HIFI_CABLE.getId(),
                                DiscFeature.CD.getId(), DiscFeature.CASSETTE.getId())).toArray(ResourceLocation[]::new))
                .addStoryBoard(Create.asResource("redstone_link"), HifiClient::hifi);
    }

    private static void hifi(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "hifi", "The Hi-Fi Stack");

        BlockPos stack = util.grid().at(2, 1, 2);
        Vec3 top = util.vector().topOf(stack);
        Vec3 front = util.vector().blockSurface(stack, Direction.NORTH);
        Vec3 back = util.vector().blockSurface(stack, Direction.SOUTH);
        scene.world().setBlock(stack, HifiFeature.HIFI_STACK.get().defaultBlockState()
                .setValue(HifiStackBlock.FACING, Direction.NORTH), false);
        scene.world().modifyBlockEntity(stack, HifiStackBlock.Entity.class, entity -> entity.add(Unit.CASSETTE_DECK));
        scene.world().showSection(util.select().position(stack), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(top, Pointing.DOWN, 60).rightClick()
                .withItem(new ItemStack(HifiFeature.hifiUnit(Unit.AMPLIFIER)));
        for (Unit unit : new Unit[]{Unit.DISC_PLAYER, Unit.TUNER, Unit.EQUALIZER, Unit.AMPLIFIER}) {
            scene.idle(12);
            scene.world().modifyBlockEntity(stack, HifiStackBlock.Entity.class, entity -> entity.add(unit));
        }
        say(scene, 120, top, "A hi-fi stack is separate units in one block: place one, then use the next on it. A whole set of five fills the block. Any order works, and any unit can be left out.");
        scene.overlay().showControls(top, Pointing.DOWN, 50).rightClick().whileSneaking();
        say(scene, 80, top, "Sneak and right-click it with an empty hand to take the top unit off again.");

        scene.rotateCameraY(180);
        scene.idle(25);
        scene.overlay().showControls(back, Pointing.RIGHT, 70).rightClick()
                .withItem(new ItemStack(HifiFeature.HIFI_CABLE.get()));
        Cable[] cables = Cable.values();
        for (int plugged = 1; plugged <= cables.length; plugged++) {
            Cable[] wired = java.util.Arrays.copyOf(cables, plugged);
            scene.idle(10);
            scene.world().modifyBlockEntity(stack, HifiStackBlock.Entity.class, entity -> entity.arrange(false, wired));
        }
        say(scene, 140, back, PonderPalette.GREEN, "Every unit but the amplifier needs its flat cable: use an Audio Cable on the socket at its back. The disc player needs its line cable too, or it plays unheard. Right-click a plug to pull it out.");
        for (int side = -1; side <= 1; side += 2) {
            BlockPos speaker = stack.east(side);
            scene.world().setBlock(speaker, HifiFeature.HIFI_SPEAKER.get().defaultBlockState()
                    .setValue(HifiSpeakerBlock.FACING, Direction.NORTH), false);
            scene.world().showSection(util.select().position(speaker), Direction.DOWN);
            scene.idle(10);
            scene.overlay().showControls(util.vector().blockSurface(speaker, Direction.SOUTH), Pointing.RIGHT, 30).rightClick()
                    .withItem(new ItemStack(HifiFeature.HIFI_CABLE.get()));
            int channel = (side < 0 ? Streams.Channel.RIGHT : Streams.Channel.LEFT).ordinal();
            scene.world().modifyBlockEntityNBT(util.select().position(speaker), HifiSpeakerBlock.Entity.class, nbt -> {
                nbt.put("Source", NbtUtils.writeBlockPos(stack));
                nbt.putInt("Channel", channel);
            });
            scene.idle(20);
        }
        say(scene, 140, back, "For sound, use an Audio Cable on one of the amplifier's speaker terminals, then on a hi-fi speaker: one for the left, one for the right. Sneak and right-click a speaker with an empty hand to pull its cable out.");

        scene.rotateCameraY(-180);
        scene.idle(25);
        scene.world().modifyBlockEntity(stack, HifiStackBlock.Entity.class, entity -> entity.arrange(true, cables));
        scene.overlay().showControls(front, Pointing.RIGHT, 60).rightClick();
        say(scene, 120, front, PonderPalette.GREEN, "Point at a button and right-click to press it. Hold right-click on a knob and look left or right to turn it. What you point at is named above the hotbar.");
        scene.overlay().showControls(front, Pointing.RIGHT, 60).rightClick()
                .withItem(new ItemStack(DiscFeature.BLU_RAY.get()));
        say(scene, 120, front, "Open the disc player's tray and put a CD, DVD, Blu-ray or Custom Disc on it. A DVD or Blu-ray also shows on displays linked to the stack with a Display Quill.");
        scene.overlay().showControls(front, Pointing.RIGHT, 60).rightClick()
                .withItem(new ItemStack(DiscFeature.CASSETTE.get()));
        say(scene, 100, front, "Put a Cassette Tape in either deck. A tape remembers where it was stopped, so rewind it to hear it from the top.");
        say(scene, 130, top, "The amplifier picks what is heard and how loud. The sound controller shapes it with its presets and shows its spectrum. A Remote paired with the stack works its disc player and volume from afar.");
    }
}
