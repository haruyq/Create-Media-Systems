package org.haruyq.createmediasystems.partybox.client;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.partybox.*;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.playback.BassAudioStream;
import org.haruyq.createmediasystems.playback.DopplerMath;
import org.haruyq.createmediasystems.playback.EmitterAudioManager;
import org.haruyq.createmediasystems.playback.MusicEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;

public final class PartyBoxClient {
    /** How loud its bass is now, as its lights pulse to it. */
    static final class Meter { volatile double value; volatile long at; void sample(double value){this.value=value;at=System.nanoTime();} }

    /** A PartyBox's music, from its speaker and through its bass. */
    private record Speaker(PartyBoxEntity box, Meter meter) implements MusicEmitter {
        @Override public int cap(){return 12;}
        static Stream<Speaker> near(Entity ear) {
            return ear.level().getEntitiesOfClass(PartyBoxEntity.class,ear.getBoundingBox().inflate(PartyBoxSpecs.HEAR_RANGE)).stream()
                    .map(box->new Speaker(box,new Meter()));
        }
        @Override public UUID id(){return box.getUUID();}
        @Override public int revision(){return box.revision();}
        @Override public ItemStack media(){return box.disc();}
        @Override public boolean present(){return !box.isRemoved() && box.level()==Minecraft.getInstance().level && box.playing();}
        @Override public Vec3 position(){return box.soundPosition();}
        /** Its steps after client interpolation; none in its first ticks, while it may still jump into place. */
        @Override public Vec3 velocity(){return box.tickCount<2?null:DopplerMath.step(box);}
        @Override public double seconds(){return box.seconds();}
        @Override public double range(){return PartyBoxSpecs.HEAR_RANGE;}
        @Override public double gain(Entity ear){return box.volume()/32.0*.95*MusicEmitter.falloff(ear.position().distanceTo(box.soundPosition()),18);}
        @Override public List<UnaryOperator<AudioStream>> decorators(){return List.of(pcm->new BassAudioStream(pcm,box::bass,meter::sample));}
    }

    private PartyBoxClient() {}
    public static float beat(PartyBoxEntity box) {
        if(!(EmitterAudioManager.playing(box.getUUID()) instanceof Speaker speaker))return 0;
        var meter=speaker.meter;return (float)(meter.value*Math.exp(-Math.max(0,System.nanoTime()-meter.at)/250000000.0));
    }
    private static boolean pulling(net.minecraft.world.entity.player.Player player) {
        return player.level().getEntitiesOfClass(PartyBoxEntity.class,player.getBoundingBox().inflate(4))
                .stream().anyMatch(box->box.puller().filter(player.getUUID()::equals).isPresent());
    }
    /** The main arm reaching back to the party box this player pulls. */
    private static void poseArms(net.minecraft.client.model.HumanoidModel<?> model,net.minecraft.world.entity.player.Player player,float yaw,float pitch) {
        if(!pulling(player))return;
        boolean right=player.getMainArm()==net.minecraft.world.entity.HumanoidArm.RIGHT;
        var arm=right?model.rightArm:model.leftArm;
        arm.xRot=.78f;arm.yRot=right?-.22f:.22f;arm.zRot=right?-.28f:.28f;
    }
    public static void register(IEventBus bus) {
        org.haruyq.createmediasystems.client.ArmPoses.add(PartyBoxClient::poseArms);
        bus.addListener((EntityRenderersEvent.RegisterRenderers event)->event.registerEntityRenderer(PartyBoxFeature.PARTYBOX_ENTITY.get(),PartyBoxRenderer::new));
        bus.addListener((RegisterMenuScreensEvent event)->event.register(PartyBoxFeature.PARTYBOX_MENU.get(),PartyBoxScreen::new));
        bus.addListener((RegisterClientExtensionsEvent event)->event.registerItem(new IClientItemExtensions() {
            private PartyBoxItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() { if(renderer==null)renderer=new PartyBoxItemRenderer();return renderer; }
        },PartyBoxFeature.PARTYBOX.get()));
        EmitterAudioManager.add(Speaker::near);
        org.haruyq.createmediasystems.client.PointLights.add(PartyBoxRenderer.LIGHTS);
    }
}
