package org.haruyq.createmediasystems.broadcast;

import java.util.UUID;

import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.Streams;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BroadcastGameTests {
    private record Station(BroadcastTowerBlock.Tower tower, BroadcastBaseBlock.Base base,
                           BlockPos mast, DiscReaderBlock.Entity source) {
        boolean relaying() {
            return Streams.relays().stream().anyMatch(r -> r.sound() != null
                    && r.sound().profile() == tower.profile() && r.sound().source().equals(source.getBlockPos()));
        }
    }

    private static Station station(GameTestHelper h, BroadcastTowerBlock head) {
        var level = h.getLevel();
        var base = h.absolutePos(new BlockPos(1, 2, 1));
        var source = base.east();
        level.setBlockAndUpdate(base, BroadcastFeature.BASE.get().defaultBlockState());
        level.setBlockAndUpdate(base.above(), BroadcastFeature.MAST.get().defaultBlockState());
        level.setBlockAndUpdate(base.above(2), head.defaultBlockState());
        level.setBlockAndUpdate(source, AudioFeature.DISC_READER.get().defaultBlockState());
        return new Station((BroadcastTowerBlock.Tower) level.getBlockEntity(base.above(2)),
                (BroadcastBaseBlock.Base) level.getBlockEntity(base), base.above(),
                (DiscReaderBlock.Entity) level.getBlockEntity(source));
    }

    private static void play(GameTestHelper h, Station station) {
        var disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString());
        station.source.insert(disc, null);
        station.tower.configure(h.getLevel(), station.source.getBlockPos(), 1, Streams.Channel.MIX);
    }

    @GameTest(template = "empty")
    public static void forgeEnergyPowersOnlyAnIntactBroadcastingMast(GameTestHelper h) {
        var station = station(h, BroadcastFeature.ARRAY.get());
        var level = h.getLevel();
        var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, station.base.getBlockPos(), Direction.WEST);
        h.assertTrue(port != null && port.receiveEnergy(120, true) == 120 && port.getEnergyStored() == 0,
                "The FE side port simulates without spending or charging");
        port.receiveEnergy(120, false);
        station.tower.relay(level);
        h.assertTrue(port.getEnergyStored() == 120 && !station.relaying(), "An idle station draws nothing");
        play(h, station);
        h.assertTrue(station.relaying() && port.getEnergyStored() == 108, "The array broadcasts on 12 FE/t");
        station.tower.relay(level);
        h.assertTrue(port.getEnergyStored() == 108, "A source refresh in the same tick costs no extra FE");
        level.setBlockAndUpdate(station.mast, Blocks.AIR.defaultBlockState());
        station.tower.relay(level);
        h.assertTrue(!station.relaying() && port.getEnergyStored() == 108, "A broken mast cuts the audio and energy draw");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void aCreateShaftPowersAStationWithoutForgeEnergy(GameTestHelper h) {
        var station = station(h, BroadcastFeature.HORN.get());
        var level = h.getLevel();
        var motor = station.base.getBlockPos().below();
        level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalBlock.FACING, Direction.UP));
        play(h, station);
        h.runAfterDelay(26, () -> {
            h.assertTrue(Math.abs(station.base.getSpeed()) >= 16 && station.relaying()
                    && station.base.port().getEnergyStored() == 0, "An actual rotating bottom shaft drives the broadcast");
            level.setBlockAndUpdate(motor, Blocks.AIR.defaultBlockState());
            station.tower.relay(level);
        });
        h.runAfterDelay(32, () -> {
            h.assertTrue(!station.relaying() && station.source.nowPlaying() != null,
                    "Power loss silences the head while its linked source keeps its playback");
            h.succeed();
        });
    }
}
