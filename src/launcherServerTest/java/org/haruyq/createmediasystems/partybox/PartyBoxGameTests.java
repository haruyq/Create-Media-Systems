package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.power.PowerFeature;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.Remotable.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PartyBoxGameTests {
    private static net.neoforged.neoforge.common.util.FakePlayer player(GameTestHelper h) {
        var p=new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"PartyBoxTest"));
        h.getLevel().addFreshEntity(p); return p;
    }
    private static PartyBoxEntity box(GameTestHelper h,int energy) {
        var box=new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(),h.getLevel());
        var p=h.absolutePos(new BlockPos(1,1,1));box.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
        ItemStack battery=new ItemStack(PowerFeature.BATTERY.get());ChargeableItem.setEnergy(battery,energy);
        box.inventory.setItem(1,battery);box.inventory.setItem(0,new ItemStack(Items.MUSIC_DISC_CAT));
        return box;
    }
    @GameTest(template="empty") public static void movingAndSavingKeepsTheSameMusicClockAndBothOriginalItems(GameTestHelper h) {
        var box=box(h,BatteryItem.CAPACITY);CompoundTag tag=new CompoundTag();box.save(tag);
        tag.putLong("Start",h.getLevel().getGameTime()-210);tag.putLong("Offset",70);box.load(tag);
        h.assertTrue(box.positionTicks()==280 && box.playing(),"World reload restores the authoritative playing clock");
        box.setPos(box.getX()+12,box.getY(),box.getZ()+2);
        h.assertTrue(box.positionTicks()==280,"Dragging cannot restart a song");
        ItemStack portable=box.portableItem();var restored=new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(),h.getLevel());
        restored.loadPortable(portable.get(PartyBoxFeature.PARTYBOX_STATE.get()));
        h.assertTrue(!restored.playing() && restored.positionTicks()==280,"Packed speakers pause at their actual position");
        h.assertTrue(restored.inventory.getItem(0).is(Items.MUSIC_DISC_CAT) && restored.energy()==BatteryItem.CAPACITY,"One packed item owns its CD and battery");
        restored.play();h.assertTrue(restored.positionTicks()==280,"Unpacking resumes rather than restarting");h.succeed();
    }
    @GameTest(template="empty") public static void unloadingChargesTheWholeElapsedIntervalAndStopsAtActualDepletion(GameTestHelper h) {
        var box=box(h,1);CompoundTag tag=new CompoundTag();box.save(tag);
        long now=h.getLevel().getGameTime();tag.putLong("PowerAt",now-1000);tag.putLong("Start",now-1000);tag.putInt("Lights",0);
        box.load(tag);box.tick();
        h.assertTrue(box.energy()==0 && !box.playing(),"An unloaded battery cannot provide free power");
        h.assertTrue(box.positionTicks()>0 && box.positionTicks()<10,"Clock freezes when the last FE ran out, not when the chunk reloaded");
        h.assertTrue(box.inventory.getItem(0).is(Items.MUSIC_DISC_CAT),"Depletion preserves the real source disc");h.succeed();
    }
    @GameTest(template="empty") public static void batteryCanUseTheExistingCreateChargerAndFESimulationIsPure(GameTestHelper h) {
        ItemStack battery=new ItemStack(PowerFeature.BATTERY.get());
        var port=battery.getCapability(Capabilities.EnergyStorage.ITEM);
        h.assertTrue(port!=null && port.receiveEnergy(1000,true)==1000 && ChargeableItem.energy(battery)==0,"FE simulation changes nothing");
        BlockPos pos=h.absolutePos(new BlockPos(1,1,1));h.getLevel().setBlockAndUpdate(pos,LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState());
        var charger=(org.haruyq.createmediasystems.launcher.DiscCellCharger)h.getLevel().getBlockEntity(pos);charger.insert(battery);
        var dock=h.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK,pos,null);dock.receiveEnergy(777,false);
        h.assertTrue(ChargeableItem.energy(charger.cell())==777,"The normal rotation/FE charger charges a real PartyBox battery");
        h.assertTrue(port.extractEnergy(1000,false)==0,"Battery cannot generate free energy");h.succeed();
    }
    @GameTest(template="empty") public static void unloadedFinishedSongsStopConsumingPowerAtTheirEnd(GameTestHelper h) {
        var box=box(h,BatteryItem.CAPACITY);CompoundTag tag=new CompoundTag();box.save(tag);
        long now=h.getLevel().getGameTime();tag.putLong("PowerAt",now-1000);tag.putLong("Start",now-1000);tag.putDouble("Duration",10);tag.putInt("Lights",0);
        box.load(tag);box.tick();
        h.assertTrue(!box.playing() && box.positionTicks()==0,"Finished track returns to its beginning");
        h.assertTrue(box.energy()>=BatteryItem.CAPACITY-39,"Only the ten seconds of actual music drain the battery");h.succeed();
    }
    @GameTest(template="empty") public static void bluRayAdvancesAcrossUnloadAndKeepsItsPlaylistWhenPacked(GameTestHelper h) {
        var box=box(h,10000);
        var owner=java.util.UUID.randomUUID();
        var first=new DiscUrl(owner,java.util.UUID.randomUUID()).toString();
        var second=new DiscUrl(owner,java.util.UUID.randomUUID()).toString();
        var disc=new ItemStack(DiscFeature.BLU_RAY.get());
        disc.set(DiscFeature.PLAYLIST.get(),java.util.List.of(first,second));
        box.inventory.setItem(0,disc);
        box.learnLength(5);
        var tag=new CompoundTag();box.save(tag);
        long now=h.getLevel().getGameTime();
        tag.putLong("PowerAt",now-160);tag.putLong("Start",now-160);
        box.load(tag);box.settlePower();
        h.assertTrue(box.playing() && box.track()==1 && second.equals(box.currentUrl()) && box.positionTicks()==60,
                "Time past the first track's end belongs to the second track after reload");
        box.learnLength(120);
        box.control(Action.SEEK,25);
        int revision=box.revision();
        var packed=box.portableItem();var restored=new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(),h.getLevel());
        restored.loadPortable(packed.get(PartyBoxFeature.PARTYBOX_STATE.get()));
        h.assertTrue(!restored.playing() && restored.track()==1 && restored.positionTicks()==500
                && ItemStack.matches(disc,restored.disc()),"Packing preserves the track, its seek and the original Blu-ray playlist");
        box.control(Action.PREVIOUS,0);
        h.assertTrue(box.track()==0 && box.playing() && box.positionTicks()==0 && box.revision()!=revision,
                "Previous starts the first track at zero with a fresh playback revision");
        h.succeed();
    }
    @GameTest(template="empty") public static void controlsRejectPacketsWithoutTheActualNearbyMenu(GameTestHelper h) {
        var box=box(h,10000);h.getLevel().addFreshEntity(box);var p=player(h);p.setPos(box.getX(),box.getY(),box.getZ()+1);
        PartyBoxAction.handle(p,new PartyBoxAction(box.getId(),PartyBoxAction.Kind.VOLUME,32));
        h.assertTrue(box.volume()==18,"Unopened menu cannot remotely control a speaker");
        p.containerMenu=new PartyBoxMenu(3,p.getInventory(),box);
        PartyBoxAction.handle(p,new PartyBoxAction(box.getId(),PartyBoxAction.Kind.VOLUME,1000));h.assertTrue(box.volume()==32,"Server bounds the volume");
        p.setPos(box.getX()+40,box.getY(),box.getZ());PartyBoxAction.handle(p,new PartyBoxAction(box.getId(),PartyBoxAction.Kind.VOLUME,0));
        h.assertTrue(box.volume()==32,"A stale far-away menu cannot alter the speaker");h.succeed();
    }
    @GameTest(template="empty") public static void packingReturnsExactlyOneOwnedSpeakerAndNoLooseOriginals(GameTestHelper h) {
        var box=box(h,12345);h.getLevel().addFreshEntity(box);var p=h.makeMockPlayer(GameType.SURVIVAL);p.setPos(box.getX(),box.getY(),box.getZ());
        h.assertTrue(box.pack(p),"Packing succeeds near the speaker");h.assertTrue(!box.pack(p),"A removed speaker cannot be packed twice");
        h.assertTrue(p.getInventory().countItem(PartyBoxFeature.PARTYBOX.get())==1 && p.getInventory().countItem(Items.MUSIC_DISC_CAT)==0,"CD lives inside one packed speaker");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=70) public static void trolleyUsesCollisionInsteadOfTeleportingThroughWalls(GameTestHelper h) {
        var box=box(h,10000);h.getLevel().addFreshEntity(box);var player=player(h);
        double start=box.getZ();player.setPos(box.getX(),box.getY(),start+1.8);player.setYRot(0);
        for(int x=0;x<=3;x++) for(int z=0;z<=4;z++) h.setBlock(x,0,z,Blocks.STONE);
        for(int x=0;x<=3;x++) for(int y=1;y<=3;y++) h.setBlock(x,y,2,Blocks.STONE);
        box.togglePull(player);
        h.runAfterDelay(35,()->{
            h.assertTrue(box.getZ()<start+.5,"Trolley does not cross the solid wall");
            h.assertTrue(box.seconds()>0 && box.inventory.getItem(0).is(Items.MUSIC_DISC_CAT),"A blocked trolley still plays the same CD");h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=40) public static void walkingKeepsTheHandleWithinReachInsteadOfAccumulatingSpringLag(GameTestHelper h) {
        var box=box(h,10000);h.getLevel().addFreshEntity(box);var p=player(h);
        double z=box.getZ()+.85;p.setPos(box.getX(),box.getY(),z);p.setYRot(0);
        for(int x=0;x<=3;x++)for(int zz=0;zz<=5;zz++)h.setBlock(x,0,zz,Blocks.STONE);
        box.togglePull(p);
        for(int i=1;i<=16;i++){int step=i;h.runAfterDelay(i,()->p.setPos(p.getX(),p.getY(),z+step*.12));}
        h.runAfterDelay(15,()->{
            h.assertTrue(box.distanceToSqr(p)<1.18*1.18,"Moving handle stays within reach while walking, not just after stopping");
            h.assertTrue(box.playing() && box.getZ()>z+.2,"The same playing speaker follows the walking player");h.succeed();
        });
    }
}
