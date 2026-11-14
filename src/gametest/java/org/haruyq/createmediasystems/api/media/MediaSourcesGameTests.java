package org.haruyq.createmediasystems.api.media;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import org.haruyq.createmediasystems.stream.DiscUrl;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MediaSourcesGameTests {
    @GameTest(batch = "launcher", template = "empty")
    public static void aSourcesScreensAreTheDisplaysShowingItAsTheyStand(GameTestHelper h) {
        var level = h.getLevel();
        var source = h.absolutePos(new BlockPos(1, 2, 1));
        var anchor = h.absolutePos(new BlockPos(6, 2, 3));
        level.setBlockAndUpdate(source, AudioFeature.DISC_READER.get().defaultBlockState());
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        var reader = (DiscReaderBlock.Entity) level.getBlockEntity(source);
        var display = (DisplayBlock.Entity) level.getBlockEntity(anchor);
        // Four blocks wide and two high, linked to the reader, which plays nothing yet.
        display.resize(4 * DisplayBlock.UNIT, 2 * DisplayBlock.UNIT, DisplayBlock.Ratio.FREE);
        display.link(source);
        display.relay(level);
        h.assertTrue(MediaSources.screens(level, source).isEmpty(), "A source that plays nothing has no screen showing it");

        var disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString());
        reader.insert(disc, null);
        display.relay(level);
        List<MediaSources.Screen> screens = MediaSources.screens(level, source);
        h.assertTrue(screens.size() == 1, "Its one display shows it: " + screens);
        MediaSources.Screen screen = screens.getFirst();
        // Facing north, its panel runs west from its anchor block's east edge: its middle is two blocks west of
        // that edge and one up, on the panel's front.
        Vec3 middle = new Vec3(anchor.getX() + 1 - 2, anchor.getY() + 1, anchor.getZ() + 1 - DisplayBlock.DEPTH);
        h.assertTrue(screen.facing() == Direction.NORTH && screen.width() == 4 && screen.height() == 2
                && screen.middle().distanceTo(middle) < 1e-6, "As it stands: " + screen + ", its middle at " + middle);
        h.assertTrue(MediaSources.screens(level, anchor).isEmpty(), "Asked of the display's own place, which is no source: none");

        // Unlinked, or gone, it shows the source no longer.
        display.link(null);
        display.relay(level);
        h.assertTrue(MediaSources.screens(level, source).isEmpty(), "An unlinked display is no screen of the source");
        display.link(source);
        display.relay(level);
        h.assertTrue(MediaSources.screens(level, source).size() == 1, "Linked again, it is");
        level.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
        h.assertTrue(MediaSources.screens(level, source).isEmpty(), "A display taken down is none");
        h.succeed();
    }
}
