package org.haruyq.createmediasystems.megaphone;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem;
import org.haruyq.createmediasystems.stream.MediaItem;

/** The physical item owns the media, controls, battery and server playback clock. */
public final class MegaphoneData {
    private MegaphoneData() {}
    public static CompoundTag state(ItemStack item) {return item.getOrDefault(MegaphoneFeature.MEGAPHONE_STATE.get(),new CompoundTag());}
    private static void commit(ItemStack item,CompoundTag tag){item.set(MegaphoneFeature.MEGAPHONE_STATE.get(),tag);}
    public static Optional<UUID> id(ItemStack item){var t=state(item);return t.hasUUID("Id")?Optional.of(t.getUUID("Id")):Optional.empty();}
    public static int volume(ItemStack item){var t=state(item);return Math.clamp(t.contains("Volume")?t.getInt("Volume"):22,0,32);}
    public static int drive(ItemStack item){var t=state(item);return Math.clamp(t.contains("Drive")?t.getInt("Drive"):1,0,2);}
    public static boolean playing(ItemStack item){return state(item).getBoolean("Playing");}
    public static int revision(ItemStack item){return state(item).getInt("Revision");}
    public static ItemStack disc(ItemStack item,Level level){return ItemStack.parseOptional(level.registryAccess(),state(item).getCompound("Disc"));}
    public static ItemStack battery(ItemStack item,Level level){return ItemStack.parseOptional(level.registryAccess(),state(item).getCompound("Battery"));}
    public static long position(ItemStack item,Level level){var t=state(item);return Math.max(0,t.getLong("Offset")+(playing(item)?Math.max(0,level.getGameTime()-t.getLong("Start")):0));}
    public static double seconds(ItemStack item,Level level){return position(item,level)/20.0;}
    public static void controls(ItemStack item,int volume,int drive){var t=state(item).copy();t.putInt("Volume",Math.clamp(volume,0,32));t.putInt("Drive",Math.clamp(drive,0,2));commit(item,t);}
    public static void contents(ItemStack item,Level level,ItemStack disc,ItemStack battery) {
        tick(item,level);var t=state(item).copy();
        if(!ItemStack.matches(disc(item,level),disc)) {
            t.putLong("Offset",0);t.putBoolean("Playing",false);t.putDouble("Duration",-1);t.putInt("Revision",revision(item)+1);
        }
        t.put("Disc",disc.copyWithCount(1).saveOptional(level.registryAccess()));
        t.put("Battery",battery.copyWithCount(1).saveOptional(level.registryAccess()));
        if(PartyBoxBatteryItem.energy(battery)<=0 && t.getBoolean("Playing")) {
            t.putLong("Offset",position(item,level));t.putBoolean("Playing",false);t.putInt("Revision",t.getInt("Revision")+1);
        }
        t.putLong("PowerAt",level.getGameTime());commit(item,t);
    }
    public static boolean play(ItemStack item,Level level,boolean play) {
        tick(item,level);var t=state(item).copy();
        if(play) {
            var media=disc(item,level);
            if(media.isEmpty() || PartyBoxBatteryItem.energy(battery(item,level))<=0 || MediaItem.silent(media) || !MediaItem.allowed(media))return false;
        }
        long at=position(item,level);double duration=t.getDouble("Duration");
        if(play && duration>0 && at>=duration*20)at=0;
        t.putLong("Offset",at);t.putLong("Start",level.getGameTime());t.putLong("PowerAt",level.getGameTime());
        t.putBoolean("Playing",play);t.putInt("Revision",revision(item)+1);commit(item,t);return true;
    }
    public static void noteDuration(ItemStack item,double seconds) {
        if(MediaItem.plausibleLength(seconds) && state(item).getDouble("Duration")<=0) {
            var t=state(item).copy();t.putDouble("Duration",seconds);commit(item,t);
        }
    }
    public static void tick(ItemStack item,Level level) {
        if(level.isClientSide)return;
        var t=state(item).copy();boolean dirty=false;long now=level.getGameTime();
        if(!t.hasUUID("Id")){t.putUUID("Id",UUID.randomUUID());dirty=true;}
        if(playing(item)) {
            long elapsed=Math.max(0,now-(t.contains("PowerAt")?t.getLong("PowerAt"):now));
            long at=position(item,level);double duration=t.getDouble("Duration");
            long active=duration>0?Math.min(elapsed,Math.max(0,(long)Math.ceil(duration*20)-(at-elapsed))):elapsed;
            var battery=battery(item,level);double rate=2+6*Math.pow(volume(item)/32.0,2);
            double used=t.getDouble("Fraction")+active*rate;int energy=PartyBoxBatteryItem.energy(battery);
            if(used>=energy) {
                long previous=Math.max(0,at-elapsed);
                t.putLong("Offset",previous+Math.max(0,(long)Math.floor((energy-t.getDouble("Fraction"))/rate)));
                t.putBoolean("Playing",false);t.putInt("Revision",revision(item)+1);used=energy;
            }
            int whole=(int)Math.floor(used);PartyBoxBatteryItem.setEnergy(battery,Math.max(0,energy-whole));
            t.putDouble("Fraction",t.getBoolean("Playing")?used-whole:0);t.putLong("PowerAt",now);
            t.put("Battery",battery.saveOptional(level.registryAccess()));
            if(duration>0 && at>=duration*20){t.putLong("Offset",(long)(duration*20));t.putBoolean("Playing",false);t.putInt("Revision",revision(item)+1);}
            if(duration<=0 && now%20==0) {
                double length=MediaItem.lengthSeconds(disc(item,level),level.registryAccess());
                if(length>0)t.putDouble("Duration",length);
            }
            dirty=elapsed>0 || dirty;
        }
        if(dirty)commit(item,t);
    }
}
