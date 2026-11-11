package org.haruyq.createmediasystems.megaphone;

import org.haruyq.createmediasystems.power.ChargeableItem;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.PortablePlaylist;
import org.haruyq.createmediasystems.stream.Remotable.Action;

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
    public static int track(ItemStack item){return state(item).getInt("Track");}
    public static double duration(ItemStack item){return state(item).getDouble("Duration");}
    public static ItemStack disc(ItemStack item,Level level){return ItemStack.parseOptional(level.registryAccess(),state(item).getCompound("Disc"));}
    public static ItemStack battery(ItemStack item,Level level){return ItemStack.parseOptional(level.registryAccess(),state(item).getCompound("Battery"));}
    public static long position(ItemStack item,Level level){return PlaybackClock.read(state(item)).position(level.getGameTime());}
    public static double seconds(ItemStack item,Level level){return PlaybackClock.read(state(item)).seconds(level.getGameTime());}
    public static void controls(ItemStack item,int volume,int drive){var t=state(item).copy();t.putInt("Volume",Math.clamp(volume,0,32));t.putInt("Drive",Math.clamp(drive,0,2));commit(item,t);}
    public static void contents(ItemStack item,Level level,ItemStack disc,ItemStack battery) {
        tick(item,level);var t=state(item).copy();long now=level.getGameTime();
        PlaybackClock clock=PlaybackClock.read(t);
        if(!ItemStack.matches(disc(item,level),disc)){clock.stop();clock.forget();t.putInt("Track",0);}
        t.put("Disc",disc.copyWithCount(1).saveOptional(level.registryAccess()));
        t.put("Battery",battery.copyWithCount(1).saveOptional(level.registryAccess()));
        if(ChargeableItem.energy(battery)<=0)clock.pause(now);
        clock.save(t);settled(t,now);commit(item,t);
    }
    public static boolean play(ItemStack item,Level level,boolean play) {
        if (play != playing(item)) control(item,level,Action.PLAY_PAUSE,0);
        return play == playing(item);
    }
    public static void control(ItemStack item,Level level,Action action,int value) {
        boolean wasPlaying=playing(item);
        tick(item,level);
        if(action==Action.PLAY_PAUSE && wasPlaying && !playing(item))return;
        var t=state(item).copy();long now=level.getGameTime();
        var disc=disc(item,level);
        var clock=PlaybackClock.read(t);
        int track=PortablePlaylist.control(disc,clock,PortablePlaylist.index(disc,track(item)),now,
                ChargeableItem.energy(battery(item,level))>0,action,value);
        t.putInt("Track",track);
        clock.save(t);settled(t,now);commit(item,t);
    }
    /** A listener's client tells its track's length, for a track the server can't tell the length of. */
    public static void learnLength(ItemStack item,double seconds) {
        var t=state(item).copy();var clock=PlaybackClock.read(t);clock.learn(seconds);clock.save(t);commit(item,t);
    }
    /** Its battery has paid up to {@code now}: a tick settled it while it played, and standing costs nothing. */
    private static void settled(CompoundTag t,long now){new PlaybackClock.Drain(now,t.getDouble(OWED)).save(t,OWED);}
    public static void tick(ItemStack item,Level level) {
        if(level.isClientSide)return;
        var t=state(item).copy();boolean dirty=false;long now=level.getGameTime();
        if(!t.hasUUID("Id")){t.putUUID("Id",UUID.randomUUID());dirty=true;}
        if(playing(item)) {
            dirty|=!t.contains("PowerAt") || t.getLong("PowerAt")!=now;
            var clock=PlaybackClock.read(t);var drain=PlaybackClock.Drain.read(t,OWED,now);var battery=battery(item,level);
            var disc=disc(item,level);
            int track=PortablePlaylist.index(disc,track(item));
            var paid=PortablePlaylist.settle(disc,level.registryAccess(),clock,drain,now,track,
                    ChargeableItem.energy(battery),MegaphoneAcoustics.drainPerTick(volume(item)));
            ChargeableItem.setEnergy(battery,paid.energy());
            t.put("Battery",battery.saveOptional(level.registryAccess()));
            t.putInt("Track",paid.track());
            clock.save(t);drain.save(t,OWED);
        } else if (now % 20 == 0 && duration(item) <= 0) {
            var clock = PlaybackClock.read(t);
            clock.learn(PortablePlaylist.duration(disc(item,level),track(item),level.registryAccess()));
            if (clock.length() > 0) {
                clock.save(t);
                dirty = true;
            }
        }
        if(dirty)commit(item,t);
    }
}
