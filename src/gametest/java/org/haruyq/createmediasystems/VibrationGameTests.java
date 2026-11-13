package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class VibrationGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPlayingJukeboxIsAVibration(GameTestHelper helper) {
        BlockPos jukebox = new BlockPos(1, 2, 1);
        BlockPos sensor = jukebox.east(3);
        helper.setBlock(jukebox, Blocks.JUKEBOX);
        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        ((JukeboxBlockEntity) helper.getBlockEntity(jukebox)).setTheItem(disc);
        // Putting the disc in is a vibration of its own: the sensor comes after it, so only the playing can wake it.
        helper.runAfterDelay(5, () -> helper.setBlock(sensor, Blocks.SCULK_SENSOR));
        helper.succeedWhen(() -> helper.assertBlockProperty(sensor, SculkSensorBlock.PHASE, SculkSensorPhase.ACTIVE));
    }
}
