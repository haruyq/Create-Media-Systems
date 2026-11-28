package org.haruyq.createmediasystems.blocks.audio;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.client.ClientFeature;

import com.simibubi.create.AllItems;
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
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

public final class AudioClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        RangeQuillHandler.register();
        LineMixerClient.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(AudioFeature.CUSTOM_DISC.getId(), AudioFeature.RANGE_QUILL.getId())
                .addStoryBoard(Create.asResource("redstone_link"), AudioClient::jukebox);
        helper.forComponents(AudioFeature.SPEAKER.getId(), AudioFeature.DISC_READER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), AudioClient::speakers);
        helper.forComponents(AudioFeature.LINE_MIXER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), AudioClient::lineMixer);
    }

    /** Where the point (x, z) of the panel of the mixer at {@code mixer}, its front to the north, is in a scene. */
    private static Vec3 onPanel(BlockPos mixer, float x, float z) {
        return Vec3.atLowerCornerOf(mixer).add(LineMixerBlock.place(Direction.NORTH, x, LineMixerBlock.HEIGHT, z));
    }

    private static void lineMixer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "line_mixer", "Mixing Sources with the Line Mixer");
        ItemStack quill = new ItemStack(AudioFeature.RANGE_QUILL.get());

        // Its front to the camera, so its right half is to the west.
        BlockState facingCamera = AudioFeature.LINE_MIXER.get().defaultBlockState()
                .setValue(LineMixerBlock.FACING, Direction.NORTH);
        BlockPos mixer = util.grid().at(1, 1, 2);
        scene.world().setBlock(mixer, facingCamera, false);
        scene.world().setBlock(mixer.west(), facingCamera.setValue(LineMixerBlock.RIGHT, true), false);
        scene.world().showSection(util.select().fromTo(0, 1, 2, 1, 1, 2), Direction.DOWN);
        scene.idle(15);
        say(scene, 90, onPanel(mixer, 16, 8), "A Line Mixer, two blocks wide, mixes several sources into one line for the speakers linked to it.");

        BlockPos reader = util.grid().at(2, 1, 4);
        BlockPos jukebox = util.grid().at(0, 1, 4);
        scene.world().setBlock(reader, AudioFeature.DISC_READER.get().defaultBlockState(), false);
        scene.world().setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(jukebox), Direction.DOWN);
        scene.idle(10);
        BlockPos[] sources = {reader, jukebox};
        for (int channel = 0; channel < sources.length; channel++) {
            BlockPos source = sources[channel];
            String key = "Input" + channel;
            scene.overlay().showControls(util.vector().topOf(source), Pointing.DOWN, 25).rightClick().withItem(quill);
            scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, source, new AABB(source), 50);
            scene.idle(30);
            scene.overlay().showControls(onPanel(mixer, 2.5f + 4 * channel, 9), Pointing.DOWN, 25).rightClick().withItem(quill);
            scene.idle(10);
            scene.world().modifyBlockEntityNBT(util.select().position(mixer), LineMixerBlock.Entity.class,
                    nbt -> nbt.putLong(key, source.asLong()));
            scene.idle(20);
        }
        say(scene, 110, onPanel(mixer, 4.5f, 9), "With the Range Quill, pick a source, then right-click a channel of the mixer to plug it in. Its lamp lights. Doing the same again unplugs it.");

        BlockPos speaker = util.grid().at(4, 1, 4);
        scene.world().setBlock(speaker, AudioFeature.SPEAKER.get().defaultBlockState()
                .setValue(SpeakerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(speaker), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(onPanel(mixer, 26.5f, 9), Pointing.DOWN, 25).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, mixer, new AABB(mixer).minmax(new AABB(mixer.west()))
                .setMaxY(mixer.getY() + LineMixerBlock.HEIGHT / 16), 60);
        scene.idle(30);
        scene.overlay().showControls(util.vector().topOf(speaker), Pointing.DOWN, 25).rightClick().withItem(quill);
        scene.world().modifyBlockEntityNBT(util.select().position(speaker), SpeakerBlock.Entity.class,
                nbt -> nbt.putLong("Source", mixer.asLong()));
        notes(scene, SpeakerBlock.mouth(speaker, Direction.NORTH), 80);
        say(scene, 110, onPanel(mixer, 26.5f, 9), PonderPalette.GREEN, "Right-click its master section to pick the mixer itself, then each speaker to link it. The speakers play everything plugged in.");

        scene.overlay().showControls(onPanel(mixer, 2.5f, 5), Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        for (int step = 1; step <= 12; step++) {
            float position = 1 - step * 0.05f;
            scene.world().modifyBlockEntityNBT(util.select().position(mixer), LineMixerBlock.Entity.class,
                    nbt -> nbt.putFloat("Fader0", position));
            scene.idle(1);
        }
        say(scene, 120, onPanel(mixer, 2.5f, 9), "Point at a fader, hold right-click and look along it: the knob follows, and everyone hears it at once. A channel's fader sets how loud its source is, the master's how loud they all are.");
        say(scene, 90, onPanel(mixer, 24, 9), "The meter beside each fader shows what its channel passes on, and the master's what the mixer does, to whoever hears it.");

        BlockPos extension = mixer.east(2);
        scene.world().setBlock(extension, facingCamera, false);
        scene.world().setBlock(extension.west(), facingCamera.setValue(LineMixerBlock.RIGHT, true), false);
        scene.world().showSection(util.select().fromTo(2, 1, 2, 3, 1, 2), Direction.DOWN);
        scene.idle(15);
        say(scene, 120, onPanel(extension, 16, 8), PonderPalette.GREEN, "Another Line Mixer set against its left side is an extension: seven more channels under the same master. More can follow it.");
    }

    private static void jukebox(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "jukebox", "Streaming Custom Discs");

        BlockPos jukebox = util.grid().at(2, 1, 2);
        scene.world().setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState(), false);
        scene.world().showSection(util.select().position(jukebox), Direction.DOWN);
        scene.idle(15);

        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        say(scene, 80, util.vector().topOf(jukebox), "Use a Custom Disc in the air to write a YouTube or Discord link onto it. The server checks that the link plays before writing it.");

        scene.overlay().showControls(util.vector().topOf(jukebox), Pointing.DOWN, 40).rightClick().withItem(disc);
        scene.idle(10);
        scene.world().setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true), false);
        notes(scene, util.vector().topOf(jukebox).add(0, 0.3, 0), 60);
        say(scene, 90, util.vector().topOf(jukebox), PonderPalette.GREEN, "In any jukebox it streams to everyone nearby: loud up close, fading out with distance.");

        ItemStack quill = new ItemStack(AudioFeature.RANGE_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(jukebox), Pointing.DOWN, 40).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, jukebox, new AABB(jukebox), 200);
        say(scene, 80, util.vector().centerOf(jukebox), "To limit who hears it, right-click the jukebox with a Jukebox Range Quill to pick it.");

        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "range",
                new AABB(Vec3.ZERO, new Vec3(5, 3, 5)), 120);
        say(scene, 110, util.vector().of(5, 2, 0), PonderPalette.GREEN, "Then select two corners like with the Schematic and Quill, and right-click again to set the volume. Only players inside the range hear it.");

        say(scene, 90, util.vector().centerOf(jukebox), "Right-click the picked jukebox again without a selection to change just the volume, or reset the range.");
    }

    private static void speakers(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "speakers", "Speakers and the Disc Reader");

        BlockPos reader = util.grid().at(2, 1, 1);
        BlockPos left = util.grid().at(3, 1, 3);
        BlockPos right = util.grid().at(1, 1, 3);
        scene.world().setBlock(reader, AudioFeature.DISC_READER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(AudioFeature.CUSTOM_DISC.get()));
        say(scene, 80, util.vector().topOf(reader), "A Disc Reader plays a Custom Disc without making a sound itself...");

        var facingCamera = AudioFeature.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
        scene.world().setBlock(left, facingCamera, false);
        scene.world().setBlock(right, facingCamera, false);
        scene.world().showSection(util.select().position(left), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(right), Direction.DOWN);
        scene.idle(10);
        say(scene, 80, util.vector().centerOf(left), "...for the Speakers linked to it. Speakers also work with a jukebox, playing in sync with it.");

        ItemStack quill = new ItemStack(AudioFeature.RANGE_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, reader, new AABB(reader), 120);
        scene.idle(35);
        scene.overlay().showControls(util.vector().topOf(left), Pointing.DOWN, 30).rightClick().withItem(quill);
        say(scene, 100, util.vector().centerOf(left), "With the Range Quill, pick the source, then right-click each speaker to link it and set its volume and channel.");

        Vec3 leftMouth = SpeakerBlock.mouth(left, Direction.NORTH);
        Vec3 rightMouth = SpeakerBlock.mouth(right, Direction.NORTH);
        notes(scene, leftMouth, 80);
        notes(scene, rightMouth, 80);
        scene.overlay().showText(40).pointAt(leftMouth).placeNearTarget().text("Left");
        scene.overlay().showText(40).pointAt(rightMouth).placeNearTarget().text("Right");
        scene.idle(50);
        say(scene, 90, util.vector().centerOf(right), PonderPalette.GREEN, "Set one to Left and one to Right for stereo, or leave them on Mix.");

        scene.overlay().showControls(util.vector().topOf(right), Pointing.DOWN, 30).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(10);
        scene.world().setBlock(right, facingCamera.setValue(SpeakerBlock.FACING, Direction.WEST), false);
        say(scene, 90, util.vector().centerOf(right), "Turn a speaker with a Wrench. It is loudest in front and quiet behind.");
        say(scene, 90, util.vector().topOf(reader), "Through Engineer's Goggles the reader shows whether it plays, its disc, and how far into the track it is.");
    }
}
