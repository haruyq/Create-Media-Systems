package org.haruyq.createmediasystems.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.RangeQuillHandler;
import org.haruyq.createmediasystems.display.DisplayRenderer;
import org.haruyq.createmediasystems.lighting.LightingControls;
import org.haruyq.createmediasystems.lighting.LightingRenderer;
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
        ClientStreams.register(modBus);
        org.haruyq.createmediasystems.playback.AnalysisPlayback.register();
        RangeQuillHandler.register();
        DisplayRenderer.register(modBus);
        EqualizerFaders.register(modBus);
        TrainDetectorRenderer.register(modBus);
        LightingControls.register();
        LightingRenderer.register(modBus);
    }
}
