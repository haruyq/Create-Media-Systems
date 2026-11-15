package org.haruyq.createmediasystems.blocks.train;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrainDiscReaderGameTests {
    @GameTest(template = "empty")
    public static void aTrainDiscReaderIsPlacedAndKeepsItsSchedule(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos, TrainFeature.TRAIN_DISC_READER.get().defaultBlockState());
        var reader = (TrainDiscReaderBlock.Entity) level.getBlockEntity(pos);
        // What the server sends its players the tick a reader is placed: one with no slot to write stopped the server.
        CompoundTag sent = reader.getUpdateTag(level.registryAccess());
        h.assertTrue(reader.slot() != null && reader.slot().isEmpty(), "A reader just placed has a slot, with nothing in it");
        h.assertTrue(!sent.contains(TrainDiscReaderBlock.ITEM_TAG), "It tells its players of no item: " + sent);

        reader.insert(new ItemStack(TrainFeature.ANNOUNCEMENT_SCHEDULE.get()), null);
        var again = new TrainDiscReaderBlock.Entity(pos, reader.getBlockState());
        again.loadWithComponents(reader.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        h.assertTrue(again.slot().get().is(TrainFeature.ANNOUNCEMENT_SCHEDULE.get()), "Its schedule is saved with it, and read back");
        h.succeed();
    }
}
