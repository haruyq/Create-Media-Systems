package org.haruyq.createmediasystems.mod;

import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.display.DisplayFeature;
import org.haruyq.createmediasystems.hifi.HifiFeature;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import org.haruyq.createmediasystems.p2p.P2pFeature;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.train.TrainFeature;
import org.haruyq.createmediasystems.video.VideoFeature;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** The mod: its features, and the creative tab that lists them. */
@Mod(CreateMediaSystems.MOD_ID)
public final class CreateMediaSystemsMod {
    /** In the order their items are listed in the creative tab; making each loads its registry entries. */
    private static final List<Feature> FEATURES = List.of(new StreamFeature(), new MegaphoneFeature(),
            new PartyBoxFeature(), new LauncherFeature(), new AudioFeature(), new TrainFeature(), new VideoFeature(),
            new DisplayFeature(), new LightingFeature(), new HifiFeature(), new P2pFeature());

    /** Every CMS item, in registration order, so new items show up without touching this. */
    private static final Supplier<CreativeModeTab> TAB = CreateMediaSystems.TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_media_systems"))
            .icon(() -> new ItemStack(AudioFeature.CUSTOM_DISC.get()))
            .displayItems((params, output) -> CreateMediaSystems.ITEMS.getEntries().forEach(item -> output.accept(item.get())))
            .build());

    public CreateMediaSystemsMod(IEventBus modBus, ModContainer container) {
        CreateMediaSystems.register(modBus, container, FEATURES);
    }
}
