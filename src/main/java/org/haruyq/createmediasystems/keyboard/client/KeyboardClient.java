package org.haruyq.createmediasystems.keyboard.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.keyboard.KeyboardBlock;
import org.haruyq.createmediasystems.keyboard.KeyboardFeature;
import org.haruyq.createmediasystems.keyboard.KeyboardKeys;
import org.haruyq.createmediasystems.keyboard.KeyboardMidi;
import org.haruyq.createmediasystems.keyboard.KeyboardPackets;
import org.haruyq.createmediasystems.keyboard.KeyboardPackets.Action;
import org.haruyq.createmediasystems.keyboard.KeyboardPackets.Frame;
import org.haruyq.createmediasystems.keyboard.KeyboardSettings;
import org.haruyq.createmediasystems.keyboard.KeyboardPackets.Target;

public final class KeyboardClient implements KeyboardPackets.Client {
    private static final KeyboardClient INSTANCE = new KeyboardClient();
    private final Map<Target, Instrument> instruments = new HashMap<>();
    private Instrument local;

    static final class Instrument {
        final Target target;
        KeyboardKeys keys = new KeyboardKeys();
        CompoundTag view = new CompoundTag();
        KeyboardSettings settings = new KeyboardSettings();
        Frame frame;
        KeyboardAudio audio;
        int token;
        long received;

        Instrument(Target target) { this.target = target; }

        void stopAudio() {
            if (audio != null) audio.close();
            audio = null;
        }
    }

    private KeyboardClient() {}

    public static void register(IEventBus bus) {
        KeyboardPackets.client(INSTANCE);
        KeyboardRenderer.register(bus);
        WorldEvents.onLevelChanged(INSTANCE::clear);
        NeoForge.EVENT_BUS.addListener(INSTANCE::tick);
        NeoForge.EVENT_BUS.addListener(INSTANCE::scrollKnob);
    }

    static Instrument instrument(Target target) { return INSTANCE.instruments.get(target); }

    @Override public void open(KeyboardPackets.Open packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = instruments.computeIfAbsent(packet.target(), Instrument::new);
        instrument.token = packet.token();
        instrument.received = mc.level.getGameTime();
        if (local == instrument && mc.screen instanceof KeyboardScreen screen && screen.instrument() == instrument) {
            if (packet.browser()) screen.openTones();
            return;
        }
        if (local != null) command(local, Action.CLOSE, 0, 0, "");
        local = instrument;
        mc.setScreen(new KeyboardScreen(this, instrument, packet.browser()));
    }

    @Override public void update(KeyboardPackets.Update packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = instruments.computeIfAbsent(packet.target(), Instrument::new);
        instrument.frame = packet.frame();
        instrument.received = mc.level.getGameTime();
        instrument.view = packet.view();
        instrument.settings = KeyboardSettings.load(packet.view().getCompound("Settings"));
        if (packet.snapshot()) instrument.keys = new KeyboardKeys();
        for (var event : packet.events()) {
            if (!event.metronome()) instrument.keys.apply(event);
        }
        if (!packet.view().getBoolean("Powered")) {
            instrument.stopAudio();
            return;
        }
        if (instrument.audio == null && instrument != local && !packet.view().getBoolean("Live") && packet.events().isEmpty()) return;
        boolean reopen = instrument.audio == null || !instrument.audio.matches(instrument.settings);
        if (reopen) {
            instrument.stopAudio();
            instrument.audio = new KeyboardAudio(instrument.settings, packet.frame());
        }
        instrument.audio.update(instrument.settings, packet.snapshot() || reopen, packet.events(), instrument.keys::snapshot);
        instrument.audio.tick(mc, frame(mc, instrument));
    }

    @Override public void end(KeyboardPackets.End packet) {
        var instrument = instruments.get(packet.target());
        if (instrument == null) return;
        if (local == instrument && (packet.token() == instrument.token || packet.token() == 0)) closeScreen();
        if (packet.token() == 0) {
            instrument.stopAudio();
            instruments.remove(packet.target());
        }
    }

    void send(Instrument instrument, List<KeyboardMidi> events) {
        if (local != instrument || events.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        for (int i = 0; i < events.size(); i += KeyboardPackets.BATCH) {
            PacketDistributor.sendToServer(new KeyboardPackets.Play(instrument.target, instrument.token,
                    List.copyOf(events.subList(i, Math.min(i + KeyboardPackets.BATCH, events.size())))));
        }
    }

    void command(Instrument instrument, Action action, int first, int second, String text) {
        if (local != instrument) return;
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new KeyboardPackets.Command(instrument.target, instrument.token, action.ordinal(), first, second, text));
        }
        if (action == Action.CLOSE) local = null;
    }

    private void scrollKnob(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || event.getScrollDeltaY() == 0) return;
        var state = mc.level.getBlockState(hit.getBlockPos());
        if (!state.is(KeyboardFeature.BLOCK.get())) return;
        var target = Target.placed(KeyboardBlock.root(hit.getBlockPos(), state));
        if (!KeyboardBlock.onKnob(mc.level, hit, target)) return;
        int direction = event.getScrollDeltaY() > 0 ? 1 : -1;
        PacketDistributor.sendToServer(new KeyboardPackets.Command(target, 0, Action.WHEEL.ordinal(), direction, 0, ""));
        event.setCanceled(true);
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (local != null && now - local.received > 80) closeScreen();
        instruments.values().removeIf(instrument -> {
            if (now - instrument.received > 100) {
                instrument.stopAudio();
                return true;
            }
            if (instrument.audio != null && instrument.frame != null) instrument.audio.tick(mc, frame(mc, instrument));
            return false;
        });
    }

    private static Frame frame(Minecraft mc, Instrument instrument) {
        var target = instrument.target;
        var pos = target.pos();
        if (target.mounted()) {
            if (mc.level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier && carrier.getContraption() != null) {
                var block = carrier.getContraption().getBlocks().get(pos);
                if (block != null && block.state().is(KeyboardFeature.BLOCK.get())) {
                    var left = carrier.toGlobalVector(KeyboardBlock.speaker(pos, block.state(), 0), 1);
                    var right = carrier.toGlobalVector(KeyboardBlock.speaker(pos, block.state(), 1), 1);
                    return new Frame(SableCompat.toRenderWorld(mc.level, carrier.blockPosition(), left),
                            SableCompat.toRenderWorld(mc.level, carrier.blockPosition(), right));
                }
            }
        } else {
            var state = mc.level.getBlockState(pos);
            if (state.is(KeyboardFeature.BLOCK.get())) {
                return new Frame(SableCompat.toRenderWorld(mc.level, pos, KeyboardBlock.speaker(pos, state, 0)),
                        SableCompat.toRenderWorld(mc.level, pos, KeyboardBlock.speaker(pos, state, 1)));
            }
        }
        return instrument.frame;
    }

    private void closeScreen() {
        var instrument = local;
        local = null;
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof KeyboardScreen screen && screen.instrument() == instrument) mc.setScreen(null);
    }

    private void clear() {
        if (local != null) closeScreen();
        instruments.values().forEach(Instrument::stopAudio);
        instruments.clear();
    }
}
