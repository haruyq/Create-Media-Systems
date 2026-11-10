package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.CreateMediaSystems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscAirCleanupGameTests {
    private static DiscProjectile flying(GameTestHelper h) {
        var d=new DiscProjectile(CreateMediaSystems.DISC_PROJECTILE.get(),h.getLevel());
        d.setItem(new ItemStack(Items.MUSIC_DISC_CAT));d.setNoGravity(true);
        d.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(1,40,1))));d.setDeltaMovement(0,0,2);
        d.tickPlayback();return d;
    }
    @GameTest(template="empty")
    public static void missedShotsExpireEvenWithoutMovementTicksAndAfterSaveLoad(GameTestHelper h) {
        var d=flying(h);var tag=new CompoundTag();d.save(tag);
        tag.putLong("CmsDiscSpawnedAt",h.getLevel().getGameTime()-100);d.load(tag);d.tickPlayback();
        h.assertTrue(d.isRemoved() && d.tickCount==0 && d.getPickResult().isEmpty(),"A missed shot cannot hover until its song ends, even outside simulation distance");h.succeed();
    }
    @GameTest(template="empty")
    public static void stalledShotsAndLegacyOrphansAreRemoved(GameTestHelper h) {
        var d=flying(h);var tag=new CompoundTag();d.save(tag);
        tag.putLong("CmsDiscSpawnedAt",h.getLevel().getGameTime()-5);d.load(tag);d.setDeltaMovement(Vec3.ZERO);d.tickPlayback();
        h.assertTrue(d.isRemoved(),"A stopped flying projection cannot remain in the air");
        var old=flying(h);var oldTag=new CompoundTag();old.save(oldTag);oldTag.putBoolean("CmsDiscEmbedded",true);old.load(oldTag);old.tickPlayback();
        h.assertTrue(old.isRemoved(),"Unsupported embedded discs from older saves are cleaned up too");h.succeed();
    }
    @GameTest(template="empty")
    public static void aBrokenSupportRemovesItsDiscButSolidSupportSurvivesReload(GameTestHelper h) {
        var d=flying(h);BlockPos block=BlockPos.containing(d.position());h.getLevel().setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());
        Vec3 contact=Vec3.atLowerCornerOf(block).add(.5,.5,0);d.setPos(contact.add(0,0,-1));d.setDeltaMovement(0,0,2);
        d.onHit(new BlockHitResult(contact,Direction.NORTH,block,false));
        h.assertTrue(d.embedded() && !d.attached(),"The actual swept block collision embeds the disc");
        var tag=new CompoundTag();d.save(tag);var restored=flying(h);restored.load(tag);restored.tickPlayback();
        h.assertTrue(!restored.isRemoved() && restored.embedded(),"A real block anchor and track survive NBT reload");
        h.getLevel().removeBlock(block,false);restored.tickPlayback();
        h.assertTrue(restored.isRemoved(),"Breaking the supporting block removes the emitter without leaving a floating disc");h.succeed();
    }
    @GameTest(template="empty")
    public static void nonLivingImpactsAndDiscardedTargetsCannotLeaveAnEmitter(GameTestHelper h) {
        var d=flying(h);var boat=EntityType.BOAT.create(h.getLevel());boat.setPos(d.position());
        d.onHitEntity(new EntityHitResult(boat,boat.position()));
        h.assertTrue(d.isRemoved(),"A boat collision cannot create a static unsupported emitter");
        var pinned=flying(h);var mob=EntityType.IRON_GOLEM.create(h.getLevel());mob.setPos(pinned.position());h.getLevel().addFreshEntity(mob);
        pinned.onHitEntity(new EntityHitResult(mob,mob.position().add(-.8,1,0)));h.assertTrue(pinned.attached(),"A living target still carries the blade");
        mob.discard();pinned.tick();h.assertTrue(pinned.isRemoved(),"Discarding a target cleans up its attached emitter");h.succeed();
    }
}
