package org.haruyq.createmediasystems.items.launcher;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscAttachmentGameTests {
    private static DiscProjectile disc(GameTestHelper h,net.minecraft.world.entity.player.Player owner) {
        var d=new DiscProjectile(h.getLevel(),owner,new ItemStack(Items.MUSIC_DISC_CAT));d.setDeltaMovement(1,0,0);return d;
    }
    private static Vec3 base(GameTestHelper h){return Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(1,1,1))).add(.5,0,.5);}
    @GameTest(batch = "launcher", template="empty")
    public static void aPlayerCarriesTheSameBladeWhileMovingTurningAndCrouching(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);p.setPos(base(h));
        var d=disc(h,h.makeMockPlayer(GameType.CREATIVE));var uuid=d.getUUID();
        d.onHitEntity(new EntityHitResult(p,p.position().add(-.36,1,0)));
        h.assertTrue(d.attached() && d.attachmentTargetUUID().orElseThrow().equals(p.getUUID()),"The player owns the attachment, not the old world contact point");
        float health=p.getHealth();Vec3 start=d.position();
        p.setPos(p.position().add(5,1,7));p.yBodyRot=90;d.tick();
        h.assertTrue(d.position().distanceTo(d.attachmentPosition(1))<1e-6 && d.position().distanceTo(start)>5,"Moving and turning the player moves the original entity");
        p.setPose(Pose.CROUCHING);p.refreshDimensions();d.tick();
        h.assertTrue(d.getUUID().equals(uuid) && d.embedded() && p.getHealth()==health,"Crouching cannot create a new disc or damage the player repeatedly");
        h.assertTrue(d.getPickResult().isEmpty(),"Attached projections cannot be recovered as items");h.succeed();
    }
    @GameTest(batch = "launcher", template="empty")
    public static void headBladesFollowTheHeadAndAlsoRemainVisibleOnCreativePlayers(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.CREATIVE);p.setPos(base(h));
        p.getAbilities().invulnerable=true;
        var d=disc(h,h.makeMockPlayer(GameType.SURVIVAL));
        d.onHitEntity(new EntityHitResult(p,p.getEyePosition().add(-.26,0,0)));
        h.assertTrue(d.attachedToHead() && d.attached(),"The actual head contact selects the head frame even when damage is denied");
        Vec3 before=d.position();p.yHeadRot=90;p.setXRot(25);d.tick();
        h.assertTrue(d.position().distanceTo(before)>.1 && d.position().distanceTo(p.getEyePosition())<.7,"Head rotation follows the same blade independently of the torso");h.succeed();
    }
    @GameTest(batch = "launcher", template="empty",timeoutTicks=30)
    public static void saveAndReloadKeepUuidClockAndAttachmentWhileTheTargetIsTemporarilyUnloaded(GameTestHelper h) {
        var owner=h.makeMockPlayer(GameType.CREATIVE);var target=EntityType.ZOMBIE.create(h.getLevel());target.setNoAi(true);target.setPos(base(h));h.getLevel().addFreshEntity(target);
        var d=disc(h,owner);d.onHitEntity(new EntityHitResult(target,target.position().add(-.36,1,0)));
        d.musicStartedAgo(200);CompoundTag tag=new CompoundTag();d.save(tag);
        var copy=new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(),h.getLevel());copy.load(tag);copy.tick();
        h.assertTrue(copy.attached() && copy.getUUID().equals(d.getUUID()) && copy.elapsedMusicSeconds()>=10 && copy.attachmentTarget()==target,"NBT keeps the same disc, target UUID and advancing server clock");
        var targetTag=new CompoundTag();target.save(targetTag);target.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);copy.tick();
        h.assertTrue(!copy.isRemoved() && copy.attached() && !copy.soundEnabled(),"A temporarily missing target cannot reset the clock or leave a ghost sound");
        h.runAfterDelay(2,()->{
            var loaded=EntityType.loadEntityRecursive(targetTag,h.getLevel(),e->e);h.getLevel().addFreshEntity(loaded);copy.tick();
            h.assertTrue(copy.attachmentTarget()==loaded && copy.elapsedMusicSeconds()>=10 && copy.soundEnabled(),"A new runtime entity ID rebinds through the saved UUID");
            loaded.discard();h.succeed();
        });
    }
    @GameTest(batch = "launcher", template="empty")
    public static void deathAndTrackEndRemoveAttachedProjectionsWithoutReturningItems(GameTestHelper h) {
        var p=h.makeMockPlayer(GameType.SURVIVAL);p.setPos(base(h));var d=disc(h,h.makeMockPlayer(GameType.CREATIVE));
        d.onHitEntity(new EntityHitResult(p,p.position().add(-.36,1,0)));p.setHealth(0);d.tick();
        h.assertTrue(d.isRemoved(),"The blade cannot transfer from a dead player to their respawn");
        var living=h.makeMockPlayer(GameType.CREATIVE);living.setPos(base(h));var expired=disc(h,living);
        expired.onHitEntity(new EntityHitResult(living,living.position().add(-.36,1,0)));expired.learnLength(1);
        expired.musicStartedAgo(21);var tag=new CompoundTag();expired.save(tag);
        var loaded=new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(),h.getLevel());loaded.load(tag);loaded.tick();
        h.assertTrue(loaded.isRemoved() && loaded.getPickResult().isEmpty(),"Track expiry removes the blade without an obtainable original");h.succeed();
    }
    @GameTest(batch = "launcher", template="empty",timeoutTicks=400)
    public static void aDimensionChangeKeepsTheAttachedDiscIdentityAndPlaybackPosition(GameTestHelper h) {
        var other=h.getLevel().getServer().getLevel(Level.NETHER);h.assertTrue(other!=null,"The actual second dimension must exist");
        // This headless server has no player in the Nether to load or expose the destination's entities.
        boolean destinationWasForced=other.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(0,0));
        other.setChunkForced(0,0,true);
        // Entity-ticking readiness also needs the surrounding FULL chunks on a freshly generated world.
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) other.getChunk(x,z);
        var target=EntityType.IRON_GOLEM.create(h.getLevel());target.setNoAi(true);target.setNoGravity(true);target.setInvulnerable(true);target.setPos(base(h));h.getLevel().addFreshEntity(target);
        var d=disc(h,h.makeMockPlayer(GameType.CREATIVE));d.onHitEntity(new EntityHitResult(target,target.position().add(-.76,1,0)));h.getLevel().addFreshEntity(d);
        var id=d.getUUID();double before=d.elapsedMusicSeconds();
        var moved=target.changeDimension(new DimensionTransition(other,new Vec3(0,80,0),Vec3.ZERO,0,0,DimensionTransition.PLACE_PORTAL_TICKET));
        h.assertTrue(moved!=null,"The target really changed dimension");
        h.succeedWhen(()->{
            // GameTest structures can be outside entity-ticking chunks; drive the fixture's server tick explicitly.
            if (!d.isRemoved()) d.tick();
            var found=other.getEntity(id);
            h.assertTrue(found instanceof DiscProjectile pinned && pinned.attachmentTargetUUID().orElseThrow().equals(moved.getUUID())
                    && pinned.elapsedMusicSeconds()>=before && pinned.position().distanceTo(moved.position())<2,
                    "The same UUID and server clock follow the target through an actual dimension change; oldRemoved="+d.isRemoved()
                            +", oldPos="+d.position()+", target="+d.attachmentTarget()+", found="+found
                            +", movedPos="+moved.position()+", movedRemoved="+moved.getRemovalReason()+", movedVisible="+(other.getEntity(moved.getUUID())==moved));
            found.discard();moved.discard();
            if (!destinationWasForced) other.setChunkForced(0,0,false);
        });
    }
}
