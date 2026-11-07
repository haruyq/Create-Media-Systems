package org.haruyq.createmediasystems.lighting;

import java.util.Comparator;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
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
    public enum Control {
        GROUP, BRIGHTNESS, MASTER, COLOR, SLOT, COUNT, RGB, MAPPING, VIDEO, AUDIO, BAND, MOTION, PATTERN,
        RANGE, SPREAD, SPEED, AIM, ENABLED, CYCLE, TARGET, PRESET, SOURCES, PRESET_BANK, PRESET_SAVE, RANGE_ALL
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

        @Override
        public Type<Change> type() {
            return TYPE;
        }
    }

    public static boolean apply(LightingConsoleBlock.Entity console, Change change, Player player) {
        if (change.group < 0 || change.group >= LightingSettings.GROUPS || change.control < 0
                || change.control >= Control.values().length || !Float.isFinite(change.value)
                || !Float.isFinite(change.other) || change.index < 0 || change.index >= 64
                || change.control == Control.PRESET_BANK.ordinal() || change.control == Control.PRESET_SAVE.ordinal()
                || change.control == Control.RANGE_ALL.ordinal() && Math.abs(change.value) != 1 && Math.abs(change.value) != 10
                || change.control == Control.PRESET.ordinal() && (change.index >= 6 || change.value < 0 || change.value > 2)) {
            return false;
        }
        LightingSettings settings = console.settings;
        LightingSettings.Group group = settings.groups[change.group];
        int value = Math.round(change.value);
        switch (Control.values()[change.control]) {
            case GROUP -> settings.selectedGroup = Mth.clamp(value, 0, LightingSettings.GROUPS - 1);
            case BRIGHTNESS -> group.brightness = Mth.clamp(change.value, 0, 1);
            case MASTER -> settings.master = Mth.clamp(change.value, 0, 1);
            case COLOR -> {
                int slot = Mth.clamp(change.index, 0, LightingSettings.COLORS - 1);
                group.colors[slot] = value & 0xFFFFFF;
                group.colorCount = Math.max(group.colorCount, slot + 1);
                settings.selectedColor = slot;
            }
            case SLOT -> settings.selectedColor = Mth.clamp(value, 0, LightingSettings.COLORS - 1);
            case COUNT -> group.colorCount = Mth.clamp(value, 1, LightingSettings.COLORS);
            case RGB -> {
                int slot = Mth.clamp(change.index / 3, 0, LightingSettings.COLORS - 1);
                int shift = (2 - change.index % 3) * 8;
                group.colors[slot] = group.colors[slot] & ~(255 << shift) | Mth.clamp(value, 0, 255) << shift;
                group.colorCount = Math.max(group.colorCount, slot + 1);
            }
            case MAPPING -> group.mapping = Mth.clamp(value, 0, 3);
            case VIDEO -> group.colorMode = Mth.clamp(value, 0, 1);
            case AUDIO -> group.audio = value != 0;
            case BAND -> group.audioBand = Mth.clamp(value, 0, 3);
            case MOTION -> group.motion = Mth.clamp(value, 0, 3);
            case PATTERN -> group.pattern = Mth.clamp(value, 0, 4);
            case RANGE -> group.range = Mth.clamp(change.value, 2, LightingSettings.MAX_RANGE);
            case RANGE_ALL -> settings.shiftRange(value);
            case SPREAD -> group.spread = Mth.clamp(change.value, 1, 70);
            case SPEED -> group.speed = Mth.clamp(change.value, 0.05f, 4);
            case AIM -> {
                group.pan = Mth.clamp(change.value, -180, 180);
                group.tilt = Mth.clamp(change.other, -85, 85);
            }
            case ENABLED -> settings.enabled = value != 0;
            case CYCLE -> group.colorCycle = value != 0;
            case TARGET -> {
                if (player != null && console.getLevel() != null) {
                    var players = console.getLevel().players().stream().filter(p -> p.isAlive() && !p.isSpectator()
                            && p.distanceToSqr(player) < 128 * 128).sorted(Comparator.comparing(Player::getUUID)).toList();
                    if (!players.isEmpty()) {
                        int current = -1;
                        for (int i = 0; i < players.size(); i++) {
                            if (players.get(i).getUUID().equals(group.target)) {
                                current = i;
                            }
                        }
                        group.target = players.get(Math.floorMod(current + (value < 0 ? -1 : 1), players.size())).getUUID();
                    }
                }
            }
            case PRESET -> {
                int slot = change.index;
                if (value == 1) {
                    console.presets[slot] = group.save();
                } else if (value == 2) {
                    group.preset(slot, player == null ? null : player.getUUID());
                } else if (console.presets[slot] != null) {
                    group.load(console.presets[slot]);
                } else {
                    return false;
                }
            }
            case SOURCES -> {
                if (value == 0) {
                    console.setAudioSource(null);
                } else {
                    console.setVideoSource(null);
                }
                console.hasAudio = false;
                console.hasVideo = false;
            }
        }
        return true;
    }

    public record Fixture(BlockPos light, int action, int value) implements CustomPacketPayload {
        public static final Type<Fixture> TYPE = new Type<>(CreateMediaSystems.id("lighting_fixture"));
        public static final StreamCodec<ByteBuf, Fixture> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Fixture::light, ByteBufCodecs.VAR_INT, Fixture::action,
                ByteBufCodecs.VAR_INT, Fixture::value, Fixture::new);

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
            if (change.action == 0 && console != null) {
                console.bind(light, Mth.clamp(change.value, 0, LightingSettings.GROUPS - 1));
            } else if (change.action == 1) {
                light.individualColor = Mth.clamp(change.value, 0, LightingSettings.COLORS - 1);
            } else if (change.action == 2 && console != null) {
                console.move(light.getBlockPos(), change.value);
            }
            light.changed();
        });
        registrar.playToServer(Link.TYPE, Link.CODEC,
                (link, context) -> LightingLinkItem.link(context.player(), link.target));
    }
}
