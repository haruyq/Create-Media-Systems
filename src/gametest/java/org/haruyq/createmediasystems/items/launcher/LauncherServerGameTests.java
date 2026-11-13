package org.haruyq.createmediasystems.items.launcher;

import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real dedicated-side checks, compiled only with -PlauncherServerTest. */
@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LauncherServerGameTests {
    private static DiscProjectile playing(GameTestHelper helper, String name, long positionTicks, double duration) {
        var level = helper.getLevel();
        ItemStack item = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        item.set(AudioFeature.URL.get(), "https://example.invalid/" + name + ".ogg");
        item.set(LauncherFeature.DISC_PLAYBACK.get(), DiscPlayback.prepare(level, item).at(positionTicks));
        var disc = new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(), level);
        disc.setItem(item);
        disc.setNoGravity(true);
        var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        disc.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        disc.musicStartedAgo(positionTicks);
        disc.firedAgo(0);
        disc.stuckHere();
        disc.trackLasts(duration);
        level.addFreshEntity(disc);
        return disc;
    }

    @GameTest(batch = "launcher", template="empty")
    public static void separateCdsKeepServerPositionsThroughSaveAndReload(GameTestHelper helper) {
        var first = playing(helper, "same-song", 400, 254);
        var second = playing(helper, "same-song", 60, 254);
        helper.assertTrue(first.playbackPositionTicks() == 400 && second.playbackPositionTicks() == 60,
                "The server holds a separate playback clock for each CD of the same song");
        ItemStack returned = first.playbackItem();
        var cell = new ItemStack(LauncherFeature.cell(DiscCellTier.COPPER));
        DiscCellItem.setMaster(cell, returned);
        ChargeableItem.setEnergy(cell, DiscCellTier.COPPER.capacity());
        var launcher = new ItemStack(LauncherFeature.DISC_LAUNCHER.get());
        LauncherMagazine.setCell(launcher, cell);
        var round = LauncherMagazine.pop(launcher);
        var owner = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        var firedAgain = new DiscProjectile(helper.getLevel(), owner, round);
        helper.assertTrue(firedAgain.playbackPositionTicks() == 0, "A new projected CD has its own new playback");
        helper.assertTrue(second.playbackPositionTicks() == 60, "A later shot does not change the other CD's position");
        helper.assertTrue(first.playbackPositionTicks() == 400
                && ItemStack.matches(returned, DiscCellItem.master(LauncherMagazine.cell(launcher))),
                "Projection does not change an existing CD or the master inside the cell");
        CompoundTag tag = new CompoundTag();
        first.save(tag);
        Entity restored = EntityType.loadEntityRecursive(tag, helper.getLevel(), e -> e);
        helper.assertTrue(restored instanceof DiscProjectile && ((DiscProjectile) restored).playbackPositionTicks() == 400,
                "Entity save/reload keeps the server playback position");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void aLongSongIsNotCutOffAtThreeMinutes(GameTestHelper helper) {
        var disc = playing(helper, "long-song", 190 * 20, 254);
        disc.firedAgo(190 * 20);
        disc.tick();
        helper.assertTrue(!disc.isRemoved(), "A 254-second song still plays at 190 seconds");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void completedProjectionLeavesNoItemAndNoPlayingEntity(GameTestHelper helper) {
        var disc = playing(helper, "completed", 40, 2);
        var bounds = disc.getBoundingBox().inflate(2);
        disc.tick();
        disc.tick();
        helper.assertTrue(disc.isRemoved(), "The playing entity is removed when the song ends");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds);
        helper.assertTrue(drops.isEmpty(), "An energy projection must never become an obtainable item");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void expiryDoesNotRequireTheProjectileMovementToTick(GameTestHelper helper) {
        var disc = playing(helper, "non-ticking", 60, 2);
        disc.tickPlayback();
        helper.assertTrue(disc.isRemoved() && disc.tickCount == 0,
                "The server lifecycle recovers an expired CD without a movement tick");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void changingTheSongResetsOnlyThatCdsPosition(GameTestHelper helper) {
        var disc = playing(helper, "old-song", 400, 254);
        var changed = disc.playbackItem();
        var previous = changed.get(LauncherFeature.DISC_PLAYBACK.get());
        changed.set(AudioFeature.URL.get(), "https://example.invalid/new-song.ogg");
        var next = DiscPlayback.prepare(helper.getLevel(), changed);
        helper.assertTrue(next.positionTicks() == 0 && next.id().equals(previous.id()),
                "Replacing the source resets the track, without replacing the CD");
        helper.assertTrue(disc.playbackPositionTicks() == 400, "Editing a copy does not reset the active CD");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void projectileRegistryAndMetadataSaveLoad(GameTestHelper helper) {
        helper.assertTrue(LauncherFeature.DISC_LAUNCHER.get() instanceof DiscLauncherItem,"Launcher registry bound");
        var level=helper.getLevel();
        ItemStack original=new ItemStack(AudioFeature.CUSTOM_DISC.get());
        original.set(AudioFeature.URL.get(),"http://example.invalid/saved-disc.ogg");
        var disc=new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(),level);
        disc.setItem(original);disc.setNoGravity(true);
        var pos=helper.absolutePos(new BlockPos(1,2,1));disc.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        CompoundTag tag=new CompoundTag();disc.save(tag);
        Entity restored=EntityType.loadEntityRecursive(tag,level,e->e);
        helper.assertTrue(restored instanceof DiscProjectile,"Projectile deserialized on dedicated server");
        helper.assertTrue(ItemStack.matches(original,((DiscProjectile)restored).getItem()),"Original item components preserved");
        helper.assertTrue(restored.isNoGravity(),"Flight state preserved");
        helper.succeed();
    }

    @GameTest(batch = "launcher", template="empty")
    public static void expiredProjectionDoesNotDropADuplicatedMaster(GameTestHelper helper) {
        var level=helper.getLevel();
        ItemStack original=new ItemStack(AudioFeature.CUSTOM_DISC.get());
        original.set(AudioFeature.URL.get(),"http://example.invalid/expiry-disc.ogg");
        var disc=new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(),level);
        disc.setItem(original);disc.setNoGravity(true);
        var pos=helper.absolutePos(new BlockPos(1,2,1));disc.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        level.addFreshEntity(disc);
        disc.musicStartedAgo(3600);disc.firedAgo(3600);
        disc.tick();
        helper.assertTrue(disc.isRemoved(),"Expired audio emitter removed");
        helper.runAfterDelay(2,()-> {
            var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3));
            helper.assertTrue(drops.isEmpty(), "Expiry cannot duplicate the cell's original media disc");
            helper.succeed();
        });
    }
}
