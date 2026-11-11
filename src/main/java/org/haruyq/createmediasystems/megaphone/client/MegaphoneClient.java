package org.haruyq.createmediasystems.megaphone.client;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.haruyq.createmediasystems.megaphone.*;
import org.haruyq.createmediasystems.playback.*;

public final class MegaphoneClient {
    private record Horn(Player player,InteractionHand hand,UUID id) implements MusicEmitter {
        @Override public int cap(){return 8;}
        static Stream<Horn> near(Entity ear) {
            return ear.level().players().stream().flatMap(player->Arrays.stream(InteractionHand.values()).flatMap(hand->{
                ItemStack item=player.getItemInHand(hand);
                return item.getItem() instanceof MegaphoneItem && MegaphoneData.playing(item)
                        ? MegaphoneData.id(item).map(id->new Horn(player,hand,id)).stream() : Stream.empty();
            }));
        }
        ItemStack item(){return player.getItemInHand(hand);}
        @Override public int revision(){return MegaphoneData.revision(item());}
        @Override public ItemStack media(){return MegaphoneData.disc(item(),player.level());}
        @Override public boolean present(){return !player.isRemoved() && WorldEvents.isClientWorld(player.level())
                && item().getItem() instanceof MegaphoneItem && MegaphoneData.id(item()).filter(id::equals).isPresent() && MegaphoneData.playing(item());}
        @Override public Vec3 position(){return player.getEyePosition().add(player.getLookAngle().scale(.46)).add(0,-.16,0);}
        @Override public Vec3 velocity(){return DopplerMath.step(player);}
        @Override public double seconds(){return MegaphoneData.seconds(item(),player.level());}
        @Override public double range(){return MegaphoneAcoustics.RANGE;}
        @Override public double gain(Entity ear){
            Vec3 offset=ear.getEyePosition().subtract(position());double beam=ear==player?.4:MegaphoneAcoustics.direction(player.getLookAngle(),offset);
            return MegaphoneAcoustics.gain(offset.length(),MegaphoneData.volume(item()),beam);}
        @Override public List<UnaryOperator<AudioStream>> decorators(){
            return List.of(pcm->new MegaphoneAudioStream(pcm,()->MegaphoneData.drive(item()),()->MegaphoneData.volume(item())));}
        @Override public boolean viaApi(){return true;}
    }
    private MegaphoneClient(){}
    /** The arm of each hand holding a playing megaphone held up before the face, following the head. */
    private static void poseArms(net.minecraft.client.model.HumanoidModel<?> model,Player p,float yaw,float pitch) {
        for(var hand:InteractionHand.values()) {
            var item=p.getItemInHand(hand);if(!(item.getItem() instanceof MegaphoneItem) || !MegaphoneData.playing(item))continue;
            boolean right=(p.getMainArm()==net.minecraft.world.entity.HumanoidArm.RIGHT)==(hand==InteractionHand.MAIN_HAND);
            var arm=right?model.rightArm:model.leftArm;arm.xRot=(float)(-Math.PI/2+Math.toRadians(pitch)*.8);arm.yRot=(float)Math.toRadians(yaw)+(right?-.12f:.12f);arm.zRot=0;
        }
    }
    public static void register(IEventBus bus) {
        org.haruyq.createmediasystems.client.ArmPoses.add(MegaphoneClient::poseArms);
        bus.addListener((RegisterMenuScreensEvent e)->e.register(MegaphoneFeature.MEGAPHONE_MENU.get(),MegaphoneScreen::new));
        bus.addListener((RegisterClientExtensionsEvent e)->e.registerItem(new IClientItemExtensions(){
            private MegaphoneItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new MegaphoneItemRenderer();return renderer;}
        },MegaphoneFeature.MEGAPHONE_WHITE.get(),MegaphoneFeature.MEGAPHONE_BLACK.get()));
        EmitterAudioManager.add(Horn::near);
    }
}
