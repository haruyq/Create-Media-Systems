package org.haruyq.createmediasystems.mod;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.AudioClient;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.disc.DiscClient;
import org.haruyq.createmediasystems.display.DisplayClient;
import org.haruyq.createmediasystems.hifi.HifiClient;
import org.haruyq.createmediasystems.launcher.client.LauncherClient;
import org.haruyq.createmediasystems.lighting.LightingClient;
import org.haruyq.createmediasystems.megaphone.client.MegaphoneClient;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.partybox.client.PartyBoxClient;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.train.TrainClient;
import org.haruyq.createmediasystems.video.VideoClient;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** The mod on a client: each feature's client part, then the client config they add to, and their Ponder scenes. */
@Mod(value = CreateMediaSystems.MOD_ID, dist = Dist.CLIENT)
public final class CreateMediaSystemsClient {
    /** In the order their listeners are added, which is the order they hear an event in. */
    private static final List<ClientFeature> FEATURES = List.of(LauncherClient::register, PartyBoxClient::register,
            MegaphoneClient::register, ClientStreams::register, new AudioClient(), new DisplayClient(),
            new VideoClient(), new HifiClient(), new TrainClient(), new LightingClient(), P2pClient::register,
            DiscClient::register);

    public CreateMediaSystemsClient(IEventBus modBus, ModContainer container) {
        FEATURES.forEach(feature -> feature.register(modBus));
        ClientConfig.register(container);
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
