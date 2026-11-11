package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LauncherGunGameTests {
    private static ItemStack gun() {
        var cell=new ItemStack(LauncherFeature.cell(DiscCellTier.COPPER));
        DiscCellItem.setMaster(cell,new ItemStack(Items.MUSIC_DISC_CAT));ChargeableItem.setEnergy(cell,24000);
        var gun=new ItemStack(LauncherFeature.DISC_LAUNCHER.get());LauncherMagazine.setCell(gun,cell);return gun;
    }
    private static DiscProjectile shot(GameTestHelper h,net.minecraft.world.entity.player.Player player) {
        return new DiscProjectile(h.getLevel(),player,new ItemStack(Items.MUSIC_DISC_CAT));
    }
    @GameTest(template="empty")
    public static void rightClickOnlyAimsAndTheShotFollowsTheExactEyeLine(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var gun=gun();p.getInventory().setItem(0,gun);
        var point=h.absolutePos(new BlockPos(1,2,1));p.setPos(point.getX()+.5,point.getY(),point.getZ()+.5);
        p.setDeltaMovement(.3,0,.2);
        gun.getItem().use(h.getLevel(),p,InteractionHand.MAIN_HAND);
        h.assertTrue(p.isUsingItem() && LauncherMagazine.count(gun)==24 && !LauncherMagazine.reloading(gun),"RMB only enters ADS without spending energy or swapping cells");
        h.assertTrue(DiscLauncherItem.fire(p,0,0),"Server accepts the first trigger shot");
        var rounds=h.getLevel().getEntitiesOfClass(DiscProjectile.class,p.getBoundingBox().inflate(2));
        h.assertTrue(rounds.size()==1,"One projectile is spawned");var d=rounds.getFirst();
        h.assertTrue(d.position().distanceTo(p.getEyePosition())<1e-7 && Math.abs(d.getDeltaMovement().x)<1e-7
                && Math.abs(d.getDeltaMovement().y)<1e-7 && Math.abs(d.getDeltaMovement().z-5.2)<1e-5,
                "ADS has no random spread, lowered muzzle origin or inherited strafe velocity");
        h.assertTrue(!DiscLauncherItem.fire(p,0,0) && LauncherMagazine.count(gun)==23,"Duplicate requests cannot exceed server cadence");
        h.assertTrue(!DiscLauncherItem.fire(p,Float.NaN,0),"Invalid look data cannot fire");d.discard();h.succeed();
    }

    @GameTest(template="empty")
    public static void aimAssistCorrectsAVisibleNearMissWithoutTurningThroughCover(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 base = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 12, 1)));
        player.setPos(base);
        var target = EntityType.IRON_GOLEM.create(level);
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPos(base.add(.92, 0, 16));
        level.getChunkAt(target.blockPosition());
        level.addFreshEntity(target);

        Vec3 forward = new Vec3(0, 0, 1);
        Vec3 eye = player.getEyePosition();
        var manual = shot(h, player);
        manual.setPos(eye);
        h.assertTrue(DiscBallistics.trace(manual, eye, eye.add(forward.scale(32)), e -> e == target).getType()
                == HitResult.Type.MISS, "The original reticle ray misses the target");

        player.getInventory().setItem(0, gun());
        player.startUsingItem(InteractionHand.MAIN_HAND);
        h.assertTrue(DiscLauncherItem.fire(player, 0, 0), "The aimed trigger fires");
        var fired = level.getEntitiesOfClass(DiscProjectile.class, player.getBoundingBox().inflate(2)).getFirst();
        Vec3 assisted = fired.getDeltaMovement().normalize();
        h.assertTrue(assisted.dot(forward) >= Math.cos(Math.toRadians(4)) - 1e-6,
                "The trigger nudges the projectile within the assist cone");
        for (int tick = 0; tick < 8 && !fired.embedded(); tick++) fired.tick();
        h.assertTrue(fired.attachmentTarget() == target, "The assisted shot hits and attaches to the target");
        fired.discard();

        var disc = new ItemStack(Items.MUSIC_DISC_CAT);
        Vec3 headAim = target.getEyePosition().subtract(eye).normalize();
        var headshot = DiscProjectile.forShot(player, disc, 0, headAim, true);
        h.assertTrue(headshot.getDeltaMovement().normalize().distanceTo(headAim) < 1e-7,
                "An aimed headshot keeps its exact direction");
        var hipfire = DiscProjectile.forShot(player, disc, 0, forward, false);
        Vec3 hipfireEnd = eye.add(hipfire.getDeltaMovement().normalize().scale(32));
        h.assertTrue(DiscBallistics.trace(hipfire, eye, hipfireEnd, e -> e == target).getType() == HitResult.Type.MISS,
                "Hip fire leaves the near miss uncorrected");

        BlockPos cover = BlockPos.containing(eye.add(.4, 0, 8));
        level.setBlockAndUpdate(cover, Blocks.STONE.defaultBlockState());
        var covered = DiscProjectile.forShot(player, disc, 0, forward, true);
        h.assertTrue(covered.getDeltaMovement().normalize().distanceTo(forward) < 1e-7,
                "Cover blocks aim assistance");
        level.removeBlock(cover, false);
        target.discard();
        h.succeed();
    }
    @GameTest(template="empty")
    public static void everyRapidBodyHitDealsFullDamageWithoutChangingOtherWeaponsInvulnerability(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var target=new IronGolem(EntityType.IRON_GOLEM,h.getLevel());
        target.setPos(h.absolutePos(new BlockPos(1,1,1)).getCenter());
        for(int n=0;n<3;n++)shot(h,p).onHitEntity(new EntityHitResult(target,target.position().add(0,1,0)));
        h.assertTrue(Math.abs(target.getHealth()-64)<.001,"Three consecutive 12-damage disc hits must all apply, even inside normal hurt immunity");
        h.assertTrue(target.getDeltaMovement().lengthSqr()<1e-8,"Disc rounds do not knock a target away between rapid hits");
        h.assertTrue(!target.hurt(h.getLevel().damageSources().thrown(shot(h,p),p),12),"Normal thrown weapons still obey vanilla cooldown");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void absorptionAndHealthAreDamagedInOrderDuringARapidBurst(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var target=new IronGolem(EntityType.IRON_GOLEM,h.getLevel());
        target.setPos(h.absolutePos(new BlockPos(1,1,1)).getCenter());target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(60);target.setAbsorptionAmount(60);
        for(int n=0;n<2;n++) {
            var d=shot(h,p);d.setCellTier(DiscCellTier.NETHERITE);
            d.onHitEntity(new EntityHitResult(target,target.position().add(0,1,0)));
        }
        h.assertTrue(target.getAbsorptionAmount()==0 && Math.abs(target.getHealth()-80)<.001,"Two 40-damage rounds remove 60 absorption and 20 real HP");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void headHitsDoubleDamageWhileTorsoAndHeadlessTargetsDoNot(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var target=new IronGolem(EntityType.IRON_GOLEM,h.getLevel());
        target.setPos(h.absolutePos(new BlockPos(1,1,1)).getCenter());
        shot(h,p).onHitEntity(new EntityHitResult(target,target.position().add(0,1,0)));
        shot(h,p).onHitEntity(new EntityHitResult(target,target.getEyePosition()));
        h.assertTrue(Math.abs(target.getHealth()-64)<.001,"Body 12 plus head 24 both apply independently");
        p.setPose(Pose.CROUCHING);p.refreshDimensions();
        h.assertTrue(DiscHitZones.headshot(p,p.getEyePosition()),"Crouching moves the real head volume");
        h.assertTrue(!DiscHitZones.headshot(p,p.position().add(0,.5,0)),"Legs are not a headshot");
        var slime=EntityType.SLIME.create(h.getLevel());
        h.assertTrue(!DiscHitZones.headshot(slime,slime.getEyePosition()),"Headless entities cannot produce invented headshots");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void sweptRayKeepsTheActualContactPointAndCannotShootThroughCover(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var d=shot(h,p);
        Vec3 base=Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(1,1,1))).add(.5,0,.5);
        var target=EntityType.ZOMBIE.create(h.getLevel());target.setNoAi(true);target.setPos(base.add(0,0,3));h.getLevel().addFreshEntity(target);
        Vec3 start=base.add(0,1.65,0),end=start.add(0,0,5.2);d.setPos(start);
        var hit=DiscBallistics.trace(d,start,end,e->e==target);
        h.assertTrue(hit instanceof EntityHitResult entity && entity.getEntity()==target && hit.getLocation().z<target.getZ()
                && Math.abs(hit.getLocation().y-start.y)<1e-7,"A fast segment returns the surface contact, not the entity centre");
        h.assertTrue(DiscHitZones.headshot(target,hit.getLocation()),"The real high contact is a head hit");
        h.setBlock(new BlockPos(1,2,3),Blocks.STONE);
        h.assertTrue(DiscBallistics.trace(d,start,end,e->e==target).getType()==HitResult.Type.BLOCK,"Solid cover wins before the target");
        target.discard();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void aNarrowHeadOnAWideBodyUsesItsOwnActualRayContact(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);var d=shot(h,p);
        var target=new IronGolem(EntityType.IRON_GOLEM,h.getLevel());
        Vec3 base=Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(1,1,1))).add(.5,0,.5);
        target.setPos(base.add(0,0,3));target.setNoAi(true);h.getLevel().addFreshEntity(target);
        Vec3 start=new Vec3(target.getX(),target.getEyeY(),base.z),end=start.add(0,0,5.2);d.setPos(start);
        var hit=DiscBallistics.trace(d,start,end,e->e==target);
        h.assertTrue(hit instanceof EntityHitResult && DiscHitZones.headshot(target,hit.getLocation()),"The chest must not replace a golem head contact with its wider front face");
        d.onHitEntity((EntityHitResult)hit);
        h.assertTrue(Math.abs(target.getHealth()-76)<.001,"The real inset-head ray applies double damage");
        Vec3 side=start.add(.6,0,0);d.setPos(side);
        h.assertTrue(DiscBallistics.trace(d,side,side.add(0,0,5.2),e->e==target).getType()==HitResult.Type.MISS,
                "Empty space beside the narrow head must not become a headshot against the oversized body AABB");
        target.discard();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void heldTriggerUsesServerCadenceAndReleaseStopsAutomaticFire(GameTestHelper h) {
        var p=new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"GunTriggerTest"));
        var gun=gun();p.getInventory().setItem(0,gun);p.setPos(h.absolutePos(new BlockPos(1,2,1)).getCenter());
        DiscLauncherTrigger.input(p,new DiscLauncherTrigger.Input(true,0,0));
        DiscLauncherTrigger.input(p,new DiscLauncherTrigger.Input(true,0,0));
        h.assertTrue(LauncherMagazine.count(gun)==23,"Repeated trigger updates cannot double-fire a tick");
        h.runAfterDelay(4,()->{
            for(int i=0;i<4;i++)p.getCooldowns().tick();
            DiscLauncherTrigger.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            h.assertTrue(LauncherMagazine.count(gun)==22,"Server fires the next round on the four-tick interval");
            DiscLauncherTrigger.input(p,new DiscLauncherTrigger.Input(false,0,0));
            h.runAfterDelay(4,()->{
                for(int i=0;i<4;i++)p.getCooldowns().tick();
                DiscLauncherTrigger.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
                h.assertTrue(LauncherMagazine.count(gun)==22,"Trigger release stops the server even after cooldown expires");
                h.getLevel().getEntitiesOfClass(DiscProjectile.class,p.getBoundingBox().inflate(30)).forEach(net.minecraft.world.entity.Entity::discard);
                h.succeed();
            });
        });
    }
}
