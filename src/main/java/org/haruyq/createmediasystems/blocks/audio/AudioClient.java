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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

public final class AudioClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        RangeQuillHandler.register();
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(AudioFeature.CUSTOM_DISC.getId(), AudioFeature.RANGE_QUILL.getId())
                .addStoryBoard(Create.asResource("redstone_link"), AudioClient::jukebox);
        helper.forComponents(AudioFeature.SPEAKER.getId(), AudioFeature.DISC_READER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), AudioClient::speakers);
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
    }
}
