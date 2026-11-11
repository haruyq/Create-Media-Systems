package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock.Disc;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock.Drive;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;
import org.haruyq.createmediasystems.stream.Remotable.Action;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class DriveEmulatorGameTests {
    /**
     * Whoever puts a disc in first has the drive and shares only their own disc; the Blu-ray player beside it plays
     * a Blu-ray, with a remote's buttons, and the disc reader a CD; the drive is free again once its player leaves.
     */
    @GameTest(template = "empty")
    public static void whatIsBesideADrivePlaysItsHostsDisc(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos.west(), AudioFeature.DISC_READER.get());
        helper.setBlock(pos.east(), VideoFeature.VIDEO_PLAYER.get());
        helper.setBlock(pos, VideoFeature.DRIVE_EMULATOR.get());
        DriveEmulatorBlock.Entity emulator = helper.getBlockEntity(pos);
        VideoPlayerBlock.Entity player = helper.getBlockEntity(pos.east());
        DiscReaderBlock.Entity reader = helper.getBlockEntity(pos.west());
        // Neither is logged in: an emulator takes that for its host having left.
        ServerPlayer host = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "host"));
        ServerPlayer other = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "other"));
        host.setPos(Vec3.atCenterOf(helper.absolutePos(pos.north())));
        other.setPos(Vec3.atCenterOf(helper.absolutePos(pos.south())));

        emulator.update(other, Drive.LOADING, "", Disc.BLU_RAY);
        emulator.update(other, Drive.OPEN, "", Disc.BLU_RAY);
        helper.assertTrue(emulator.drive() == Drive.OPEN && emulator.host() == null
                        && !helper.getBlockState(pos).getValue(DriveEmulatorBlock.LIT),
                "a real drive that closed on nothing leaves it free: " + emulator.drive() + " " + emulator.host());
        emulator.update(host, Drive.LOADING, "", Disc.BLU_RAY);
        helper.assertTrue(emulator.drive() == Drive.LOADING && host.getUUID().equals(emulator.host()) && player.playlist().isEmpty(),
                "a disc the real drive closes on makes it its player's before it is read: " + emulator.drive() + " " + emulator.host());
        emulator.update(host, Drive.READING, "MOVIE", Disc.BLU_RAY);
        helper.assertTrue(emulator.drive() == Drive.READING && host.getUUID().equals(emulator.host())
                        && helper.getBlockState(pos).getValue(DriveEmulatorBlock.LIT),
                "a disc put in makes it its player's and lights its lamp: " + emulator.drive() + " " + emulator.host());
        emulator.update(other, Drive.OPEN, "", Disc.BLU_RAY);
        emulator.update(host, Drive.READY, new DiscUrl(other.getUUID(), UUID.randomUUID()).toString(), Disc.BLU_RAY);
        helper.assertTrue(emulator.drive() == Drive.READING && player.playlist().isEmpty(),
                "another player, or another player's disc, changes nothing: " + emulator.drive() + " " + player.playlist());

        String link = new DiscUrl(host.getUUID(), UUID.randomUUID()).toString();
        emulator.update(host, Drive.READY, link, Disc.BLU_RAY);
        Streams.Source playing = player.nowPlaying();
        helper.assertTrue(player.playlist().equals(List.of(link)) && playing != null && playing.video().maxHeight() == 1080
                && reader.nowPlaying() == null, "the Blu-ray player beside it plays its Blu-ray, the disc reader doesn't: "
                + player.playlist() + " " + playing + " " + reader.nowPlaying());
        helper.assertTrue("MOVIE (Blu-ray)".equals(emulator.discName()), "it is called by its label: " + emulator.discName());
        helper.assertTrue(emulator.status().getString().equals(Component.translatable("create_media_systems.drive.loading").getString()),
                "it says it loads until the player has its disc playing: " + emulator.status().getString());

        // A remote's buttons, as RemoteItem passes them on.
        player.control(Action.PLAY_PAUSE, 0, host);
        helper.assertTrue(player.nowPlaying() == null && player.state().getString().toLowerCase(java.util.Locale.ROOT).contains("paused"), "paused: " + player.state().getString());
        player.control(Action.PLAY_PAUSE, 0, host);
        helper.assertTrue(player.nowPlaying() != null, "played on: " + player.state().getString());
        player.control(Action.STOP, 0, host);
        helper.assertTrue(player.nowPlaying() == null && player.state().getString().toLowerCase(java.util.Locale.ROOT).contains("stopped"), "stopped: " + player.state().getString());
        player.control(Action.SELECT, 1, host);
        helper.assertTrue(player.nowPlaying() != null, "played again from its remote: " + player.state().getString());

        // A disc of the player's own comes first; taken out, the drive's plays again.
        ItemStack own = new ItemStack(VideoFeature.BLU_RAY.get());
        own.set(VideoFeature.PLAYLIST.get(), List.of("https://www.youtube.com/watch?v=own"));
        player.insert(own, host);
        helper.assertTrue(player.playlist().equals(List.of("https://www.youtube.com/watch?v=own")), "its own disc: " + player.playlist());
        player.insert(ItemStack.EMPTY, host);
        helper.assertTrue(player.playlist().equals(List.of(link)) && player.nowPlaying() != null, "the drive's again: " + player.playlist());

        String cd = new DiscUrl(host.getUUID(), UUID.randomUUID()).toString();
        emulator.update(host, Drive.READING, "", Disc.CD);
        helper.assertTrue(player.playlist().isEmpty() && player.nowPlaying() == null, "the Blu-ray came out: " + player.playlist());
        emulator.update(host, Drive.READY, cd, Disc.CD);
        helper.assertTrue(reader.nowPlaying() != null && reader.nowPlaying().url().equals(cd) && player.playlist().isEmpty(),
                "the disc reader beside it plays its CD, the Blu-ray player doesn't: " + reader.nowPlaying() + " " + player.playlist());

        helper.succeedWhen(() -> helper.assertTrue(emulator.drive() == Drive.OPEN && emulator.host() == null
                && reader.nowPlaying() == null && !helper.getBlockState(pos).getValue(DriveEmulatorBlock.LIT),
                "its host left, and it still has their disc: " + emulator.drive()));
    }
}
