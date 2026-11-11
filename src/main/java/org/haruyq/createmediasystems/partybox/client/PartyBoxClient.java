package org.haruyq.createmediasystems.partybox.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.haruyq.createmediasystems.launcher.DopplerMath;
import org.haruyq.createmediasystems.partybox.*;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.PlaybackSync;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PartyBoxClient {
    private record Playing(PartyBoxEntity box,int revision,boolean nativeStream,PartyBoxSound song,int at,Meter meter) {}
    static final class Meter { volatile double value; volatile long at; void sample(double value){this.value=value;at=System.nanoTime();} }
    private static final Map<UUID,Playing> PLAYING=new HashMap<>();
    public static double audibleSeconds(UUID id){var p=PLAYING.get(id);return p==null?-1:p.nativeStream?ClientStreams.audibleSeconds(id):p.song.audibleSeconds();}
    private static int ticks;
    private PartyBoxClient() {}
    public static float beat(PartyBoxEntity box) {var p=PLAYING.get(box.getUUID());return p==null?0:(float)(p.meter.value*Math.exp(-Math.max(0,System.nanoTime()-p.meter.at)/250000000.0));}
    public static boolean pulling(net.minecraft.world.entity.player.Player player) {
        return player.level().getEntitiesOfClass(PartyBoxEntity.class,player.getBoundingBox().inflate(4))
                .stream().anyMatch(box->box.puller().filter(player.getUUID()::equals).isPresent());
    }
    public static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event)->event.registerEntityRenderer(PartyBoxFeature.PARTYBOX_ENTITY.get(),PartyBoxRenderer::new));
        bus.addListener((RegisterMenuScreensEvent event)->event.register(PartyBoxFeature.PARTYBOX_MENU.get(),PartyBoxScreen::new));
        bus.addListener((RegisterClientExtensionsEvent event)->event.registerItem(new IClientItemExtensions() {
            private PartyBoxItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() { if(renderer==null)renderer=new PartyBoxItemRenderer();return renderer; }
        },PartyBoxFeature.PARTYBOX.get()));
        bus.addListener((net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent event)->Minecraft.getInstance().execute(PartyBoxClient::stopAll));
        NeoForge.EVENT_BUS.addListener(PartyBoxClient::tick);
    }
    public static boolean audible(PartyBoxEntity box) {
        var camera=Minecraft.getInstance().getCameraEntity();
        return camera!=null && camera.level()==box.level() && !box.isRemoved() && box.playing()
                && camera.distanceToSqr(box)<PartyBoxSpecs.HEAR_RANGE*PartyBoxSpecs.HEAR_RANGE;
    }
    public static float gain(PartyBoxEntity box) {
        var camera=Minecraft.getInstance().getCameraEntity(); if(camera==null)return 0;
        double distance=camera.position().distanceTo(box.soundPosition())/18;
        return (float)(box.volume()/32.0*.95/(1+distance*distance));
    }
    /** Each listener hears the pitch of this same moving music source, after client interpolation. */
    public static double pitchAt(PartyBoxEntity box) {
        var ear=Minecraft.getInstance().getCameraEntity();
        if(ear==null || ear.level()!=box.level() || box.tickCount<2 || ear.tickCount<2)return 1;
        var source=box.soundPosition();
        return DopplerMath.observedRatio(source.x,source.y,source.z,
                box.getX()-box.xo,box.getY()-box.yo,box.getZ()-box.zo,
                ear.getX(),ear.getEyeY(),ear.getZ(),ear.getX()-ear.xo,ear.getY()-ear.yo,ear.getZ()-ear.zo);
    }
    private static void stop(Playing p) {
        if(p.nativeStream)ClientStreams.stop(p.box.getUUID()); else Minecraft.getInstance().getSoundManager().stop(p.song);
    }
    private static void stopAll() { PLAYING.values().forEach(PartyBoxClient::stop);PLAYING.clear(); }
    private static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();ticks++;
        if(mc.level==null || mc.player==null) { stopAll();return; }
        PLAYING.entrySet().removeIf(e->{var p=e.getValue();
            boolean active=p.nativeStream?ClientStreams.contains(e.getKey()):mc.getSoundManager().isActive(p.song);
            if(audible(p.box) && p.revision==p.box.revision() && (active || ticks-p.at<40)
                    && !PlaybackSync.realign(audibleSeconds(e.getKey()),p.box.seconds(),ticks-p.at,pitchAt(p.box)))return false;
            stop(p);return true;
        });
        if(ticks%5!=0)return;
        for(var box:mc.level.getEntitiesOfClass(PartyBoxEntity.class,mc.player.getBoundingBox().inflate(PartyBoxSpecs.HEAR_RANGE))) {
            if(!audible(box)||PLAYING.containsKey(box.getUUID())||PLAYING.size()>=12)continue;
            String url=MediaItem.url(box.disc()); int revision=box.revision();Meter meter=new Meter();
            if(url!=null && UrlPolicy.isAllowed(url)) {
                double start=box.seconds();
                StreamSound sound=new StreamSound(ClientStreams.apiStream(url,start,Streams.Channel.MIX),url,null,0,start,SoundSource.RECORDS,
                        box::soundPosition,null,()->!box.isRemoved()&&box.playing()&&box.revision()==revision,()->audible(box),()->gain(box),null,Streams.Channel.MIX,
                        length->mc.execute(()->{if(mc.getConnection()!=null && box.revision()==revision && Double.isFinite(length) && length>0)
                            PacketDistributor.sendToServer(new PartyBoxAction(box.getId(),7,(int)Math.ceil(length),revision));}));
                sound.setDiscPlayback(box.getUUID(),box::seconds);sound.setDynamicPitch(()->pitchAt(box));
                sound.setBassMode(box::bass);sound.setPcmMeter(meter::sample);
                ClientStreams.play(box.getUUID(),sound);PLAYING.put(box.getUUID(),new Playing(box,revision,true,null,ticks,meter));
            } else {
                MediaItem.song(box.disc(),mc.level.registryAccess()).ifPresent(song->{
                    var sound=new PartyBoxSound(box,song.value().soundEvent().value(),meter);mc.getSoundManager().play(sound);
                    PLAYING.put(box.getUUID(),new Playing(box,revision,false,sound,ticks,meter));
                });
            }
        }
    }
}
