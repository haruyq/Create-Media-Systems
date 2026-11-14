package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.midi.ShortMessage;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.MidiPort;
import org.haruyq.createmediasystems.lighting.LightingPackets.Change;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.haruyq.createmediasystems.lighting.LightingPackets.PresetAction;
import org.haruyq.createmediasystems.lighting.LightingPackets.Stepped;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A real MIDI controller plugged into this computer, working the lighting console its player is at: the one they last
 * pointed at, while they stay within reach of it. What each fader, knob and key of a controller works is its map; the
 * console's own changes go out for them, so a server sees nothing new. Only a controller with a map is opened, so a
 * MIDI keyboard is left to the instruments.
 */
public final class LightingMidi {
    /**
     * The maps of the controllers known: what they send is as QLC+'s input profiles list it (Akai-APCMini.qxi,
     * Akai-APCMini-mk2.qxi and KORG-nanoKONTROL2.qxi). The APC mini's pads are notes 0 to 63 from the bottom left, the
     * keys under its faders 64 to 71 and those beside its pads 82 to 89, top down; the mk2's are 100 to 107 and 112 to
     * 119, so one map takes both. The nanoKONTROL2's keys are control changes: S 32 to 39, M 48 to 55, R 64 to 71.
     *
     * <p>The Pioneer DJ DDJ-FLX4's is as Mixxx's mapping lists it (Pioneer-DDJ-FLX4.midi.xml): its decks send the same
     * numbers, the left on channel 1 and the right on 2, their pads on 8 and 10 (hot cues 0 to 7, the sampler's 48 to
     * 55), and the mixer's filter knobs on 7. The left deck works the selected group (its fader the brightness, its
     * equaliser the colour, its pads the group), the right the whole console (its fader the master, its pads the
     * presets, and the sampler's the colour slots); the filter knobs, level at their middle, aim.
     */
    static final List<String> BUILT_IN = List.of(
            "DDJ-FLX4: ch1.cc19=selected ch2.cc19=master ch1.cc7=red ch1.cc11=green ch1.cc15=blue"
                    + " ch2.cc7=range ch2.cc11=spread ch2.cc15=speed ch7.cc23=pan ch7.cc24=tilt"
                    + " ch8.note0-5=group ch10.note0-5=preset ch10.note48-55=slot ch1.note11=enabled ch1.note12=strobe"
                    + " ch1.note88=motion ch2.note11=audio ch2.note12=cycle ch2.note88=pattern",
            "APC mini: cc48-53=brightness cc54=pan cc55=tilt cc56=master note0-63=color"
                    + " note64-69=group note70=motion note71=pattern note82-87=preset note88=strobe note89=enabled"
                    + " note100-105=group note106=motion note107=pattern note112-117=preset note118=strobe note119=enabled",
            "nanoKONTROL2: cc0-5=brightness cc7=master cc16=red cc17=green cc18=blue cc19=range cc20=spread cc21=speed"
                    + " cc22=pan cc23=tilt cc32-37=group cc48-55=slot cc64-69=preset cc41=enabled cc42=audio cc43=motion"
                    + " cc44=pattern cc45=strobe cc46=cycle cc58=mapping cc59=count cc60=video cc61=band cc62=target");

    private static final ModConfigSpec.ConfigValue<List<? extends String>> CONTROLLERS = ClientConfig.BUILDER
            .comment("MIDI controllers that work the lighting console you point at, beside the Akai APC mini, the KORG nanoKONTROL2 and the Pioneer DJ DDJ-FLX4, which work as they are. Each entry is part of the device's name, a colon, then what its controls work: 'X-TOUCH: cc1-6=brightness cc9=master note8-13=group'. cc and note numbers are those the device sends, on any MIDI channel, or on one alone with it in front ('ch2.cc19=master'); a range works the groups, slots or keys in turn, and a number after a name starts from that one ('cc20=brightness3'). Faders and knobs: brightness (6 groups), selected (the selected group's brightness), master, red, green, blue, range, spread, speed, pan, tilt. Keys: group (6), preset (6), slot (8), color (the 64 palette cells, from the bottom left), enabled, audio, band, cycle, pattern, motion, target, strobe, count, mapping, video. An entry here comes before the built-in ones; one with nothing after the colon keeps that device closed.")
            .defineListAllowEmpty("lightingMidiControllers", List.of(), () -> "", entry -> entry instanceof String);

    /** A binding: its channel if it has one, cc or note, its number or range, and what it works, from which. */
    private static final Pattern BOUND = Pattern.compile(
            "(?<![\\w.])(?:ch(\\d{1,2})\\.)?(cc|note)(\\d{1,3})(?:-(\\d{1,3}))?=([a-z]+)(\\d{0,2})");
    /** A brightness binding's index for the selected group's, whichever that is. */
    static final int SELECTED = -1;
    /** What MidiPort's status says once its input is open. */
    private static final String CONNECTED = "keyboard.midi_connected";

    /** A fader, knob or key's latest value (0 to 127) not passed on yet: a controller sends them by the hundred. */
    private static final Map<Binding, Integer> MOVED = new ConcurrentHashMap<>();
    private static final Queue<Binding> PRESSED = new ConcurrentLinkedQueue<>();
    private static volatile List<Profile> profiles = List.of();
    @Nullable
    private static volatile MidiPort port;
    private static boolean announced;
    private static int ticks;

    private LightingMidi() {
    }

    /** What a control of a controller works: a console control, and which of its groups, slots, keys or channels. */
    record Binding(Control control, int index) {
        /** Whether it follows the control's value (a fader, a knob), rather than being pressed. */
        boolean continuous() {
            return switch (control) {
                case BRIGHTNESS, MASTER, RANGE, SPREAD, SPEED, RGB, AIM -> true;
                default -> false;
            };
        }
    }

    /** A controller's map: part of its device's name (lower case), and its bindings by key (see key). */
    record Profile(String device, Map<Integer, Binding> map) {
        /** What the control sending {@code number} on {@code channel} (1 to 16) works: that channel's, else any channel's. */
        @Nullable
        Binding binding(int channel, boolean note, int number) {
            Binding own = map.get(key(channel, note, number));
            return own != null ? own : map.get(key(0, note, number));
        }
    }

    /**
     * A control change's or a note's place in a map: as QLC+ numbers its MIDI channels, notes after the 128 controls,
     * and those of one MIDI channel ({@code channel} 1 to 16; 0 for any) 256 on for each.
     */
    static int key(int channel, boolean note, int number) {
        return channel << 8 | (note ? 128 + number : number);
    }

    /** {@code entries}' maps (lightingMidiControllers), then the built-in ones. */
    static List<Profile> profiles(List<? extends String> entries) {
        List<Profile> parsed = new ArrayList<>();
        entries.forEach(entry -> parsed.add(parse(entry)));
        BUILT_IN.forEach(entry -> parsed.add(parse(entry)));
        return List.copyOf(parsed);
    }

    /** The first of {@code profiles} for the device named {@code name}; null for a device none is for. */
    @Nullable
    static Profile profile(List<Profile> profiles, String name) {
        String device = name.toLowerCase(Locale.ROOT);
        return profiles.stream().filter(profile -> device.contains(profile.device())).findFirst().orElse(null);
    }

    /** An entry's map: whatever of it isn't a binding, or names no control, is left out. */
    static Profile parse(String entry) {
        int colon = entry.indexOf(':');
        Map<Integer, Binding> map = new HashMap<>();
        Matcher bound = BOUND.matcher(colon < 0 ? "" : entry.substring(colon + 1).toLowerCase(Locale.ROOT));
        while (bound.find()) {
            Binding first = target(bound.group(5));
            int channel = bound.group(1) == null ? 0 : Integer.parseInt(bound.group(1));
            if (first == null || bound.group(1) != null && (channel < 1 || channel > 16)) {
                continue;
            }
            int from = Integer.parseInt(bound.group(3));
            int to = bound.group(4) == null ? from : Integer.parseInt(bound.group(4));
            int start = first.index() + (bound.group(6).isEmpty() ? 0 : Integer.parseInt(bound.group(6)) - 1);
            for (int number = from; number <= Math.min(to, 127); number++) {
                int index = first.index() == SELECTED ? SELECTED : start + number - from;
                if (index == SELECTED || index >= 0 && index < count(first.control())) {
                    map.put(key(channel, bound.group(2).equals("note"), number), new Binding(first.control(), index));
                }
            }
        }
        return new Profile((colon < 0 ? entry : entry.substring(0, colon)).strip().toLowerCase(Locale.ROOT), Map.copyOf(map));
    }

    /** The first binding {@code name} stands for; null if it names nothing a controller works. */
    @Nullable
    private static Binding target(String name) {
        int channel = List.of("red", "green", "blue").indexOf(name);
        if (channel >= 0) {
            return new Binding(Control.RGB, channel);
        }
        if (name.equals("pan") || name.equals("tilt")) {
            return new Binding(Control.AIM, name.equals("pan") ? 0 : 1);
        }
        if (name.equals("selected")) {
            return new Binding(Control.BRIGHTNESS, SELECTED);
        }
        for (Control control : Control.values()) {
            // Not the sources' key: all it does is unlink them.
            if (control.key.equals(name) && control.press != LightingPackets.Press.NONE && control != Control.RGB
                    && control != Control.AIM && control != Control.SOURCES) {
                return new Binding(control, 0);
            }
        }
        return null;
    }

    /** How many of {@code control} a console has: the indices a binding may take. */
    private static int count(Control control) {
        return switch (control) {
            case BRIGHTNESS, GROUP -> LightingSettings.GROUPS;
            case PRESET -> LightingSettings.PRESETS;
            case SLOT -> LightingSettings.COLORS;
            case COLOR -> ConsoleLayout.PALETTE_CELLS * ConsoleLayout.PALETTE_CELLS;
            case RGB -> 3;
            case AIM -> 2;
            default -> 1;
        };
    }

    /**
     * The change {@code binding} makes at {@code value} (0 to 127; a key's, any above 0) to the console at
     * {@code console}, as its {@code settings} are: a fader or knob's whole travel is its setting's, pan and tilt
     * level at 64; a key does what a right-click on it does ({@code customPresets}: the saved presets' bank shows).
     */
    static Change change(BlockPos console, LightingSettings settings, boolean customPresets, Binding binding, int value) {
        Control control = binding.control();
        int index = binding.index();
        int group = settings.selectedGroup;
        float level = value / 127f;
        return switch (control) {
            case BRIGHTNESS -> new Change(console, index == SELECTED ? group : index, control, 0, level, 0);
            case MASTER -> new Change(console, group, control, 0, level, 0);
            case RANGE -> new Change(console, group, control, 0, travel(LightingSettings.RANGE, level), 0);
            case SPREAD -> new Change(console, group, control, 0, travel(LightingSettings.SPREAD, level), 0);
            case SPEED -> new Change(console, group, control, 0, travel(LightingSettings.SPEED, level), 0);
            case RGB -> new Change(console, group, control, settings.selectedColor * 3 + index, Math.round(level * 255), 0);
            case AIM -> {
                float side = Math.max(-1, (value - 64) / 63f);
                LightingSettings.Group aimed = settings.selected();
                yield new Change(console, group, control, 0, index == 0 ? side * LightingSettings.PAN.max() : aimed.pan,
                        index == 0 ? aimed.tilt : side * LightingSettings.TILT.max());
            }
            case GROUP -> new Change(console, index, control, 0, index, 0);
            case SLOT -> new Change(console, group, control, 0, index, 0);
            case PRESET -> new Change(console, group, control, index,
                    (customPresets ? PresetAction.LOAD : PresetAction.FACTORY).ordinal(), 0);
            case COLOR -> new Change(console, group, control, settings.selectedColor, LightingSettings.palette(
                    index % ConsoleLayout.PALETTE_CELLS, ConsoleLayout.PALETTE_CELLS - 1 - index / ConsoleLayout.PALETTE_CELLS), 0);
            default -> {
                Stepped next = control.step(settings, index, 1, false);
                yield new Change(console, group, next.control(), next.index(), next.value(), 0);
            }
        };
    }

    private static float travel(LightingSettings.Setting setting, float level) {
        return Mth.lerp(level, setting.min(), setting.max());
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    /** On the device's thread. */
    private static void receive(ShortMessage message) {
        MidiPort from = port;
        int command = message.getCommand();
        boolean note = command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF;
        Profile profile = from == null || !note && command != ShortMessage.CONTROL_CHANGE ? null
                : profile(profiles, from.status().selected());
        Binding binding = profile == null ? null : profile.binding(message.getChannel() + 1, note, message.getData1());
        if (binding == null) {
            return;
        }
        int value = command == ShortMessage.NOTE_OFF ? 0 : message.getData2();
        if (binding.continuous()) {
            MOVED.put(binding, value);
        } else if (note ? value > 0 : value >= 64) {
            // A key that sends control changes is down from 64 up.
            PRESSED.add(binding);
        }
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LightingConsoleBlock.Entity console = mc.player == null ? null : LightingControls.focused();
        MidiPort open = port;
        if (console == null) {
            if (open != null) {
                open.close();
                port = null;
            }
            MOVED.clear();
            PRESSED.clear();
            announced = false;
            return;
        }
        if (open == null) {
            List<Profile> known = profiles = profiles(CONTROLLERS.get());
            // ponytail: one controller at a time, the first with a map; a MidiPort each if two are to work together.
            port = open = new MidiPort(null, LightingMidi::receive, () -> {
            }, input -> {
                Profile profile = profile(known, input.id());
                return profile != null && !profile.map().isEmpty();
            });
            ticks = 0;
        }
        // Again every two seconds: for a controller plugged in since, or one an instrument's screen closed.
        if (ticks++ % 40 == 0) {
            open.scan();
        }
        MidiPort.Status status = open.status();
        boolean connected = status.message().equals(CONNECTED);
        if (connected && !announced) {
            status.inputs().stream().filter(input -> input.id().equals(status.selected())).findFirst()
                    .ifPresent(input -> LightingControls.notice(LightingText.message(LightingText.ON, "midi_connected",
                            Component.literal(input.name()))));
        }
        announced = connected;
        if (!LightingControls.hand()) {
            MOVED.clear();
            PRESSED.clear();
            return;
        }
        for (Binding binding : MOVED.keySet()) {
            Integer value = MOVED.remove(binding);
            if (value != null) {
                send(console, binding, value);
            }
        }
        for (Binding binding = PRESSED.poll(); binding != null; binding = PRESSED.poll()) {
            send(console, binding, 127);
        }
    }

    private static void send(LightingConsoleBlock.Entity console, Binding binding, int value) {
        Change change = change(console.getBlockPos(), console.settings, LightingControls.customPresets(console), binding, value);
        if (LightingPackets.apply(console, change, Minecraft.getInstance().player)) {
            PacketDistributor.sendToServer(change);
        }
    }
}
