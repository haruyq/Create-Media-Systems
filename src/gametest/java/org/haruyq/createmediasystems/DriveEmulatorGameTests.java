package org.haruyq.createmediasystems;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock.Drive;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class DriveEmulatorGameTests {
    /** Whoever puts a disc in first has it, shares only their own disc, and gives it up by leaving. */
    @GameTest(template = "empty")
    public static void itsHostAloneChangesIt(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, CreateMediaSystems.DRIVE_EMULATOR.get());
        DriveEmulatorBlock.Entity emulator = helper.getBlockEntity(pos);
        // Neither is logged in: an emulator takes that for its host having left.
        ServerPlayer host = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "host"));
        ServerPlayer other = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "other"));
        host.setPos(Vec3.atCenterOf(helper.absolutePos(pos.north())));
        other.setPos(Vec3.atCenterOf(helper.absolutePos(pos.south())));

        emulator.update(host, Drive.READING, "MOVIE", false);
        helper.assertTrue(emulator.drive() == Drive.READING && host.getUUID().equals(emulator.host()),
                "a disc put in makes it its player's: " + emulator.drive() + " " + emulator.host());
        emulator.update(other, Drive.OPEN, "", false);
        emulator.update(host, Drive.READY, new DiscUrl(other.getUUID(), UUID.randomUUID()).toString(), false);
        helper.assertTrue(emulator.drive() == Drive.READING && emulator.playlist().isEmpty(),
                "another player, or another player's disc, changes nothing: " + emulator.drive() + " " + emulator.playlist());

        String link = new DiscUrl(host.getUUID(), UUID.randomUUID()).toString();
        emulator.update(host, Drive.READY, link, false);
        helper.assertTrue(emulator.drive() == Drive.READY && emulator.playlist().equals(List.of(link))
                && emulator.nowPlaying() != null, "its host's disc plays: " + emulator.drive() + " " + emulator.playlist());
        helper.assertTrue("MOVIE (Blu-ray)".equals(emulator.disc()), "it is called by its label: " + emulator.disc());

        helper.succeedWhen(() -> helper.assertTrue(emulator.drive() == Drive.OPEN && emulator.host() == null
                && emulator.playlist().isEmpty(), "its host left, and it still has their disc: " + emulator.drive()));
    }
}
