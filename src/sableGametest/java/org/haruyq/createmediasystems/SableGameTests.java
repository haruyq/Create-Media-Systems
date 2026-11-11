package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.display.DisplayFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.EqualizerBlock;
import org.haruyq.createmediasystems.video.RemoteItem;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class SableGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void linksFollowAssemblyAndRotatedDisassembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(origin, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(origin.east(), DisplayFeature.DISPLAY.get().defaultBlockState());
        level.setBlockAndUpdate(origin.east(2), AudioFeature.SPEAKER.get().defaultBlockState());
        BlockPos ground = origin.south(3);
        level.setBlockAndUpdate(ground, AudioFeature.SPEAKER.get().defaultBlockState());
        VideoPlayerBlock.Entity player = (VideoPlayerBlock.Entity) level.getBlockEntity(origin);
        ItemStack disc = new ItemStack(VideoFeature.BLU_RAY.get());
        disc.set(VideoFeature.PLAYLIST.get(), List.of("https://example.com/test.mp4"));
        player.insert(disc, null);
        CompoundTag legacy = new CompoundTag();
        legacy.put("Source", NbtUtils.writeBlockPos(origin));
        ((DisplayBlock.Entity) level.getBlockEntity(origin.east())).loadWithComponents(legacy, level.registryAccess());
        ((DisplayBlock.Entity) level.getBlockEntity(origin.east())).followSource(level);
        ((SpeakerBlock.Entity) level.getBlockEntity(origin.east(2))).configure(level, origin, 1, Streams.Channel.LEFT);
        ((SpeakerBlock.Entity) level.getBlockEntity(ground)).configure(level, origin, 1, Streams.Channel.RIGHT);
        UUID id = BlockIdentity.of(level, origin);
        ItemStack remote = new ItemStack(VideoFeature.REMOTE.get());
        remote.set(VideoFeature.REMOTE_TARGET.get(), GlobalPos.of(level.dimension(), origin));
        remote.set(VideoFeature.REMOTE_SOURCE.get(), id);
        // Nobody listens here: its track plays once it has waited as long as a track waits for its listeners.
        long played = level.getGameTime() + Streams.MAX_LOAD_TICKS;
        Streams.tick(player, player.transport().clock(), played);
        BlockEntity groundBefore = level.getBlockEntity(ground);
        CompoundTag groundSaved = groundBefore.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(ground);
        BlockPos groundSource = origin.north();
        level.setBlockAndUpdate(groundSource, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(origin.east(3), AudioFeature.SPEAKER.get().defaultBlockState());
        ((SpeakerBlock.Entity) level.getBlockEntity(origin.east(3))).configure(level, groundSource, 1, Streams.Channel.MIX);

        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin,
                List.of(origin, origin.east(), origin.east(2), origin.east(3)),
                new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 3, origin.getY(), origin.getZ()));
        BlockPos plot = ship.getPlot().getCenterBlock();
        level.setBlockEntity(BlockEntity.loadStatic(ground, groundBefore.getBlockState(), groundSaved, level.registryAccess()));
        checkLinks(helper, plot, plot.east(), plot.east(2), ground, remote, id);
        SpeakerBlock.Entity aboardSpeaker = (SpeakerBlock.Entity) level.getBlockEntity(plot.east(3));
        aboardSpeaker.followSource(level);
        helper.assertTrue(aboardSpeaker.source().orElseThrow().equals(groundSource),
                "a ground source is not moved with its speaker");
        VideoPlayerBlock.Entity moved = (VideoPlayerBlock.Entity) level.getBlockEntity(plot);
        helper.assertTrue(moved.playlist().equals(player.playlist()), "disc and playlist survive assembly");
        Streams.tick(moved, moved.transport().clock(), played + 1);
        helper.assertTrue(Streams.loadId(moved) == Streams.loadId(player) && Streams.loaded(moved)
                && moved.transport().clock().position(played + 21) == 20, "playback clock follows the player");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(5)).isEmpty(),
                "assembly must not drop or duplicate the disc");

        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(10, 0, 15);
        Vec3 global = SableCompat.toWorld(level, plot, plot.east(2).getCenter());
        Vec3 local = SableCompat.toBlockSpace(level, plot, global);
        helper.assertTrue(local.distanceTo(plot.east(2).getCenter()) < 1e-6, "sound transforms follow a rotated ship");
        helper.assertTrue(Math.abs(SableCompat.distance(level, plot, ground)
                - SableCompat.toWorld(level, plot.getCenter()).distanceTo(ground.getCenter())) < 1e-6,
                "link range measures world distance");

        BlockPos destination = origin.south(8);
        var transform = new SubLevelAssemblyHelper.AssemblyTransform(plot, destination, 1, Rotation.COUNTERCLOCKWISE_90, level);
        SubLevelAssemblyHelper.moveBlocks(level, transform, List.of(plot, plot.east(), plot.east(2), plot.east(3)));
        var container = SubLevelContainer.getContainer(level);
        var plotPos = ship.getPlot().plotPos;
        var containerOrigin = container.getOrigin();
        container.removeSubLevel(plotPos.x - containerOrigin.x, plotPos.z - containerOrigin.y, SubLevelRemovalReason.REMOVED);
        BlockPos display = transform.apply(plot.east());
        BlockPos speaker = transform.apply(plot.east(2));
        checkLinks(helper, destination, display, speaker, ground, remote, id);
        helper.assertTrue(((VideoPlayerBlock.Entity) level.getBlockEntity(destination)).playlist().equals(player.playlist()),
                "disc survives rotated disassembly");

        BlockEntity before = level.getBlockEntity(destination);
        CompoundTag saved = before.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(destination);
        BlockEntity reloaded = BlockEntity.loadStatic(destination, before.getBlockState(), saved, level.registryAccess());
        level.setBlockEntity(reloaded);
        checkLinks(helper, destination, display, speaker, ground, remote, id);
        level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(destination, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        helper.assertTrue(BlockIdentity.find(level, id, destination) == null, "replacement players do not inherit links");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void equalizerFadersWorkOnATurnedShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(origin, VideoFeature.EQUALIZER.get().defaultBlockState());
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin, List.of(origin),
                new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ()));
        BlockPos plot = ship.getPlot().getCenterBlock();
        ship.logicalPose().orientation().rotationY(Math.PI / 2).rotateX(0.3);
        ship.logicalPose().position().add(10, 0, 15);

        // Looking down at a spot on its top from above and in front of it, as the ship has it.
        Vec3 spot = new Vec3(plot.getX() + 0.2, plot.getY() + 1, plot.getZ() + 0.7);
        Vec3 eye = SableCompat.toWorld(level, plot, spot.add(0.3, 1.5, 1.2));
        Vec3 look = SableCompat.toWorld(level, plot, spot).subtract(eye).normalize();
        // As ControlDrag#look takes the look into the equalizer's block space.
        Vec3 from = SableCompat.toBlockSpace(level, plot, eye);
        Vec3 aimed = EqualizerBlock.aim(plot, from, SableCompat.toBlockSpace(level, plot, eye.add(look)).subtract(from));
        helper.assertTrue(aimed != null && aimed.distanceTo(spot) < 1e-6,
                "the crosshair finds the spot on a turned ship, got " + aimed);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(eye);
        EqualizerBlock.setEqualizer(player, new EqualizerBlock.SetEqualizer(plot, 2, 6));
        EqualizerBlock.Entity equalizer = (EqualizerBlock.Entity) level.getBlockEntity(plot);
        helper.assertTrue(equalizer.gain(2) == 6, "a player beside the ship reaches its equalizer");
        player.setPos(eye.add(0, 0, 30));
        EqualizerBlock.setEqualizer(player, new EqualizerBlock.SetEqualizer(plot, 2, -6));
        helper.assertTrue(equalizer.gain(2) == 6, "a player far from the ship does not");
        helper.succeed();
    }

    private static void checkLinks(GameTestHelper helper, BlockPos source, BlockPos display, BlockPos speaker,
                                   BlockPos ground, ItemStack remote, UUID id) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(source.equals(BlockIdentity.find(level, id, null)), "source identity follows its block");
        DisplayBlock.Entity screen = (DisplayBlock.Entity) level.getBlockEntity(display);
        SpeakerBlock.Entity onboard = (SpeakerBlock.Entity) level.getBlockEntity(speaker);
        SpeakerBlock.Entity groundSpeaker = (SpeakerBlock.Entity) level.getBlockEntity(ground);
        screen.followSource(level);
        onboard.followSource(level);
        groundSpeaker.followSource(level);
        helper.assertTrue(screen.source().orElseThrow().equals(source), "display keeps its player");
        helper.assertTrue(onboard.source().orElseThrow().equals(source), "onboard speaker keeps its player");
        helper.assertTrue(groundSpeaker.source().orElseThrow().equals(source),
                "ground speaker follows the moved player: expected " + source + ", got " + groundSpeaker.source()
                        + ", level " + groundSpeaker.getLevel());
        helper.assertTrue(RemoteItem.target(level, remote).pos().equals(source), "remote follows its player");
    }
}
