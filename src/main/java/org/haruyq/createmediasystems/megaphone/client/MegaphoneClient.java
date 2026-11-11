package org.haruyq.createmediasystems.megaphone.client;

import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.haruyq.createmediasystems.*;
import org.haruyq.createmediasystems.launcher.*;
import org.haruyq.createmediasystems.megaphone.*;
import org.haruyq.createmediasystems.playback.*;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

public final class MegaphoneClient {
    public record Emitter(Player player,InteractionHand hand,UUID id,int revision) {
        public ItemStack item(){return player.getItemInHand(hand);}
        public boolean present(){return !player.isRemoved() && player.level()==Minecraft.getInstance().level
                && item().getItem() instanceof MegaphoneItem && MegaphoneData.id(item()).filter(id::equals).isPresent()
                && MegaphoneData.revision(item())==revision && MegaphoneData.playing(item());}
        public Vec3 position(){return player.getEyePosition().add(player.getLookAngle().scale(.46)).add(0,-.16,0);}
        public double seconds(){return MegaphoneData.seconds(item(),player.level());}
        public int volume(){return MegaphoneData.volume(item());}
        public int drive(){return MegaphoneData.drive(item());}
        public boolean audible(){var ear=Minecraft.getInstance().getCameraEntity();return present() && ear!=null && ear.distanceToSqr(player)<MegaphoneAcoustics.RANGE*MegaphoneAcoustics.RANGE;}
        public double gain(){var ear=Minecraft.getInstance().getCameraEntity();if(ear==null)return 0;
            Vec3 offset=ear.getEyePosition().subtract(position());double beam=ear==player?.4:MegaphoneAcoustics.direction(player.getLookAngle(),offset);
            return MegaphoneAcoustics.gain(offset.length(),volume(),beam);}
        public double pitch(){var ear=Minecraft.getInstance().getCameraEntity();if(ear==null)return 1;var p=position();
            return DopplerMath.observedRatio(p.x,p.y,p.z,player.getX()-player.xo,player.getY()-player.yo,player.getZ()-player.zo,
                    ear.getX(),ear.getEyeY(),ear.getZ(),ear.getX()-ear.xo,ear.getY()-ear.yo,ear.getZ()-ear.zo);}
    }
    private record Playing(Emitter source,MegaphoneSound vanilla,boolean stream,int at){
        double seconds(){return stream?ClientStreams.audibleSeconds(source.id):vanilla.audibleSeconds();}
    }
    public record Playback(UUID id,String player,int revision,double serverSeconds,double audibleSeconds,boolean stream,double requestedSeconds){}
    /** Used by diagnostics to check the same physical horn on separate clients. */
    public static List<Playback> playbacks(){return PLAYING.values().stream().map(p->new Playback(p.source.id,p.source.player.getScoreboardName(),p.source.revision,p.source.seconds(),p.seconds(),p.stream,p.stream?ClientStreams.requestedSeconds(p.source.id):-1)).toList();}
    private static final Map<UUID,Playing> PLAYING=new HashMap<>();private static int ticks;
    private MegaphoneClient(){}
    public static void register(IEventBus bus) {
        bus.addListener((RegisterMenuScreensEvent e)->e.register(MegaphoneFeature.MEGAPHONE_MENU.get(),MegaphoneScreen::new));
        bus.addListener((RegisterClientExtensionsEvent e)->e.registerItem(new IClientItemExtensions(){
            private MegaphoneItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new MegaphoneItemRenderer();return renderer;}
        },MegaphoneFeature.MEGAPHONE_WHITE.get(),MegaphoneFeature.MEGAPHONE_BLACK.get()));
        bus.addListener((net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent e)->Minecraft.getInstance().execute(MegaphoneClient::stopAll));
        NeoForge.EVENT_BUS.addListener(MegaphoneClient::tick);
    }
    private static void stop(Playing p){if(p.stream)ClientStreams.stop(p.source.id);else Minecraft.getInstance().getSoundManager().stop(p.vanilla);}
    private static void stopAll(){PLAYING.values().forEach(MegaphoneClient::stop);PLAYING.clear();}
    private static void tick(ClientTickEvent.Post ignored) {
        var mc=Minecraft.getInstance();ticks++;
        if(mc.level==null || mc.player==null){stopAll();return;}
        PLAYING.entrySet().removeIf(e->{var p=e.getValue();int age=ticks-p.at;
            boolean alive=p.stream?ClientStreams.contains(e.getKey()):mc.getSoundManager().isActive(p.vanilla);
            if(p.source.audible() && (alive || age<40) && !PlaybackSync.realign(p.seconds(),p.source.seconds(),age,p.source.pitch()))return false;
            stop(p);return true;
        });
        if(ticks%5!=0)return;
        for(var player:mc.level.players())for(var hand:InteractionHand.values()) {
            ItemStack item=player.getItemInHand(hand);
            if(!(item.getItem() instanceof MegaphoneItem) || !MegaphoneData.playing(item))continue;
            var id=MegaphoneData.id(item);if(id.isEmpty() || PLAYING.containsKey(id.get()) || PLAYING.size()>=8)continue;
            var source=new Emitter(player,hand,id.get(),MegaphoneData.revision(item));if(!source.audible())continue;
            var disc=MegaphoneData.disc(item,mc.level);String url=MediaItem.url(disc);
            if(url!=null && UrlPolicy.isAllowed(url)) {
                double start=source.seconds();
                var sound=new StreamSound(ClientStreams.apiStream(url,start,Streams.Channel.MIX),url,null,0,start,SoundSource.RECORDS,
                        source::position,null,source::present,source::audible,source::gain,null,Streams.Channel.MIX,length->{});
                sound.setDiscPlayback(id.get(),source::seconds);sound.setDynamicPitch(source::pitch);sound.setMegaphone(source::drive,source::volume);
                ClientStreams.play(id.get(),sound);PLAYING.put(id.get(),new Playing(source,null,true,ticks));
            } else MediaItem.song(disc,mc.level.registryAccess()).ifPresent(song->{
                var sound=new MegaphoneSound(source,song.value().soundEvent().value());mc.getSoundManager().play(sound);PLAYING.put(id.get(),new Playing(source,sound,false,ticks));
            });
        }
    }
}
