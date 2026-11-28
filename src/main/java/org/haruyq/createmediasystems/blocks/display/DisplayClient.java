package org.haruyq.createmediasystems.blocks.display;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;

import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/** Displays on this client: their renderer, and the display and image viewer scenes. */
public final class DisplayClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        DisplayRenderer.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(DisplayFeature.DISPLAY.getId(), LightingFeature.LIGHTING_LINK.getId())
                .addStoryBoard(Create.asResource("redstone_link"), DisplayClient::display);
        helper.forComponents(DisplayFeature.IMAGE_VIEWER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), DisplayClient::imageViewer);
    }

    private static void display(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "display", "Displays");

        // The camera looks from the north-west: the wall stands at the back, the display on its north side, so the
        // picture grows west (its right), towards the camera's side of the plate.
        Selection wall = util.select().fromTo(0, 1, 3, 4, 3, 3);
        scene.world().setBlocks(wall, Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().showSection(wall, Direction.DOWN);
        scene.idle(10);
        BlockPos display = util.grid().at(3, 1, 2);
        Vec3 picture = util.vector().of(2.5, 1.85, 2.9);
        scene.world().setBlock(display, DisplayFeature.DISPLAY.get().defaultBlockState()
                .setValue(DisplayBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(display), Direction.DOWN);
        scene.idle(15);
        say(scene, 90, util.vector().centerOf(display), "A Display goes on the side of a wall, where it shows a Blu-ray Player's video.");

        BlockPos player = util.grid().at(0, 1, 0);
        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        ItemStack wrench = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 30).rightClick().withItem(wrench);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, player, new AABB(player), 140);
        scene.idle(35);
        scene.overlay().showControls(util.vector().centerOf(display), Pointing.DOWN, 30).rightClick().withItem(wrench);
        say(scene, 110, util.vector().centerOf(display), PonderPalette.GREEN, "Hold a Tuning Wrench, right-click a Blu-ray Player to pick it, then right-click displays to link them; Shift+right-click a display to unlink it. Any number of displays can show one player.");

        scene.overlay().showControls(util.vector().centerOf(display), Pointing.DOWN, 40).scroll().withItem(wrench);
        scene.idle(10);
        scene.world().modifyBlockEntity(display, DisplayBlock.Entity.class, entity -> entity.resize(3 * DisplayBlock.UNIT,
                (int) Math.round(3 * DisplayBlock.UNIT / DisplayBlock.Ratio.WIDE.value), DisplayBlock.Ratio.WIDE));
        say(scene, 110, picture, "Looking at a display with the wrench, scroll makes it a block wider or narrower (a sixteenth with Shift). It grows right and up, and its height follows its ratio.");

        scene.overlay().showControls(picture, Pointing.DOWN, 40).scroll().whileCTRL().withItem(wrench);
        say(scene, 110, picture, "Ctrl+scroll steps through the ratios: free, 16:9, 4:3, 21:9, 1:1, and the video's own. Alt+scroll sets the height alone.");

        scene.overlay().showControls(picture, Pointing.DOWN, 40).rightClick().whileCTRL().withItem(wrench);
        say(scene, 110, picture, "Ctrl+right-click it for its lighting: Cinematic lights the room in front of it in the video's colours, Ambient glows on the wall around it.");

        say(scene, 110, picture, PonderPalette.GREEN, "Its sound comes from the middle of the picture, at the player's volume. While a speaker plays that Blu-ray Player, only the speakers are heard.");
    }

    private static void imageViewer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "image_viewer", "Image Viewers");

        // Set up as in the display's scene: the picture grows west, towards the camera's side of the plate.
        Selection wall = util.select().fromTo(0, 1, 3, 4, 3, 3);
        scene.world().setBlocks(wall, Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().showSection(wall, Direction.DOWN);
        scene.idle(10);
        BlockPos viewer = util.grid().at(3, 1, 2);
        Vec3 picture = util.vector().of(2.5, 1.85, 2.9);
        scene.world().setBlock(viewer, DisplayFeature.IMAGE_VIEWER.get().defaultBlockState()
                .setValue(ImageViewerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(viewer), Direction.DOWN);
        scene.idle(15);
        say(scene, 90, util.vector().centerOf(viewer), "An Image Viewer goes on the side of a wall like a Display, and shows a picture from a link.");

        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        say(scene, 120, util.vector().centerOf(viewer), "Right-click it with a Wrench to type the image's link: a PNG, JPEG, GIF (its first frame) or BMP, from a site the server allows. Each player's game downloads it.");

        ItemStack wrench = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).scroll().withItem(wrench);
        scene.idle(10);
        scene.world().modifyBlockEntity(viewer, ImageViewerBlock.Entity.class, entity -> entity.resize(3 * DisplayBlock.UNIT,
                (int) Math.round(3 * DisplayBlock.UNIT / DisplayBlock.Ratio.WIDE.value), DisplayBlock.Ratio.WIDE));
        say(scene, 120, picture, "Size it with a Tuning Wrench, just like a display. The last ratio Ctrl+scroll steps to is the image's own.");

        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).rightClick().withItem(wrench);
        scene.idle(10);
        scene.world().cycleBlockProperty(viewer, ImageViewerBlock.FRAMED);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, viewer,
                new AABB(1, 1, 2.9, 4, 2 + 11 / 16.0, 3), 120);
        say(scene, 120, picture, PonderPalette.GREEN, "Right-click it with the wrench to take its frame away: only the picture is left, and the wall shows through where the image is transparent. Right-click again to put the frame back.");

        say(scene, 90, picture, "Through Engineer's Goggles it shows its link and size.");
    }
}
