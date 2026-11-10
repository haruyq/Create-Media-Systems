package org.haruyq.createmediasystems.launcher;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID) @PrefixGameTestTemplate(false)
public final class DiscDragonGameTests {
    private static EnderDragon dragon(GameTestHelper h){
        // A dragon spans neighbouring small test structures; shoot above their glass/block fixtures.
        var d=EntityType.ENDER_DRAGON.create(h.getLevel());var b=Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(1,1,1))).add(0,40,0);
        d.setPos(b);d.setNoAi(true);h.getLevel().addFreshEntity(d);
        var parts=d.getSubEntities();for(int i=0;i<parts.length;i++)parts[i].setPos(b.add(i*6,2,0));return d;
    }
    private static DiscProjectile disc(GameTestHelper h){var d=new DiscProjectile(h.getLevel(),h.makeMockPlayer(GameType.SURVIVAL),new ItemStack(Items.MUSIC_DISC_CAT));d.setDeltaMovement(1,0,0);return d;}
    @GameTest(template="empty") public static void actualSweptHeadHitAttachesToTheParentAndMovesWithTheAnimatedHead(GameTestHelper h){
        var dragon=dragon(h);var part=dragon.head;var shot=disc(h);var start=part.position().add(-2,.5,0);shot.setPos(start);
        var hit=DiscBallistics.trace(shot,start,start.add(4,0,0),e->e.canBeHitByProjectile() && (e==dragon || e instanceof net.minecraft.world.entity.boss.EnderDragonPart p && p.parentMob==dragon));
        h.assertTrue(hit instanceof EntityHitResult e && e.getEntity()==part,"The actual multipart head must win the swept contact; hit="+hit.getType()+", contact="+(hit instanceof EntityHitResult e?e.getEntity():hit.getLocation()));
        shot.onHitEntity((EntityHitResult)hit);h.assertTrue(shot.attached() && shot.attachmentPart()==0 && shot.attachmentTargetUUID().orElseThrow().equals(dragon.getUUID()),"The parent UUID and head index must be saved");
        h.assertTrue(dragon.getHealth()==176,"Head damage must use the real dragon part and real parent HP");
        var before=shot.position();dragon.setPos(dragon.position().add(4,3,7));dragon.flapTime+=.25f;shot.tick();
        h.assertTrue(shot.position().distanceTo(before)>5 && shot.position().distanceTo(shot.attachmentPosition(1))<1e-6,"An independently moving head cannot leave the blade floating at its old contact");dragon.discard();h.succeed();
    }
    @GameTest(template="empty") public static void wingsAndTailUseTheirOwnPartFramesRatherThanTheParentBody(GameTestHelper h){
        var dragon=dragon(h);
        for(int index:new int[]{5,6,7}){
            var part=dragon.getSubEntities()[index];var shot=disc(h);shot.onHitEntity(new EntityHitResult(part,part.position().add(-part.getBbWidth()/2,.5,0)));
            var before=shot.position();dragon.setPos(dragon.position().add(0,4,5));dragon.flapTime+=.25f;shot.tick();
            h.assertTrue(shot.attachmentPart()==index && shot.position().distanceTo(before)>4,"Each wing/tail blade must follow the exact struck part");
        }
        dragon.discard();h.succeed();
    }
    @GameTest(template="empty") public static void savingAndRebindingDragonPartsPreservesTheDiscUuidAndMusicClock(GameTestHelper h){
        var dragon=dragon(h);var part=dragon.getSubEntities()[3];var shot=disc(h);shot.onHitEntity(new EntityHitResult(part,part.position().add(-.8,.5,0)));
        var tag=new CompoundTag();shot.save(tag);tag.putLong("CmsDiscMusicStart",h.getLevel().getGameTime()-200);
        var loaded=new DiscProjectile(CreateMediaSystems.DISC_PROJECTILE.get(),h.getLevel());loaded.load(tag);dragon.setPos(dragon.position().add(5,2,4));loaded.tick();
        h.assertTrue(loaded.getUUID().equals(shot.getUUID()) && loaded.attachmentPart()==3 && loaded.attachmentTarget()==dragon && loaded.elapsedMusicSeconds()>=10,"NBT must rebind the same parent UUID and distinct tail index without restarting audio");
        dragon.setHealth(0);loaded.tick();h.assertTrue(loaded.isRemoved(),"A dead dragon cannot leave an orphan blade");dragon.discard();h.succeed();
    }
    @GameTest(template="empty") public static void coarseColliderImpactsAreProjectedOntoTheActualVisibleBodySurface(GameTestHelper h){
        var dragon=dragon(h);var part=dragon.getSubEntities()[2];var shot=disc(h);shot.onHitEntity(new EntityHitResult(part,part.position().add(-2.56,1.5,0)));
        var bone=DiscDragonAttachment.bone(dragon,shot.attachmentBone(),1);var local=bone.local(shot.position());
        h.assertTrue(shot.attachmentBone()==6 && bone.boxes().stream().anyMatch(b->b.inflate(.1).contains(local)),"The blade is on the visible 24-pixel trunk, not the oversized five-block collider");
        var before=shot.position();dragon.flapTime+=.25f;shot.tick();var changed=DiscDragonAttachment.bone(dragon,shot.attachmentBone(),1);
        h.assertTrue(shot.position().distanceTo(before)>.03,"The blade follows visible breathing/flapping, not just the parent's world translation");
        h.assertTrue(changed.boxes().stream().anyMatch(b->b.inflate(.1).contains(changed.local(shot.position()))),"Animation preserves the same bone-local skin contact");dragon.discard();h.succeed();
    }
    @GameTest(template="empty") public static void emptySpaceInsideTheLargeParentBoundingBoxIsNotAnImpact(GameTestHelper h){
        var dragon=dragon(h);var shot=disc(h);var start=dragon.position().add(-6,7,0);shot.setPos(start);
        var result=DiscBallistics.trace(shot,start,start.add(12,0,0),Entity::canBeHitByProjectile);
        h.assertTrue(result.getType()==HitResult.Type.MISS,"Only the real multipart boxes count, never empty space in the huge parent AABB");dragon.discard();h.succeed();
    }
    @GameTest(template="empty") public static void aSharedHeadNeckBoundaryCannotDependOnThePartMapIterationOrder(GameTestHelper h){
        var dragon=dragon(h);dragon.getSubEntities()[1].setPos(dragon.head.position());var shot=disc(h);var start=dragon.head.position().add(0,-2,0);shot.setPos(start);
        var hit=DiscBallistics.trace(shot,start,start.add(0,4,0),e->e.canBeHitByProjectile() && e instanceof net.minecraft.world.entity.boss.EnderDragonPart p && p.parentMob==dragon);
        h.assertTrue(hit instanceof EntityHitResult e && e.getEntity()==dragon.head,"A coincident contact must pick the real head consistently");dragon.discard();h.succeed();
    }
}
