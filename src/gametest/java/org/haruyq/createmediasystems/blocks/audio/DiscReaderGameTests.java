package org.haruyq.createmediasystems.blocks.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.PlaybackClock;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscReaderGameTests {
    @GameTest(template = "empty")
    public static void gogglesShowWhatItPlaysAndHowFar(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos, AudioFeature.DISC_READER.get().defaultBlockState());
        var reader = (DiscReaderBlock.Entity) level.getBlockEntity(pos);
        h.assertTrue(goggles(reader).contains("video_player.empty"), "Without a disc it says so: " + goggles(reader));

        var disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        String url = new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString();
        disc.set(AudioFeature.URL.get(), url);
        reader.insert(disc, null);
        String playing = goggles(reader);
        h.assertTrue(playing.contains("video_player.playing") && playing.contains(url.substring(0, 20)),
                "With a disc it plays it, and shows its link: " + playing);

        // A client's copy counts on from the position the server sent, but only while the server's clock counts.
        CompoundTag sent = reader.getUpdateTag(level.registryAccess());
        sent.putLong("Ticks", 100);
        sent.putLong("SentAt", level.getGameTime() - 40);
        var copy = new DiscReaderBlock.Entity(pos, reader.getBlockState());
        copy.setLevel(level);
        sent.putBoolean("Counting", false);
        copy.loadWithComponents(sent, level.registryAccess());
        h.assertTrue(goggles(copy).contains("video_player.loading"), "Standing, it is loading: " + goggles(copy));
        sent.putBoolean("Counting", true);
        copy.loadWithComponents(sent, level.registryAccess());
        h.assertTrue(goggles(copy).contains(PlaybackClock.format(7.0)), "Counting, it is 5 s plus 2 s in: " + goggles(copy));

        reader.power(true);
        reader.power(false);
        h.assertTrue(goggles(reader).contains("video_player.stopped"), "Its signal gone, it stops: " + goggles(reader));
        h.succeed();
    }

    private static String goggles(DiscReaderBlock.Entity reader) {
        List<Component> tooltip = new ArrayList<>();
        reader.addToGoggleTooltip(tooltip, false);
        return tooltip.toString();
    }
}
