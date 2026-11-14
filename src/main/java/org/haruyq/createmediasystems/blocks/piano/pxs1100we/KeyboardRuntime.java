package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Output;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Routing;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Target;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.Nullable;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;
import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Action;

final class KeyboardRuntime {
    static final int DRAW = 10, CAPACITY = DRAW * 20 * 60 * 60 * 4, MAX_INPUT = 2048;
    public static final double RANGE = 48;
    private static final int MAX_TAPE = 10_000;
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final List<KeyboardMidi> RELEASE = releaseEvents();

    interface Host {
        ServerLevel world();
        Target target();
        KeyboardPackets.Frame frame();
        BlockState state();
        void decorations(boolean stand, boolean rack);
        void changed(boolean sync);
    }

    private record Timed(int tick, KeyboardMidi event) {}
    private record Heard(Output output, long lastRelay) {}
    private static final class PlayerPart {
        final int token = TOKENS.updateAndGet(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
        final int color;
        final KeyboardKeys keys = new KeyboardKeys();
        final int[] banks = new int[16];
        int eventCount;
        long budgetTick = Long.MIN_VALUE;
        boolean browser;

        PlayerPart(int color) { this.color = color; }
    }

    private final Host host;
    private final KeyboardKeys keys = new KeyboardKeys();
    private final Map<UUID, PlayerPart> players = new HashMap<>();
    private final Map<ServerPlayer, Routing> listeners = new HashMap<>();
    private final Map<BlockPos, Heard> speakers = new HashMap<>();
    private final List<Timed> tape = new ArrayList<>();
    private final List<Timed> taps = new ArrayList<>();
    private final int[] banks = new int[16];
    private KeyboardSettings settings;
    private CompoundTag tapeSettings;
    private int energy, playback, beat, pulse, nextColor;
    private boolean switched, metro, poweredLast;
    private int tapeDuration;
    private long recordedAt = -1, playingAt = -1, flash, lastEvent = -600, lastWheel = -1;

    KeyboardRuntime(Host host, CompoundTag data) {
        this.host = host;
        settings = KeyboardSettings.load(data.getCompound("Settings"));
        tapeSettings = data.getCompound("TapeSettings");
        energy = Math.clamp(data.getInt("Energy"), 0, CAPACITY);
        switched = data.getBoolean("Switch");
        poweredLast = powered();
        var stored = data.getIntArray("Tape");
        int last = 0;
        for (int i = 0; i + 1 < stored.length && i < MAX_TAPE * 2; i += 2) {
            if (stored[i] < last) break;
            try {
                tape.add(new Timed(stored[i], KeyboardMidi.unpack(stored[i + 1])));
                last = stored[i];
            } catch (io.netty.handler.codec.DecoderException invalid) {
                break;
            }
        }
        tapeDuration = Math.max(last + 5, data.getInt("TapeDuration"));
    }

    CompoundTag save() {
        var data = new CompoundTag();
        data.put("Settings", settings.save());
        data.put("TapeSettings", tapeSettings.copy());
        data.putBoolean("Switch", switched);
        data.putInt("Energy", energy);
        data.putInt("TapeDuration", tapeDuration);
        var stored = new int[tape.size() * 2];
        for (int i = 0; i < tape.size(); i++) {
            stored[i * 2] = tape.get(i).tick;
            stored[i * 2 + 1] = tape.get(i).event.pack();
        }
        data.putIntArray("Tape", stored);
        return data;
    }

    CompoundTag view() {
        var data = new CompoundTag();
        data.put("Settings", settings.save());
        data.putBoolean("Powered", powered());
        data.putBoolean("Switch", switched);
        data.putBoolean("Metro", metro);
        data.putBoolean("Recording", recordedAt >= 0);
        data.putBoolean("Playing", playingAt >= 0);
        data.putBoolean("Browser", players.values().stream().anyMatch(part -> part.browser));
        data.putBoolean("Live", !players.isEmpty() || metro || playingAt >= 0 || keys.active());
        data.putBoolean("Stand", host.state().getValue(KeyboardBlock.STAND));
        data.putBoolean("Rack", host.state().getValue(KeyboardBlock.RACK));
        data.putInt("Energy", energy);
        data.putInt("Events", tape.size());
        data.putLong("Flash", flash);
        return data;
    }

    boolean powered() { return switched && energy >= DRAW; }
    int energy() { return energy; }

    int charge(int offered, boolean simulate) {
        int accepted = Math.clamp(offered, 0, Math.min(MAX_INPUT, CAPACITY - energy));
        if (!simulate && accepted > 0) {
            energy += accepted;
            host.changed(false);
        }
        return accepted;
    }

    void battery(ServerPlayer player, ItemStack battery) {
        if (!near(player)) return;
        energy += ChargeableItem.drawBattery(battery, CAPACITY - energy);
        changed();
    }

    int open(ServerPlayer player, boolean browser) {
        if (!near(player)) return 0;
        refresh();
        var part = players.computeIfAbsent(player.getUUID(), id -> new PlayerPart(++nextColor));
        part.browser = browser;
        PacketDistributor.sendToPlayer(player, new KeyboardPackets.Open(host.target(), part.token, browser, part.color));
        changed();
        return part.token;
    }

    void play(ServerPlayer player, int receivedToken, List<KeyboardMidi> events) {
        var part = part(player, receivedToken);
        if (part == null || events.isEmpty()) return;
        long now = host.world().getGameTime();
        if (part.budgetTick != now) {
            part.budgetTick = now;
            part.eventCount = 0;
        }
        part.eventCount += events.size();
        if (part.eventCount > 384) {
            leave(player.getUUID());
            return;
        }
        if (playingAt >= 0) return;
        accept(events.stream().filter(event -> !event.metronome()).toList(), true, player, part, true);
    }

    void command(ServerPlayer player, int receivedToken, Action action, int first, int second, String text) {
        if (action == Action.WHEEL) {
            long now = host.world().getGameTime();
            if (near(player) && now != lastWheel && first != 0) {
                lastWheel = now;
                perform(Action.VOLUME, settings.volume + Integer.signum(first) * 5, 0, "");
            }
        } else {
            var part = part(player, receivedToken);
            if (part == null) return;
            if (action == Action.CLOSE) leave(player.getUUID());
            else if (action == Action.PEDAL) {
                accept(List.of(KeyboardMidi.cc(SUSTAIN, Math.clamp(first, 0, 127))), true, player, part, false);
            } else perform(action, first, second, text);
        }
    }

    void panel(ServerPlayer player, String control) {
        if (!near(player)) return;
        if (control.equals("function") || control.equals("volume_knob")) {
            open(player, control.equals("function"));
            return;
        }
        switch (control) {
            case "power_button" -> perform(Action.POWER, 0, 0, "");
            case "sound_mode" -> perform(Action.SOUND_MODE, 0, 0, "");
            case "metronome" -> perform(Action.METRONOME, 0, 0, "");
            case "rec" -> perform(Action.RECORD, 0, 0, "");
            case "play" -> perform(Action.PLAY, 0, 0, "");
            case "grand_piano" -> perform(Action.MAIN, 0, 0, "");
            case "elec_piano" -> perform(Action.MAIN, 0, 4, "");
            case "pedal" -> {
                var part = players.get(player.getUUID());
                if (part == null) perform(Action.PEDAL, keys.pedalAmount(SUSTAIN) >= 64 ? 0 : 127, 0, "");
                else accept(List.of(KeyboardMidi.cc(SUSTAIN, part.keys.pedalAmount(SUSTAIN) >= 64 ? 0 : 127)),
                        true, player, part, false);
            }
        }
    }

    void tap(ServerPlayer player, int note) {
        if (!near(player) || playingAt >= 0) return;
        accept(List.of(KeyboardMidi.note(note, 87)), false);
        scheduleRelease(note, 0, 6);
    }

    private void perform(Action action, int first, int second, String text) {
        long now = host.world().getGameTime();
        switch (action) {
            case CLOSE, WHEEL -> { return; }
            case POWER -> {
                switched = !switched;
                flash = now + 3;
                if (!switched) silence();
            }
            case VOLUME -> {
                settings.volume = Math.clamp(first, 0, 127);
                if (recordedAt >= 0) record(List.of(KeyboardMidi.cc(7, settings.volume)));
            }
            case FONT -> settings.font = KeyboardSettings.fontName(text);
            case MAIN -> {
                settings.main = new KeyboardSettings.Tone(first, second);
                banks[0] = settings.main.bank();
                if (recordedAt >= 0) record(toneEvents(settings.main));
            }
            case LAYER -> settings.layer = new KeyboardSettings.Tone(first, second);
            case LOWER -> settings.lower = new KeyboardSettings.Tone(first, second);
            case LAYER_ON -> settings.layered = first != 0;
            case SPLIT_ON -> settings.split = first != 0;
            case SPLIT_KEY -> settings.splitKey = Math.clamp(first, 22, 108);
            case TRANSPOSE -> settings.transpose = Math.clamp(first, -36, 36);
            case TOUCH -> settings.touch = Math.clamp(first, 0, 4);
            case REVERB -> settings.reverb = Math.clamp(first, 0, 127);
            case CHORUS -> settings.chorus = Math.clamp(first, 0, 127);
            case BPM -> settings.bpm = Math.clamp(first, 20, 300);
            case BEATS -> settings.beats = Math.clamp(first, 1, 12);
            case POLYPHONY -> settings.polyphony = Math.clamp(first, 32, 256);
            case SOUND_MODE -> settings.reverb = settings.reverb < 30 ? 45 : settings.reverb < 75 ? 94 : 0;
            case METRONOME -> {
                metro = powered() && !metro;
                pulse = 1200;
                beat = 0;
            }
            case RECORD -> {
                if (recordedAt >= 0) finishRecording();
                else if (powered()) {
                    stopPlayback();
                    tape.clear();
                    tapeDuration = 0;
                    tapeSettings = settings.save();
                    recordedAt = now;
                    record(keys.snapshot());
                    players.values().forEach(part -> record(part.keys.snapshot()));
                }
            }
            case PLAY -> {
                if (playingAt >= 0) stopPlayback();
                else if (powered() && !tape.isEmpty()) {
                    finishRecording();
                    accept(RELEASE, false);
                    releasePlayers();
                    settings = KeyboardSettings.load(tapeSettings);
                    Arrays.fill(banks, 0);
                    banks[0] = settings.main.bank();
                    playingAt = now;
                    playback = 0;
                }
            }
            case RACK -> host.decorations(host.state().getValue(KeyboardBlock.STAND), first != 0);
            case STAND -> host.decorations(first != 0, host.state().getValue(KeyboardBlock.RACK));
            case PANIC -> {
                stopPlayback();
                accept(RELEASE, false);
                releasePlayers();
            }
            case PEDAL -> accept(List.of(KeyboardMidi.cc(SUSTAIN, Math.clamp(first, 0, 127))), true);
        }
        changed();
    }

    private boolean near(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.level() == host.world()
                && SableCompat.eye(player, 1).distanceToSqr(host.frame().centre()) < 64;
    }

    @Nullable private PlayerPart part(ServerPlayer player, int token) {
        var part = players.get(player.getUUID());
        return part != null && part.token == token && near(player) ? part : null;
    }

    private void scheduleRelease(int note, int channel, int ticks) {
        if (taps.size() < 128) {
            taps.add(new Timed((int) host.world().getGameTime() + ticks, new KeyboardMidi(OFF | channel, note, 0)));
        }
    }

    private void record(List<KeyboardMidi> events) {
        if (recordedAt < 0) return;
        int time = (int) Math.min(Integer.MAX_VALUE - 5L, host.world().getGameTime() - recordedAt);
        for (var event : events) {
            if (tape.size() == MAX_TAPE) {
                finishRecording();
                break;
            }
            tape.add(new Timed(time, event));
            tapeDuration = time + 5;
        }
        host.changed(false);
    }

    private void accept(List<KeyboardMidi> events, boolean record) {
        accept(events, record, null, null, false);
    }

    private void accept(List<KeyboardMidi> events, boolean record, @Nullable ServerPlayer performer,
            @Nullable PlayerPart part, boolean predicted) {
        if (events.isEmpty()) return;
        refresh();
        var played = part == null ? keys : part.keys;
        var channelBanks = part == null ? banks : part.banks;
        for (var event : events) {
            if (!event.metronome()) played.apply(event);
            int channel = event.channel();
            if (event.command() == CONTROL) {
                if (event.key() == 0) channelBanks[channel] = event.value() << 7 | channelBanks[channel] & 127;
                else if (event.key() == 32) channelBanks[channel] = channelBanks[channel] & ~127 | event.value();
                else if (event.key() == 7) settings.volume = event.value();
            } else if (event.command() == PROGRAM && channel == 0) {
                settings.main = KeyboardSettings.Tone.fromMidiBank(channelBanks[channel], event.key());
            }
        }
        lastEvent = host.world().getGameTime();
        if (record && powered()) record(events);
        if (events.stream().anyMatch(event -> event.command() == PROGRAM
                || event.command() == CONTROL && event.key() == 7)) host.changed(false);
        broadcast(events, part, predicted ? performer : null);
    }

    void tick() {
        long now = host.world().getGameTime();
        boolean power = powered();
        if (power != poweredLast) {
            poweredLast = power;
            if (!power) silence();
            changed();
        }
        if (power) {
            energy -= DRAW;
            if (now % 20 == 0) host.changed(players.isEmpty());
        }
        var gone = players.keySet().stream().filter(id ->
                !(host.world().getPlayerByUUID(id) instanceof ServerPlayer player) || !near(player)).toList();
        gone.forEach(this::leave);
        var due = new ArrayList<KeyboardMidi>();
        taps.removeIf(tap -> {
            if (tap.tick > (int) now) return false;
            due.add(tap.event);
            return true;
        });
        if (power && metro && (pulse += settings.bpm) >= 1200) {
            pulse %= 1200;
            due.add(new KeyboardMidi(CONTROL | 9, CLICK, beat == 0 ? 2 : 1));
            beat = (beat + 1) % settings.beats;
        }
        if (power && playingAt >= 0) {
            while (playback < tape.size() && tape.get(playback).tick <= now - playingAt && due.size() < KeyboardPackets.BATCH) {
                due.add(tape.get(playback++).event);
            }
        }
        accept(due, false);
        if (playingAt >= 0 && playback == tape.size() && now - playingAt >= tapeDuration) {
            stopPlayback();
            changed();
        }
        if (now % Streams.RELAY_TICKS != 0) return;
        pruneSpeakers(now);
        if (!players.isEmpty() || metro || playingAt >= 0 || keys.active() || now - lastEvent < 60) {
            refresh();
            broadcast(List.of());
        } else if (!listeners.isEmpty()) {
            listeners.keySet().forEach(player -> end(player, 0));
            listeners.clear();
        }
    }

    void heardThrough(Output output, long now) {
        speakers.put(output.pos(), new Heard(output, now));
    }

    private void pruneSpeakers(long now) {
        speakers.values().removeIf(heard -> now - heard.lastRelay() > 2 * Streams.RELAY_TICKS
                || !host.world().isLoaded(heard.output().pos()));
    }

    Routing routing(Vec3 ear) {
        if (speakers.isEmpty()) return Routing.INTERNAL;
        var level = host.world();
        var outputs = speakers.values().stream().map(Heard::output)
                .filter(output -> output.volume() > 0 && EmitterRange.DEFAULT.gain(output.pos(), output.sound().origin(),
                        SableCompat.toBlockSpace(level, output.pos(), ear)) > 0)
                .sorted(Comparator.comparingDouble((Output output) ->
                                SableCompat.toWorld(level, output.pos(), output.sound().origin()).distanceToSqr(ear))
                        .thenComparingLong(output -> output.pos().asLong()))
                .limit(KeyboardPackets.MAX_SPEAKERS).toList();
        return outputs.isEmpty() ? Routing.INTERNAL : new Routing(true, outputs);
    }

    private void refresh() {
        var level = host.world();
        var position = host.frame().centre();
        long now = level.getGameTime();
        pruneSpeakers(now);
        listeners.keySet().removeIf(player -> player.isRemoved() || player.level() != level);
        for (ServerPlayer player : level.players()) {
            var ear = SableCompat.eye(player, 1);
            var routing = routing(ear);
            if (ear.distanceToSqr(position) <= RANGE * RANGE || !routing.outputs().isEmpty() || players.containsKey(player.getUUID())) {
                if (listeners.put(player, routing) == null) {
                    send(player, keys.snapshot(), routing, null);
                    players.values().forEach(part -> send(player, part.keys.snapshot(), routing, part));
                }
            } else if (listeners.remove(player) != null) {
                end(player, 0);
            }
        }
    }

    private void send(ServerPlayer player, List<KeyboardMidi> events, Routing routing, @Nullable PlayerPart part) {
        PacketDistributor.sendToPlayer(player, new KeyboardPackets.Update(host.target(), host.frame(), view(), true,
                events, routing, part == null ? 0 : part.token, part == null ? 0 : part.color));
    }

    private void broadcast(List<KeyboardMidi> events) {
        broadcast(events, null, null);
    }

    private void broadcast(List<KeyboardMidi> events, @Nullable PlayerPart part, @Nullable ServerPlayer performer) {
        if (listeners.isEmpty()) return;
        var target = host.target();
        var frame = host.frame();
        var view = view();
        listeners.forEach((player, routing) -> PacketDistributor.sendToPlayer(player,
                new KeyboardPackets.Update(target, frame, view, false, player == performer ? List.of() : events,
                        routing, part == null ? 0 : part.token, part == null ? 0 : part.color)));
    }

    private void changed() {
        host.changed(true);
        broadcast(List.of());
    }

    private void end(ServerPlayer player, int token) {
        if (!player.isRemoved() && player.level() == host.world()) {
            PacketDistributor.sendToPlayer(player, new KeyboardPackets.End(host.target(), token));
        }
    }

    private void leave(UUID id) {
        var part = players.remove(id);
        if (part == null) return;
        if (host.world().getPlayerByUUID(id) instanceof ServerPlayer player && !listeners.containsKey(player)) {
            end(player, part.token);
        }
        listeners.keySet().forEach(player -> end(player, part.token));
        if (recordedAt >= 0) record(part.keys.releaseHeld());
        if (players.isEmpty()) finishRecording();
        changed();
    }

    private void releasePlayers() {
        for (var part : players.values()) {
            RELEASE.forEach(part.keys::apply);
            broadcast(RELEASE, part, null);
        }
    }

    private void stopPlayback() {
        if (playingAt < 0) return;
        playingAt = -1;
        accept(RELEASE, false);
    }

    private void silence() {
        metro = false;
        finishRecording();
        playingAt = -1;
        accept(RELEASE, false);
        taps.clear();
        releasePlayers();
    }

    private void finishRecording() {
        if (recordedAt < 0) return;
        tapeDuration = Math.max(tapeDuration, (int) Math.min(Integer.MAX_VALUE - 5L,
                host.world().getGameTime() - recordedAt) + 5);
        recordedAt = -1;
    }

    void removed() {
        listeners.keySet().forEach(player -> end(player, 0));
        listeners.clear();
        players.clear();
    }

    private static List<KeyboardMidi> toneEvents(KeyboardSettings.Tone tone) {
        return List.of(KeyboardMidi.cc(0, tone.bank() >> 7), KeyboardMidi.cc(32, tone.bank() & 127),
                new KeyboardMidi(PROGRAM, tone.program(), 0));
    }

    private static List<KeyboardMidi> releaseEvents() {
        var events = new ArrayList<KeyboardMidi>();
        for (int channel = 0; channel < 16; channel++) {
            events.add(new KeyboardMidi(CONTROL | channel, SILENCE, 0));
            events.add(new KeyboardMidi(CONTROL | channel, RESET, 0));
        }
        return List.copyOf(events);
    }
}
