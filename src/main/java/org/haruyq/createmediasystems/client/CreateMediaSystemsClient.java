package org.haruyq.createmediasystems.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.client.LauncherClient;
import org.haruyq.createmediasystems.audio.RangeQuillHandler;
import org.haruyq.createmediasystems.disc.DiscClient;
import org.haruyq.createmediasystems.disc.DriveClient;
import org.haruyq.createmediasystems.display.DisplayRenderer;
import org.haruyq.createmediasystems.hifi.HifiControls;
import org.haruyq.createmediasystems.hifi.HifiRenderer;
import org.haruyq.createmediasystems.lighting.AnalysisPlayback;
import org.haruyq.createmediasystems.lighting.LightingControls;
import org.haruyq.createmediasystems.lighting.LightingRenderer;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.P2pOverlay;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.train.TrainDetectorRenderer;
import org.haruyq.createmediasystems.video.EqualizerFaders;

import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = CreateMediaSystems.MOD_ID, dist = Dist.CLIENT)
public class CreateMediaSystemsClient {
    public CreateMediaSystemsClient(IEventBus modBus, ModContainer container) {
        ClientConfig.register(container);
        modBus.addListener((FMLClientSetupEvent event) -> PonderIndex.addPlugin(new CreateMediaSystemsPonder()));
        LauncherClient.register(modBus);
        org.haruyq.createmediasystems.partybox.client.PartyBoxClient.register(modBus);
        org.haruyq.createmediasystems.megaphone.client.MegaphoneClient.register(modBus);
        ClientStreams.register(modBus);
        AnalysisPlayback.register();
        RangeQuillHandler.register();
        DisplayRenderer.register(modBus);
        EqualizerFaders.register(modBus);
        HifiControls.register();
        HifiRenderer.register(modBus);
        TrainDetectorRenderer.register(modBus);
        LightingControls.register();
        LightingRenderer.register(modBus);
        P2pClient.register();
        P2pOverlay.register(modBus);
        DiscClient.register();
        DriveClient.register();
    }
}
