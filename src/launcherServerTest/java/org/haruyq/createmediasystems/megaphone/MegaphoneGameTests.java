package org.haruyq.createmediasystems.megaphone;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.*;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem;

@GameTestHolder(CreateMediaSystems.MOD_ID) @PrefixGameTestTemplate(false)
public final class MegaphoneGameTests {
    private static ItemStack horn(GameTestHelper h,int energy){var horn=new ItemStack(CreateMediaSystems.MEGAPHONE_WHITE.get());var battery=new ItemStack(CreateMediaSystems.PARTYBOX_BATTERY.get());PartyBoxBatteryItem.setEnergy(battery,energy);MegaphoneData.contents(horn,h.getLevel(),new ItemStack(Items.MUSIC_DISC_CAT),battery);return horn;}
    @GameTest(template="empty") public static void serverClockBatteryAndSettingsRemainIndependentOfDecoderReloads(GameTestHelper h){
        var item=horn(h,10000);MegaphoneData.play(item,h.getLevel(),true);var id=MegaphoneData.id(item).orElseThrow();
        var tag=MegaphoneData.state(item).copy();tag.putLong("Start",h.getLevel().getGameTime()-120);tag.putLong("PowerAt",h.getLevel().getGameTime()-20);item.set(CreateMediaSystems.MEGAPHONE_STATE.get(),tag);
        MegaphoneData.tick(item,h.getLevel());int energy=PartyBoxBatteryItem.energy(MegaphoneData.battery(item,h.getLevel()));MegaphoneData.tick(item,h.getLevel());MegaphoneData.controls(item,30,2);
        h.assertTrue(energy<10000 && energy==PartyBoxBatteryItem.energy(MegaphoneData.battery(item,h.getLevel())),"Repeated settlement in one tick must not drain twice");
        h.assertTrue(MegaphoneData.position(item,h.getLevel())==120 && MegaphoneData.id(item).orElseThrow().equals(id),"Changing distortion/volume cannot reset the clock or identity");
        var saved=(net.minecraft.nbt.CompoundTag)item.saveOptional(h.getLevel().registryAccess());var loaded=ItemStack.parseOptional(h.getLevel().registryAccess(),saved);
        h.assertTrue(MegaphoneData.position(loaded,h.getLevel())==120 && MegaphoneData.playing(loaded),"Actual item serialization keeps the running server clock");h.succeed();
    }
    @GameTest(template="empty") public static void exhaustedBatteryPausesAtTheLastPoweredSampleAndRetainsTheOriginalDisc(GameTestHelper h){
        var item=horn(h,10);MegaphoneData.controls(item,32,2);MegaphoneData.play(item,h.getLevel(),true);
        var tag=MegaphoneData.state(item).copy();tag.putLong("Start",h.getLevel().getGameTime()-20);tag.putLong("PowerAt",h.getLevel().getGameTime()-20);item.set(CreateMediaSystems.MEGAPHONE_STATE.get(),tag);MegaphoneData.tick(item,h.getLevel());
        h.assertTrue(!MegaphoneData.playing(item) && MegaphoneData.position(item,h.getLevel())==1 && PartyBoxBatteryItem.energy(MegaphoneData.battery(item,h.getLevel()))==0,"10 FE at 8 FE/t powers exactly one full tick");
        h.assertTrue(MegaphoneData.disc(item,h.getLevel()).is(Items.MUSIC_DISC_CAT),"A dead battery cannot consume the original CD");h.succeed();
    }
    @GameTest(template="empty") public static void heldHornIsLockedInItsMenuAndShiftClickReturnsTheRealMedia(GameTestHelper h){
        var p=h.makeMockPlayer(GameType.CREATIVE);var item=horn(h,10000);p.getInventory().setItem(0,item);var menu=new MegaphoneMenu(1,p.getInventory(),0);
        menu.clicked(29,0,ClickType.THROW,p);h.assertTrue(p.getInventory().getItem(0)==item && menu.stillValid(p),"The physical horn cannot be dropped while its nested inventory is being edited");
        menu.quickMoveStack(p,0);h.assertTrue(MegaphoneData.disc(item,h.getLevel()).isEmpty() && p.getInventory().countItem(Items.MUSIC_DISC_CAT)==1,"Shift-click returns exactly one original and clears the actual item component");h.succeed();
    }
    @GameTest(template="empty") public static void puttingTheHornAwayPausesItsOwnClock(GameTestHelper h){
        var p=h.makeMockPlayer(GameType.CREATIVE);var item=horn(h,10000);p.getInventory().setItem(1,item);p.getInventory().selected=0;MegaphoneData.play(item,h.getLevel(),true);
        ((MegaphoneItem)item.getItem()).inventoryTick(item,h.getLevel(),p,1,false);
        h.assertTrue(!MegaphoneData.playing(item) && !MegaphoneData.disc(item,h.getLevel()).isEmpty(),"Unheld equipment pauses without losing media or generating a world ghost speaker");h.succeed();
    }
}
