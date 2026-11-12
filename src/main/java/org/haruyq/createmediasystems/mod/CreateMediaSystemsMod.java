package org.haruyq.createmediasystems.mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.broadcast.BroadcastFeature;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.display.DisplayFeature;
import org.haruyq.createmediasystems.hifi.HifiFeature;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import org.haruyq.createmediasystems.p2p.P2pFeature;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.piano.PianoFeature;
import org.haruyq.createmediasystems.power.PowerFeature;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.train.TrainFeature;
import org.haruyq.createmediasystems.video.VideoFeature;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(CreateMediaSystems.MOD_ID)
public final class CreateMediaSystemsMod {
    /** Making each loads its registry entries. */
    private static final List<Feature> FEATURES = List.of(new StreamFeature(), new PowerFeature(), new MegaphoneFeature(),
            new PartyBoxFeature(), new LauncherFeature(), new AudioFeature(), new TrainFeature(), new DiscFeature(), new VideoFeature(),
            new DisplayFeature(), new LightingFeature(), new HifiFeature(), new P2pFeature(), new BroadcastFeature(), new PianoFeature());

    private static final Supplier<CreativeModeTab> TAB = CreateMediaSystems.TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_media_systems"))
            .icon(() -> new ItemStack(AudioFeature.CUSTOM_DISC.get()))
            .displayItems((params, output) -> {
                Set<Item> items = new LinkedHashSet<>();
                tabOrder().forEach(item -> items.add(item.get()));
                CreateMediaSystems.ITEMS.getEntries().forEach(item -> items.add(item.get()));
                items.forEach(output::accept);
            })
            .build());

    public CreateMediaSystemsMod(IEventBus modBus, ModContainer container) {
        CreateMediaSystems.register(modBus, container, FEATURES);
    }

    /** Grouped by system: media, then each system's devices followed by its tools. */
    private static List<Supplier<? extends Item>> tabOrder() {
        List<Supplier<? extends Item>> order = new ArrayList<>(List.of(
                // Media
                AudioFeature.CUSTOM_DISC, DiscFeature.CD, DiscFeature.CASSETTE, DiscFeature.DVD, DiscFeature.BLU_RAY,
                // Jukebox audio
                AudioFeature.DISC_READER_ITEM, AudioFeature.SPEAKER_ITEM, AudioFeature.RANGE_QUILL, PianoFeature.PIANO_ITEM));
        // Hi-Fi
        order.addAll(HifiFeature.hifiUnits());
        order.addAll(List.of(HifiFeature.HIFI_SPEAKER_ITEM, HifiFeature.HIFI_CABLE,
                // Video
                VideoFeature.VIDEO_PLAYER_ITEM, DiscFeature.DRIVE_EMULATOR_ITEM, VideoFeature.EQUALIZER_ITEM,
                DisplayFeature.DISPLAY_ITEM, DisplayFeature.IMAGE_VIEWER_ITEM, VideoFeature.REMOTE, DisplayFeature.DISPLAY_QUILL,
                // Lighting
                LightingFeature.STAGE_LIGHT_ITEM, LightingFeature.LASER_PROJECTOR_ITEM, LightingFeature.LIGHTING_CONSOLE_ITEM,
                LightingFeature.LIGHTING_MONITOR_ITEM, LightingFeature.LIGHTING_LINK,
                // Broadcast
                BroadcastFeature.BASE_ITEM, BroadcastFeature.MAST_ITEM, BroadcastFeature.HORN_ITEM,
                BroadcastFeature.DIGITAL_HORN_ITEM, BroadcastFeature.ARRAY_ITEM,
                // Train
                TrainFeature.TRAIN_DISC_READER_ITEM, TrainFeature.TRAIN_SPEAKER_ITEM, TrainFeature.TRAIN_DETECTOR_ITEM,
                TrainFeature.TRAIN_SIGNAL_RECEIVER_ITEM, TrainFeature.ANNOUNCEMENT_SCHEDULE,
                // Portable
                PartyBoxFeature.PARTYBOX, PowerFeature.BATTERY, MegaphoneFeature.MEGAPHONE_WHITE, MegaphoneFeature.MEGAPHONE_BLACK,
                // Disc Launcher
                LauncherFeature.DISC_LAUNCHER, LauncherFeature.LAUNCHER_AIM_MODULE_ITEM, LauncherFeature.DISC_CELL_CHARGER_ITEM));
        order.addAll(LauncherFeature.DISC_CELLS);
        return order;
    }
}
