package org.haruyq.createmediasystems.lighting;

import java.util.Comparator;
import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.lighting.LightingSettings.Band;
import org.haruyq.createmediasystems.lighting.LightingSettings.ColorMode;
import org.haruyq.createmediasystems.lighting.LightingSettings.Group;
import org.haruyq.createmediasystems.lighting.LightingSettings.Mapping;
import org.haruyq.createmediasystems.lighting.LightingSettings.Motion;
import org.haruyq.createmediasystems.lighting.LightingSettings.Pattern;
import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllItems;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class LightingPackets {
    /** The most a Change's index may be: a colour slot's channel is the largest (COLORS * 3). */
    static final int MAX_INDEX = 64;

    /** What a preset key does: its Change's value. */
    public enum PresetAction {
        LOAD, SAVE, FACTORY
    }

    /** The media source a SOURCES change clears: its Change's value. */
    public enum Source {
        AUDIO, VIDEO
    }

    /** What a Fixture packet changes of its fixture. */
    public enum FixtureAction {
        /** Value: the group it moves to. */
        GROUP,
        /** Value: the colour slot it takes from its group (MAPPING_PER_FIXTURE). */
        COLOR,
        /** Value: places to move in its group's order (by its sign). */
        MOVE;

        @Nullable
        static FixtureAction byId(int id) {
            return id >= 0 && id < values().length ? values()[id] : null;
        }
    }

    /** How a player's right-click works a console control. */
    public enum Press {
        /** Steps it on (back, with Shift). */
        STEP,
        /** Sets it to the control's index (a group's row, a colour slot). */
        SELECT,
        /** Follows the player's aim while held (a fader, the aim pad). */
        DRAG,
        /** Takes the colour pointed at on the palette. */
        PICK,
        /** Loads, saves or applies a preset (see LightingControls). */
        PRESET,
        /** Not worked from the console's face. */
        NONE
    }

    /** What a control shows: on {@code console}, for {@code index} (its group, slot or channel), as now pointed at. */
    public record View(LightingConsoleBlock.Entity console, int index, boolean allGroups, boolean customPresets,
                       boolean saving) {
        /** As the monitors show it: not pointed at. */
        public static View shown(LightingConsoleBlock.Entity console) {
            return new View(console, 0, false, false, false);
        }

        LightingSettings settings() {
            return console.settings;
        }

        Group group() {
            return console.settings.selected();
        }
    }

    @FunctionalInterface
    interface Apply {
        /** Makes {@code change} to {@code console}; false if it can't. */
        boolean apply(LightingConsoleBlock.Entity console, LightingSettings settings, Group group, Change change,
                      int value, @Nullable Player player);
    }

    @FunctionalInterface
    interface Step {
        /** The value one {@code step} (1 or -1, or FAST_RANGE_STEP) moves it to; {@code fine} with Shift held. */
        float next(LightingSettings settings, Group group, int index, int step, boolean fine);
    }

    @FunctionalInterface
    interface Format {
        Component format(View view);
    }

    /**
     * A setting a console's controls change, as it goes over the wire (a Change's control: its {@code id}, kept
     * whatever the order here), with its label's lang key ({@code label.<key>}), how a press works it, what one step
     * moves it to, how it shows, and how a change sets it. Whatever a client draws but doesn't send (the preset
     * bank and save keys) isn't one: see ConsoleLayout.Button.
     */
    public enum Control {
        GROUP(0, "group", Press.SELECT,
                (s, g, i, step, fine) -> Math.floorMod(s.selectedGroup + step, LightingSettings.GROUPS),
                view -> LightingText.message(LightingText.MUTED, "select"),
                (console, s, g, change, value, player) -> set(() -> s.selectedGroup = value)),
        BRIGHTNESS(1, "brightness", Press.DRAG,
                (s, g, i, step, fine) -> s.groups[i].brightness + step * LightingSettings.BRIGHTNESS.step(),
                view -> percent(view.settings().groups[view.index()].brightness),
                (console, s, g, change, value, player) -> set(() -> g.brightness = change.value)),
        MASTER(2, "master", Press.DRAG,
                (s, g, i, step, fine) -> s.master + step * LightingSettings.MASTER.step(),
                view -> percent(view.settings().master),
                (console, s, g, change, value, player) -> set(() -> s.master = change.value)),
        COLOR(3, "color", Press.PICK, null, Control::selectedColor,
                (console, s, g, change, value, player) -> {
                    int slot = Mth.clamp(change.index, 0, LightingSettings.COLORS - 1);
                    g.colors[slot] = value & 0xFFFFFF;
                    g.colorCount = Math.max(g.colorCount, slot + 1);
                    s.selectedColor = slot;
                    return true;
                }),
        SLOT(4, "slot", Press.SELECT,
                (s, g, i, step, fine) -> Math.floorMod(s.selectedColor + step, LightingSettings.COLORS),
                Control::selectedColor,
                (console, s, g, change, value, player) -> set(() -> s.selectedColor = value)),
        COUNT(5, "count", Press.STEP, (s, g, i, step, fine) -> g.colorCount + step,
                view -> LightingText.number(view.group().colorCount),
                (console, s, g, change, value, player) -> set(() -> g.colorCount = value)),
        /** Index: a colour slot's channel, slot * 3 + channel (0 red, 1 green, 2 blue) on the wire. */
        RGB(6, "rgb", Press.STEP,
                (s, g, i, step, fine) -> (g.color(s.selectedColor) >> (2 - i) * 8 & 255) + step * (fine ? 1 : 8),
                Control::selectedColor,
                (console, s, g, change, value, player) -> {
                    int slot = Mth.clamp(change.index / 3, 0, LightingSettings.COLORS - 1);
                    int shift = (2 - change.index % 3) * 8;
                    g.colors[slot] = g.colors[slot] & ~(255 << shift) | Mth.clamp(value, 0, 255) << shift;
                    g.colorCount = Math.max(g.colorCount, slot + 1);
                    return true;
                }),
        MAPPING(7, "mapping", Press.STEP, (s, g, i, step, fine) -> LightingSettings.cycle(g.mapping, step).ordinal(),
                view -> LightingText.mode(view.group().mapping),
                (console, s, g, change, value, player) -> set(() -> g.mapping = LightingSettings.byId(Mapping.values(), value))),
        VIDEO(8, "video", Press.STEP, (s, g, i, step, fine) -> LightingSettings.cycle(g.colorMode, 1).ordinal(),
                view -> LightingText.on(view.group().colorMode == ColorMode.VIDEO),
                (console, s, g, change, value, player) -> set(() -> g.colorMode = LightingSettings.byId(ColorMode.values(), value))),
        AUDIO(9, "audio", Press.STEP, (s, g, i, step, fine) -> g.audio ? 0 : 1,
                view -> LightingText.on(view.group().audio),
                (console, s, g, change, value, player) -> set(() -> g.audio = value != 0)),
        BAND(10, "band", Press.STEP, (s, g, i, step, fine) -> LightingSettings.cycle(g.audioBand, step).ordinal(),
                view -> LightingText.mode(view.group().audioBand),
                (console, s, g, change, value, player) -> set(() -> g.audioBand = LightingSettings.byId(Band.values(), value))),
        MOTION(11, "motion", Press.STEP, (s, g, i, step, fine) -> LightingSettings.cycle(g.motion, step).ordinal(),
                view -> LightingText.mode(view.group().motion),
                (console, s, g, change, value, player) -> set(() -> g.motion = LightingSettings.byId(Motion.values(), value))),
        PATTERN(12, "pattern", Press.STEP, (s, g, i, step, fine) -> LightingSettings.cycle(g.pattern, step).ordinal(),
                view -> LightingText.mode(view.group().pattern),
                (console, s, g, change, value, player) -> set(() -> g.pattern = LightingSettings.byId(Pattern.values(), value))),
        RANGE(13, "range", Press.STEP, (s, g, i, step, fine) -> g.range + step * LightingSettings.RANGE.step(),
                view -> {
                    float min = view.group().range;
                    float max = min;
                    if (view.allGroups()) {
                        for (Group group : view.settings().groups) {
                            min = Math.min(min, group.range);
                            max = Math.max(max, group.range);
                        }
                    }
                    return LightingText.number((min == max ? String.valueOf(Math.round(min))
                            : Math.round(min) + "–" + Math.round(max)) + " m");
                },
                (console, s, g, change, value, player) -> set(() -> g.range = change.value)),
        SPREAD(14, "spread", Press.STEP, (s, g, i, step, fine) -> g.spread + step * LightingSettings.SPREAD.step(),
                view -> LightingText.number(Math.round(view.group().spread) + "°"),
                (console, s, g, change, value, player) -> set(() -> g.spread = change.value)),
        SPEED(15, "speed", Press.STEP, (s, g, i, step, fine) -> g.speed + step * LightingSettings.SPEED.step(),
                view -> LightingText.number(String.format(Locale.ROOT, "%.2f", view.group().speed)),
                (console, s, g, change, value, player) -> set(() -> g.speed = change.value)),
        /** Value: the pan; other: the tilt. */
        AIM(16, "aim", Press.DRAG, null,
                view -> LightingText.number(Math.round(view.group().pan) + "° / " + Math.round(view.group().tilt) + "°"),
                (console, s, g, change, value, player) -> set(() -> {
                    g.pan = change.value;
                    g.tilt = change.other;
                })),
        ENABLED(17, "enabled", Press.STEP, (s, g, i, step, fine) -> s.enabled ? 0 : 1,
                view -> LightingText.on(view.settings().enabled),
                (console, s, g, change, value, player) -> set(() -> s.enabled = value != 0)),
        CYCLE(18, "cycle", Press.STEP, (s, g, i, step, fine) -> g.colorCycle ? 0 : 1,
                view -> LightingText.on(view.group().colorCycle),
                (console, s, g, change, value, player) -> set(() -> g.colorCycle = value != 0)),
        /** Value: which way to step through the players near, by its sign. */
        TARGET(19, "target", Press.STEP, (s, g, i, step, fine) -> step,
                view -> {
                    Group group = view.group();
                    Player target = group.target == null || view.console().getLevel() == null ? null
                            : view.console().getLevel().getPlayerByUUID(group.target);
                    return target == null ? LightingText.text("—", LightingText.MUTED)
                            : LightingText.text(target.getName(), LightingText.MODE);
                },
                (console, s, g, change, value, player) -> {
                    if (player != null && console.getLevel() != null) {
                        var players = console.getLevel().players().stream().filter(p -> p.isAlive() && !p.isSpectator()
                                && p.distanceToSqr(player) < 128 * 128).sorted(Comparator.comparing(Player::getUUID)).toList();
                        if (!players.isEmpty()) {
                            int current = -1;
                            for (int i = 0; i < players.size(); i++) {
                                if (players.get(i).getUUID().equals(g.target)) {
                                    current = i;
                                }
                            }
                            g.target = players.get(Math.floorMod(current + (value < 0 ? -1 : 1), players.size())).getUUID();
                        }
                    }
                    return true;
                }),
        /** Index: the preset's slot; value: a PresetAction. */
        PRESET(20, "preset", Press.PRESET, null,
                view -> LightingText.message(view.saving() ? LightingText.WARNING : LightingText.TEXT,
                        view.saving() ? "preset_save_help" : "preset_help",
                        LightingText.presetName(view.console(), view.index(), view.customPresets())),
                (console, s, g, change, value, player) -> {
                    int slot = change.index;
                    switch (PresetAction.values()[value]) {
                        case SAVE -> console.presets[slot] = g.save();
                        case FACTORY -> g.preset(LightingSettings.Preset.values()[slot], player == null ? null : player.getUUID());
                        case LOAD -> {
                            if (console.presets[slot] == null) {
                                return false;
                            }
                            g.load(console.presets[slot]);
                        }
                    }
                    return true;
                }) {
            @Override
            boolean valid(Change change) {
                return change.index < LightingSettings.PRESETS && change.value >= 0
                        && change.value <= PresetAction.values().length - 1;
            }
        },
        /** Value: the Source it clears. */
        SOURCES(21, "sources", Press.STEP, (s, g, i, step, fine) -> (step < 0 ? Source.AUDIO : Source.VIDEO).ordinal(),
                view -> LightingText.message("source_state",
                        LightingText.source(view.console().audioSource() != null, view.console().hasAudio()),
                        LightingText.source(view.console().videoSource() != null, view.console().hasVideo())),
                (console, s, g, change, value, player) -> {
                    if (value == Source.AUDIO.ordinal()) {
                        console.setAudioSource(null);
                    } else {
                        console.setVideoSource(null);
                    }
                    return true;
                }) {
            @Override
            Component readout(View view) {
                return LightingText.text("A ", LightingText.TEXT)
                        .append(LightingText.source(view.console().audioSource() != null, view.console().hasAudio()))
                        .append("  V ")
                        .append(LightingText.source(view.console().videoSource() != null, view.console().hasVideo()));
            }
        },
        /** Value: how far every group's range moves, 1 or FAST_RANGE_STEP either way. */
        RANGE_ALL(24, "range", Press.NONE, null, view -> Component.empty(),
                (console, s, g, change, value, player) -> set(() -> s.shiftRange(value))) {
            @Override
            boolean valid(Change change) {
                return Math.abs(change.value) == 1 || Math.abs(change.value) == LightingSettings.FAST_RANGE_STEP;
            }
        },
        /** Value: flashes a second, 0 for none. A step past either end comes round to the other. */
        STROBE(25, "strobe", Press.STEP,
                (s, g, i, step, fine) -> Math.floorMod(Math.round(g.strobe) + step, (int) LightingSettings.STROBE.max() + 1),
                view -> view.group().strobe <= 0 ? LightingText.on(false)
                        : LightingText.number(Math.round(view.group().strobe) + " Hz"),
                (console, s, g, change, value, player) -> set(() -> g.strobe = change.value));

        private static final Control[] BY_ID = new Control[26];

        static {
            for (Control control : values()) {
                BY_ID[control.id] = control;
            }
        }

        /** Its id on the wire. */
        public final int id;
        /** Its label's lang key, after {@code create_media_systems.lighting.label.}. */
        public final String key;
        public final Press press;
        @Nullable
        private final Step step;
        private final Format format;
        private final Apply apply;

        Control(int id, String key, Press press, @Nullable Step step, Format format, Apply apply) {
            this.id = id;
            this.key = key;
            this.press = press;
            this.step = step;
            this.format = format;
            this.apply = apply;
        }

        @Nullable
        public static Control byId(int id) {
            return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
        }

        /** Whether its index names a group (it applies to that group, not the selected one). */
        public boolean indexIsGroup() {
            return this == GROUP || this == BRIGHTNESS;
        }

        /** Whether a scroll (or a press, with Press.STEP) steps it. */
        public boolean steps() {
            return step != null || this == COLOR;
        }

        /**
         * The change one {@code step} sends: the control it changes (scrolling the palette picks the slot), its
         * index on the wire and its value, as {@code settings} are now.
         */
        public Stepped step(LightingSettings settings, int index, int step, boolean fine) {
            Control control = this == COLOR ? SLOT : this;
            Group group = settings.selected();
            return new Stepped(control, control == RGB ? index + settings.selectedColor * 3 : index,
                    control.step.next(settings, group, index, step, fine));
        }

        public Component format(View view) {
            return format.format(view);
        }

        /** As the monitors show it, after its label. */
        Component readout(View view) {
            return format(view);
        }

        boolean valid(Change change) {
            return true;
        }

        private static boolean set(Runnable change) {
            change.run();
            return true;
        }

        private static Component percent(float value) {
            return LightingText.number(Math.round(value * 100) + "%");
        }

        private static Component selectedColor(View view) {
            LightingSettings settings = view.settings();
            int color = view.group().color(settings.selectedColor);
            return LightingText.number(settings.selectedColor + 1).append(LightingText.text(" / ", LightingText.TEXT))
                    .append(LightingText.text(String.format(Locale.ROOT, "#%06X", color), LightingText.readable(color)));
        }
    }

    /** A step's change: what it sends (see Control.step). */
    public record Stepped(Control control, int index, float value) {
    }

    private LightingPackets() {
    }

    public record Change(BlockPos console, int group, int control, int index, float value, float other)
            implements CustomPacketPayload {
        public static final Type<Change> TYPE = new Type<>(CreateMediaSystems.id("lighting_change"));
        public static final StreamCodec<ByteBuf, Change> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Change::console, ByteBufCodecs.VAR_INT, Change::group,
                ByteBufCodecs.VAR_INT, Change::control, ByteBufCodecs.VAR_INT, Change::index,
                ByteBufCodecs.FLOAT, Change::value, ByteBufCodecs.FLOAT, Change::other, Change::new);

        public Change(BlockPos console, int group, Control control, int index, float value, float other) {
            this(console, group, control.id, index, value, other);
        }

        @Override
        public Type<Change> type() {
            return TYPE;
        }
    }

    public static boolean apply(LightingConsoleBlock.Entity console, Change change, Player player) {
        Control control = Control.byId(change.control);
        if (control == null || change.group < 0 || change.group >= LightingSettings.GROUPS
                || !Float.isFinite(change.value) || !Float.isFinite(change.other) || change.index < 0
                || change.index >= MAX_INDEX || !control.valid(change)) {
            return false;
        }
        LightingSettings settings = console.settings;
        if (!control.apply.apply(console, settings, settings.groups[change.group], change, Math.round(change.value),
                player)) {
            return false;
        }
        settings.normalize();
        return true;
    }

    public record Fixture(BlockPos light, int action, int value) implements CustomPacketPayload {
        public static final Type<Fixture> TYPE = new Type<>(CreateMediaSystems.id("lighting_fixture"));
        public static final StreamCodec<ByteBuf, Fixture> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Fixture::light, ByteBufCodecs.VAR_INT, Fixture::action,
                ByteBufCodecs.VAR_INT, Fixture::value, Fixture::new);

        public Fixture(BlockPos light, FixtureAction action, int value) {
            this(light, action.ordinal(), value);
        }

        @Override
        public Type<Fixture> type() {
            return TYPE;
        }
    }

    public record Link(BlockPos target) implements CustomPacketPayload {
        public static final Type<Link> TYPE = new Type<>(CreateMediaSystems.id("lighting_link"));
        public static final StreamCodec<ByteBuf, Link> CODEC = BlockPos.STREAM_CODEC.map(Link::new, Link::target);

        @Override
        public Type<Link> type() {
            return TYPE;
        }
    }

    private static boolean hand(Player player, BlockPos pos) {
        return player.mayBuild() && SableCompat.mayInteract(player.level(), player, pos)
                && (player.getMainHandItem().isEmpty() || player.getMainHandItem().is(AllItems.WRENCH.get()));
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Change.TYPE, Change.CODEC, (change, context) -> {
            Player player = context.player();
            LightingConsoleBlock.Entity entity = Reach.blockEntity(player, change.console, Reach.HAND,
                    LightingConsoleBlock.Entity.class);
            if (entity != null && entity.controller() == entity && hand(player, change.console)
                    && apply(entity, change, player)) {
                entity.changed();
            }
        });
        registrar.playToServer(Fixture.TYPE, Fixture.CODEC, (change, context) -> {
            Player player = context.player();
            StageLightBlock.Entity light = Reach.blockEntity(player, change.light, Reach.HAND, StageLightBlock.Entity.class);
            if (light == null || !hand(player, change.light)) {
                return;
            }
            LightingConsoleBlock.Entity console = light.controller();
            if (console != null && !SableCompat.mayInteract(player.level(), player, console.getBlockPos())) {
                return;
            }
            FixtureAction action = FixtureAction.byId(change.action);
            if (action == FixtureAction.GROUP && console != null) {
                console.bind(light, Mth.clamp(change.value, 0, LightingSettings.GROUPS - 1));
            } else if (action == FixtureAction.COLOR) {
                light.individualColor = Mth.clamp(change.value, 0, LightingSettings.COLORS - 1);
            } else if (action == FixtureAction.MOVE && console != null) {
                console.move(light.getBlockPos(), change.value);
            }
            light.changed();
        });
        registrar.playToServer(Link.TYPE, Link.CODEC,
                (link, context) -> LightingLinkItem.link(context.player(), link.target));
    }
}
