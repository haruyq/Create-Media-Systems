package org.haruyq.createmediasystems.megaphone;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;

/** The physical item owns the media, controls, battery and server playback clock. */
public final class MegaphoneData {
    /** Where its state keeps the part of an energy unit its clock owes (see PlaybackClock.Drain). */
    private static final String OWED="Fraction";
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
    public static long position(ItemStack item,Level level){return PlaybackClock.read(state(item)).position(level.getGameTime());}
    public static double seconds(ItemStack item,Level level){return PlaybackClock.read(state(item)).seconds(level.getGameTime());}
    public static void controls(ItemStack item,int volume,int drive){var t=state(item).copy();t.putInt("Volume",Math.clamp(volume,0,32));t.putInt("Drive",Math.clamp(drive,0,2));commit(item,t);}
    public static void contents(ItemStack item,Level level,ItemStack disc,ItemStack battery) {
        tick(item,level);var t=state(item).copy();long now=level.getGameTime();
        PlaybackClock clock=PlaybackClock.read(t);
        if(!ItemStack.matches(disc(item,level),disc)){clock.stop();clock.forget();}
        t.put("Disc",disc.copyWithCount(1).saveOptional(level.registryAccess()));
        t.put("Battery",battery.copyWithCount(1).saveOptional(level.registryAccess()));
        if(PartyBoxBatteryItem.energy(battery)<=0)clock.pause(now);
        clock.save(t);settled(t,now);commit(item,t);
    }
    public static boolean play(ItemStack item,Level level,boolean play) {
        tick(item,level);var t=state(item).copy();long now=level.getGameTime();
        if(play) {
            var media=disc(item,level);
            if(media.isEmpty() || PartyBoxBatteryItem.energy(battery(item,level))<=0 || MediaItem.silent(media) || !MediaItem.allowed(media))return false;
        }
        PlaybackClock clock=PlaybackClock.read(t);
        if(!play)clock.pause(now);
        else {if(clock.ended(now))clock.seek(0);clock.play(now);}
        clock.save(t);settled(t,now);commit(item,t);return true;
    }
    /** A listener's client tells its track's length, for a track the server can't tell the length of. */
    public static void noteDuration(ItemStack item,double seconds) {
        var t=state(item).copy();var clock=PlaybackClock.read(t);clock.learn(seconds);clock.save(t);commit(item,t);
    }
    /** Its battery has paid up to {@code now}: a tick settled it while it played, and standing costs nothing. */
    private static void settled(CompoundTag t,long now){new PlaybackClock.Drain(now,t.getDouble(OWED)).save(t,OWED);}
    /** Pays its battery for the music played since it last settled, and stops at the end of its track. */
    public static void tick(ItemStack item,Level level) {
        if(level.isClientSide)return;
        var t=state(item).copy();boolean dirty=false;long now=level.getGameTime();
        if(!t.hasUUID("Id")){t.putUUID("Id",UUID.randomUUID());dirty=true;}
        if(playing(item)) {
            dirty|=!t.contains("PowerAt") || t.getLong("PowerAt")!=now;
            var clock=PlaybackClock.read(t);var drain=PlaybackClock.Drain.read(t,OWED,now);var battery=battery(item,level);
            PartyBoxBatteryItem.setEnergy(battery,drain.settle(clock,now,PartyBoxBatteryItem.energy(battery),MegaphoneAcoustics.drainPerTick(volume(item))));
            t.put("Battery",battery.saveOptional(level.registryAccess()));
            clock.learn(MediaItem.lengthSeconds(disc(item,level),level.registryAccess()));
            if(clock.running() && clock.ended(now))clock.stop();
            clock.save(t);drain.save(t,OWED);
        }
        if(dirty)commit(item,t);
    }
}
