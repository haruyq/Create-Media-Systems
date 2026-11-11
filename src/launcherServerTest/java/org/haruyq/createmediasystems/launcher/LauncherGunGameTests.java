package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SmithingMenu;
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

        var launcher = gun();
        player.getInventory().setItem(0, launcher);
        player.startUsingItem(InteractionHand.MAIN_HAND);
        h.assertTrue(LauncherAimLock.direction(player, launcher, forward).equals(forward),
                "The bare launcher leaves the near miss uncorrected");
        launcher.set(LauncherFeature.LAUNCHER_AIM_MODULE.get(), true);
        h.assertTrue(DiscLauncherItem.fire(player, 0, 0), "The aimed trigger fires");
        var fired = level.getEntitiesOfClass(DiscProjectile.class, player.getBoundingBox().inflate(2)).getFirst();
        Vec3 assisted = fired.getDeltaMovement().normalize();
        h.assertTrue(assisted.dot(forward) >= Math.cos(Math.toRadians(6)) - 1e-6,
                "The trigger nudges the projectile within the assist cone");
        for (int tick = 0; tick < 8 && !fired.embedded(); tick++) fired.tick();
        h.assertTrue(fired.attachmentTarget() == target, "The assisted shot hits and attaches to the target");
        fired.discard();

        var disc = new ItemStack(Items.MUSIC_DISC_CAT);
        Vec3 headAim = target.getEyePosition().subtract(eye).normalize();
        h.assertTrue(LauncherAimLock.direction(player, launcher, headAim).distanceTo(headAim) < 1e-7,
                "An aimed headshot keeps its exact direction");
        var hipfire = DiscProjectile.forShot(player, disc, 0, forward, false);
        Vec3 hipfireEnd = eye.add(hipfire.getDeltaMovement().normalize().scale(32));
        h.assertTrue(DiscBallistics.trace(hipfire, eye, hipfireEnd, e -> e == target).getType() == HitResult.Type.MISS,
                "Hip fire leaves the near miss uncorrected");

        BlockPos cover = BlockPos.containing(eye.add(.4, 0, 8));
        level.setBlockAndUpdate(cover, Blocks.STONE.defaultBlockState());
        h.assertTrue(LauncherAimLock.direction(player, launcher, forward).distanceTo(forward) < 1e-7,
                "Cover blocks aim assistance");
        level.removeBlock(cover, false);
        target.discard();
        h.succeed();
    }

    @GameTest(template="empty")
    public static void lockKeepsAnOrdinaryTargetUntilThePlayerLowersTheLauncher(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 base = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 67, 1)));
        player.setPos(base);
        var launcher = gun();
        launcher.set(LauncherFeature.LAUNCHER_AIM_MODULE.get(), true);
        player.getInventory().setItem(0, launcher);
        player.startUsingItem(InteractionHand.MAIN_HAND);
        var first = EntityType.IRON_GOLEM.create(level);
        first.setNoAi(true);
        first.setPos(base.add(1, 0, 17));
        level.getChunkAt(first.blockPosition());
        level.addFreshEntity(first);
        Vec3 eye = player.getEyePosition();
        LauncherAimLock.direction(player, launcher, new Vec3(0, 0, 1));

        var second = EntityType.IRON_GOLEM.create(level);
        second.setNoAi(true);
        second.setPos(base.add(-3, 0, 16));
        level.getChunkAt(second.blockPosition());
        level.addFreshEntity(second);
        Vec3 towardSecond = second.getBoundingBox().getCenter().subtract(eye).normalize();
        Vec3 retained = LauncherAimLock.direction(player, launcher, towardSecond);
        var retainedHit = DiscBallistics.trace(player, eye, eye.add(retained.scale(30)), e -> e == first || e == second);
        h.assertTrue(retainedHit instanceof EntityHitResult hit && hit.getEntity() == first,
                "The lock keeps tracking the first golem while a second crosses the reticle");

        player.stopUsingItem();
        LauncherAimLock.tick(player);
        player.startUsingItem(InteractionHand.MAIN_HAND);
        Vec3 reacquired = LauncherAimLock.direction(player, launcher, towardSecond);
        h.assertTrue(reacquired.distanceTo(towardSecond) < 1e-7, "Lowering the launcher lets the player pick a new target");
        first.discard();
        second.discard();
        h.succeed();
    }

    @GameTest(template="empty")
    public static void aimModuleCraftsAndSmithingKeepsTheLoadedCell(GameTestHelper h) {
        var level = h.getLevel();
        var mechanical = level.getRecipeManager().byKey(CreateMediaSystems.id("launcher_aim_module")).orElseThrow().value();
        h.assertTrue(mechanical instanceof MechanicalCraftingRecipe recipe
                        && recipe.getWidth() == 5 && recipe.getHeight() == 5
                        && recipe.getResultItem(level.registryAccess()).is(LauncherFeature.LAUNCHER_AIM_MODULE_ITEM.get()),
                "The registered module recipe uses the five-by-five Mechanical Crafter");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos table = h.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(table, Blocks.SMITHING_TABLE.defaultBlockState());
        var menu = new SmithingMenu(1, player.getInventory(), ContainerLevelAccess.create(level, table));
        var launcher = gun();
        launcher.set(DataComponents.CUSTOM_NAME, Component.literal("Loaded launcher"));
        var loaded = LauncherMagazine.cell(launcher);
        menu.getSlot(0).set(new ItemStack(LauncherFeature.LAUNCHER_AIM_MODULE_ITEM.get()));
        menu.getSlot(1).set(launcher);
        menu.getSlot(2).set(AllItems.STURDY_SHEET.asStack());
        menu.clicked(3, 0, ClickType.PICKUP, player);
        ItemStack installed = menu.getCarried();
        h.assertTrue(DiscLauncherItem.hasAimModule(installed) && installed.getHoverName().getString().equals("Loaded launcher")
                        && ItemStack.matches(loaded, LauncherMagazine.cell(installed)),
                "Taking the smithing result mounts the module while preserving the renamed launcher and its loaded cell");
        h.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty()
                        && menu.getSlot(2).getItem().isEmpty(), "Smithing consumes the three inputs");
        var restored = ItemStack.parse(level.registryAccess(), installed.save(level.registryAccess())).orElseThrow();
        menu.getSlot(0).set(new ItemStack(LauncherFeature.LAUNCHER_AIM_MODULE_ITEM.get()));
        menu.getSlot(1).set(restored);
        menu.getSlot(2).set(AllItems.STURDY_SHEET.asStack());
        h.assertTrue(DiscLauncherItem.hasAimModule(restored) && menu.getSlot(3).getItem().isEmpty(),
                "The mount persists through a save and accepts only one module");
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
