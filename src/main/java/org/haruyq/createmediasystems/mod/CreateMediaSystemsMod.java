package org.haruyq.createmediasystems.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.broadcast.BroadcastFeature;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import org.haruyq.createmediasystems.blocks.component.m700.HifiFeature;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardFeature;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.items.megaphone.megaphone30.MegaphoneFeature;
import org.haruyq.createmediasystems.p2p.P2pFeature;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxFeature;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoFeature;
import org.haruyq.createmediasystems.items.power.PowerFeature;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.PxS1100Feature;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.blocks.train.TrainFeature;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;

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
    private static final List<Feature> FEATURES = List.of(new StreamFeature(), new PowerFeature(), new PxS1100Feature(), new MegaphoneFeature(),
            new PartyBoxFeature(), new LauncherFeature(), new AudioFeature(), new TrainFeature(), new DiscFeature(), new VideoFeature(),
            new DisplayFeature(), new LightingFeature(), new HifiFeature(), new P2pFeature(), new BroadcastFeature(),
            new KeyboardFeature(), new PianoFeature());

    public static final Supplier<CreativeModeTab> TAB = CreateMediaSystems.TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_media_systems"))
            .icon(() -> new ItemStack(AudioFeature.CUSTOM_DISC.get()))
            .displayItems((params, output) -> {
                Set<Item> items = new LinkedHashSet<>();
                sections().values().forEach(section -> section.forEach(item -> items.add(item.get())));
                CreateMediaSystems.ITEMS.getEntries().forEach(item -> items.add(item.get()));
                items.forEach(output::accept);
            })
            .build());

    public CreateMediaSystemsMod(IEventBus modBus, ModContainer container) {
        CreateMediaSystems.register(modBus, container, FEATURES);
    }

    /**
     * The tab's sections in order, each a system's devices followed by its tools. The client shows each under a banner,
     * the sprite tab_section/<name>, titled itemGroup.create_media_systems.<name> (see TabSections).
     */
    public static Map<String, List<Supplier<? extends Item>>> sections() {
        List<Supplier<? extends Item>> audio = new ArrayList<>(List.of(
                AudioFeature.DISC_READER_ITEM, AudioFeature.SPEAKER_ITEM, AudioFeature.RANGE_QUILL));
        audio.addAll(HifiFeature.hifiUnits());
        audio.addAll(List.of(HifiFeature.HIFI_SPEAKER_ITEM, HifiFeature.HIFI_CABLE,
                KeyboardFeature.KEYBOARD_ITEM, PxS1100Feature.ITEM, PianoFeature.PIANO_ITEM));
        List<Supplier<? extends Item>> portable = new ArrayList<>(List.of(
                PartyBoxFeature.PARTYBOX, PowerFeature.BATTERY, MegaphoneFeature.MEGAPHONE_WHITE, MegaphoneFeature.MEGAPHONE_BLACK,
                LauncherFeature.DISC_LAUNCHER, LauncherFeature.LAUNCHER_AIM_MODULE_ITEM, LauncherFeature.DISC_CELL_CHARGER_ITEM));
        portable.addAll(LauncherFeature.DISC_CELLS);

        Map<String, List<Supplier<? extends Item>>> sections = new LinkedHashMap<>();
        sections.put("media", List.of(
                AudioFeature.CUSTOM_DISC, DiscFeature.CD, DiscFeature.CASSETTE, DiscFeature.DVD, DiscFeature.BLU_RAY));
        sections.put("audio", audio);
        sections.put("video", List.of(
                VideoFeature.VIDEO_PLAYER_ITEM, DiscFeature.DRIVE_EMULATOR_ITEM, VideoFeature.EQUALIZER_ITEM,
                DisplayFeature.DISPLAY_ITEM, DisplayFeature.IMAGE_VIEWER_ITEM, VideoFeature.REMOTE, DisplayFeature.DISPLAY_QUILL));
        sections.put("lighting", List.of(
                LightingFeature.STAGE_LIGHT_ITEM, LightingFeature.LASER_PROJECTOR_ITEM, LightingFeature.LIGHTING_CONSOLE_ITEM,
                LightingFeature.LIGHTING_MONITOR_ITEM, LightingFeature.LIGHTING_LINK));
        sections.put("broadcast", List.of(
                BroadcastFeature.BASE_ITEM, BroadcastFeature.MAST_ITEM, BroadcastFeature.HORN_ITEM,
                BroadcastFeature.DIGITAL_HORN_ITEM, BroadcastFeature.ARRAY_ITEM,
                TrainFeature.TRAIN_DISC_READER_ITEM, TrainFeature.TRAIN_SPEAKER_ITEM, TrainFeature.TRAIN_DETECTOR_ITEM,
                TrainFeature.TRAIN_SIGNAL_RECEIVER_ITEM, TrainFeature.ANNOUNCEMENT_SCHEDULE));
        sections.put("portable", portable);
        return sections;
    }
}
