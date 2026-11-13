package org.haruyq.createmediasystems.mod;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioClient;
import org.haruyq.createmediasystems.blocks.broadcast.client.BroadcastClient;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.PointLights;
import org.haruyq.createmediasystems.client.ScreenLight;
import org.haruyq.createmediasystems.client.TabSections;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.disc.DiscClient;
import org.haruyq.createmediasystems.blocks.display.DisplayClient;
import org.haruyq.createmediasystems.blocks.component.m700.HifiClient;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardClient;
import org.haruyq.createmediasystems.items.launcher.client.LauncherClient;
import org.haruyq.createmediasystems.lighting.LightingClient;
import org.haruyq.createmediasystems.items.megaphone.megaphone30.client.MegaphoneClient;
import org.haruyq.createmediasystems.blocks.piano.grand.client.PianoClient;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.items.partybox.stage320.client.PartyBoxClient;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.EmitterAudioManager;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.client.PxS1100Client;
import org.haruyq.createmediasystems.items.power.client.BatteryRenderer;
import org.haruyq.createmediasystems.blocks.train.TrainClient;
import org.haruyq.createmediasystems.blocks.video.VideoClient;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = CreateMediaSystems.MOD_ID, dist = Dist.CLIENT)
public final class CreateMediaSystemsClient {
    /** In the order their listeners are added, which is the order they hear an event in. */
    private static final List<ClientFeature> FEATURES = List.of(BatteryRenderer.CLIENT, LauncherClient::register, PartyBoxClient::register,
            MegaphoneClient::register, EmitterAudioManager::register, ClientStreams::register, new AudioClient(),
            PxS1100Client.INSTANCE, new DisplayClient(),
            new VideoClient(), new HifiClient(), new TrainClient(), new LightingClient(), P2pClient::register,
            DiscClient::register, BroadcastClient::register, new KeyboardClient(), PianoClient::register);

    public CreateMediaSystemsClient(IEventBus modBus, ModContainer container) {
        FEATURES.forEach(feature -> feature.register(modBus));
        ClientConfig.register(container);
        ScreenLight.register();
        TabSections.register();
        WorldEvents.register();
        PointLights.register(modBus);
        modBus.addListener((FMLClientSetupEvent event) -> PonderIndex.addPlugin(new PonderPlugin() {
            @Override
            public String getModId() {
                return CreateMediaSystems.MOD_ID;
            }

            @Override
            public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
                FEATURES.forEach(feature -> feature.registerPonder(helper));
            }
        }));
    }
}
