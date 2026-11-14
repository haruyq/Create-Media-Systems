package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.client.KeyMotion;
import org.haruyq.createmediasystems.blocks.piano.client.NoteTrails;
import org.haruyq.createmediasystems.blocks.piano.client.NoteTrailRenderer;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.PxS1100Feature;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardKeys;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Action;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Frame;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Routing;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Target;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;

public final class PxS1100Client implements ClientFeature, KeyboardPackets.Client {
    public static final PxS1100Client INSTANCE = new PxS1100Client();
    private final Map<Target, Instrument> instruments = new HashMap<>();
    private volatile Instrument local;

    private static final class Voice {
        KeyboardKeys keys = new KeyboardKeys();
        KeyboardAudio audio;

        void close() {
            if (audio != null) audio.close();
            audio = null;
        }
    }

    static final class Instrument {
        final Target target;
        final NoteTrails trails = new NoteTrails(NoteTrailRenderer::time);
        private final Map<Integer, Voice> voices = new HashMap<>();
        final KeyMotion motion = new KeyMotion(KeyboardLayout.FIRST, KeyboardLayout.LAST - KeyboardLayout.FIRST + 1);
        CompoundTag view = new CompoundTag();
        KeyboardSettings settings = new KeyboardSettings();
        Frame frame;
        Routing routing = Routing.INTERNAL;
        int token;
        int color;
        long received;

        Instrument(Target target) { this.target = target; }

        synchronized void stopAudio() {
            voices.values().forEach(Voice::close);
        }

        synchronized boolean pressed(int note) {
            for (var voice : voices.values()) if (voice.keys.pressed(note)) return true;
            return false;
        }

        synchronized int pedalAmount(int controller) {
            int amount = 0;
            for (var voice : voices.values()) amount = Math.max(amount, voice.keys.pedalAmount(controller));
            return amount;
        }

        boolean pedal() {
            return pedalAmount(SUSTAIN) > 0 || pedalAmount(SOSTENUTO) > 0 || pedalAmount(SOFT) > 0;
        }

        void apply(int token, int color, Voice voice, List<KeyboardMidi> events, boolean snapshot) {
            if (!snapshot && events.isEmpty()) return;
            long now = System.nanoTime();
            if (snapshot) {
                trails.release(token, now);
                voice.keys = new KeyboardKeys();
            }
            for (var event : events) {
                if (event.metronome()) continue;
                voice.keys.apply(event);
                int note = event.key();
                switch (event.command()) {
                    case ON, OFF -> {
                        if (event.command() == ON && event.value() > 0 && view.getBoolean("Powered")) {
                            trails.press(token, color, note, event.value(), now);
                        } else if (!voice.keys.pressed(note)) trails.release(token, note, now);
                        if (!snapshot) updateMotion(note, now);
                    }
                    case CONTROL -> {
                        if (note == SILENCE || note == NOTES_OFF) {
                            for (int n = KeyboardLayout.FIRST; n <= KeyboardLayout.LAST; n++) {
                                if (!voice.keys.pressed(n)) trails.release(token, n, now);
                            }
                            if (!snapshot) updateMotion(now);
                        }
                    }
                }
            }
            if (snapshot) updateMotion(now);
        }

        private void updateMotion(long now) {
            for (int note = KeyboardLayout.FIRST; note <= KeyboardLayout.LAST; note++) {
                updateMotion(note, now);
            }
        }

        private void updateMotion(int note, long now) {
            if (pressed(note)) motion.press(note, now);
            else motion.release(note, now);
        }

        void forget(int token) {
            trails.release(token, System.nanoTime());
            var voice = voices.remove(token);
            if (voice != null) {
                voice.close();
                updateMotion(System.nanoTime());
            }
        }

        void play(int token, int color, Voice voice, List<KeyboardMidi> events, boolean snapshot) {
            boolean reopen = voice.audio == null || !voice.audio.matches(settings);
            var initial = snapshot ? events : reopen ? voice.keys.snapshot() : null;
            apply(token, color, voice, events, snapshot);
            if (!view.getBoolean("Powered")) {
                trails.release(token, System.nanoTime());
                voice.close();
                return;
            }
            if (reopen && events.isEmpty() && (initial == null || initial.isEmpty()) && !voice.keys.active()) return;
            if (reopen) {
                voice.close();
                voice.audio = new KeyboardAudio(settings, frame);
            }
            voice.audio.update(settings, initial, snapshot ? List.of() : events, voice.keys::snapshot);
        }

        void tickAudio(Minecraft mc) {
            if (frame == null) return;
            var at = frame(mc, this);
            for (var voice : voices.values()) {
                if (voice.audio != null) voice.audio.tick(mc, at, routing);
            }
        }
    }

    private PxS1100Client() {}

    @Override
    public void register(IEventBus bus) {
        KeyboardPackets.client(this);
        KeyboardRenderer.register(bus);
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(PxS1100Feature.ADAPTER_ENTITY.get(), context -> new KeyboardAdapterRenderer()));
        WorldEvents.onLevelChanged(this::clear);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::scrollKnob);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PxS1100Ponder.register(helper);
    }

    static Instrument instrument(Target target) { return INSTANCE.instruments.get(target); }

    @Override public void open(KeyboardPackets.Open packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = instruments.computeIfAbsent(packet.target(), Instrument::new);
        synchronized (instrument) {
            instrument.token = packet.token();
            instrument.color = packet.color();
            instrument.received = mc.level.getGameTime();
        }
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
        synchronized (instrument) {
            instrument.frame = packet.frame();
            instrument.routing = packet.routing();
            instrument.received = mc.level.getGameTime();
            instrument.view = packet.view();
            instrument.settings = KeyboardSettings.load(packet.view().getCompound("Settings"));
            if (packet.snapshot() || !packet.events().isEmpty()) {
                instrument.play(packet.token(), packet.color(), instrument.voices.computeIfAbsent(packet.token(), token -> new Voice()),
                        packet.events(), packet.snapshot());
            }
            for (var entry : instrument.voices.entrySet()) {
                if (entry.getKey() != packet.token() || !packet.snapshot() && packet.events().isEmpty()) {
                    instrument.play(entry.getKey(), 0, entry.getValue(), List.of(), false);
                }
            }
            instrument.tickAudio(mc);
        }
    }

    @Override public void end(KeyboardPackets.End packet) {
        var instrument = instruments.get(packet.target());
        if (instrument == null) return;
        if (local == instrument && (packet.token() == instrument.token || packet.token() == 0)) closeScreen();
        if (packet.token() == 0) {
            instrument.stopAudio();
            instruments.remove(packet.target());
        } else {
            synchronized (instrument) { instrument.forget(packet.token()); }
        }
    }

    void send(Instrument instrument, List<KeyboardMidi> events) {
        if (events.isEmpty()) return;
        synchronized (instrument) {
            var connection = Minecraft.getInstance().getConnection();
            if (local != instrument || connection == null || instrument.view.getBoolean("Playing")) return;
            instrument.play(instrument.token, instrument.color, instrument.voices.computeIfAbsent(instrument.token, token -> new Voice()),
                    events, false);
            for (int i = 0; i < events.size(); i += KeyboardPackets.BATCH) {
                connection.send(new KeyboardPackets.Play(instrument.target, instrument.token,
                        List.copyOf(events.subList(i, Math.min(i + KeyboardPackets.BATCH, events.size())))));
            }
        }
    }

    void command(Instrument instrument, Action action, int first, int second, String text) {
        synchronized (instrument) {
            if (local != instrument) return;
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new KeyboardPackets.Command(instrument.target, instrument.token, action.ordinal(), first, second, text));
            }
            if (action == Action.CLOSE) {
                local = null;
                instrument.forget(instrument.token);
            }
        }
    }

    private void scrollKnob(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || event.getScrollDeltaY() == 0) return;
        var state = mc.level.getBlockState(hit.getBlockPos());
        if (!state.is(PxS1100Feature.BLOCK.get())) return;
        var target = Target.placed(KeyboardBlock.root(hit.getBlockPos(), state));
        if (!KeyboardBlock.onKnob(mc.level, hit, target)) return;
        int direction = event.getScrollDeltaY() > 0 ? 1 : -1;
        PacketDistributor.sendToServer(new KeyboardPackets.Command(target, 0, Action.WHEEL.ordinal(), direction, 0, ""));
        event.setCanceled(true);
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        PxS1100Ponder.tick(mc);
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (local != null && now - local.received > 80) closeScreen();
        instruments.values().removeIf(instrument -> {
            if (now - instrument.received > 100) {
                instrument.stopAudio();
                return true;
            }
            synchronized (instrument) { instrument.tickAudio(mc); }
            return false;
        });
    }

    private static Frame frame(Minecraft mc, Instrument instrument) {
        var target = instrument.target;
        var pos = target.pos();
        if (target.mounted()) {
            if (mc.level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier && carrier.getContraption() != null) {
                var block = carrier.getContraption().getBlocks().get(pos);
                if (block != null && block.state().is(PxS1100Feature.BLOCK.get())) {
                    var left = carrier.toGlobalVector(KeyboardBlock.speaker(pos, block.state(), 0), 1);
                    var right = carrier.toGlobalVector(KeyboardBlock.speaker(pos, block.state(), 1), 1);
                    return new Frame(SableCompat.toRenderWorld(mc.level, carrier.blockPosition(), left),
                            SableCompat.toRenderWorld(mc.level, carrier.blockPosition(), right));
                }
            }
        } else {
            var state = mc.level.getBlockState(pos);
            if (state.is(PxS1100Feature.BLOCK.get())) {
                return new Frame(SableCompat.toRenderWorld(mc.level, pos, KeyboardBlock.speaker(pos, state, 0)),
                        SableCompat.toRenderWorld(mc.level, pos, KeyboardBlock.speaker(pos, state, 1)));
            }
        }
        return instrument.frame;
    }

    private void closeScreen() {
        var instrument = local;
        if (instrument == null) return;
        synchronized (instrument) {
            local = null;
        }
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof KeyboardScreen screen && screen.instrument() == instrument) mc.setScreen(null);
    }

    private void clear() {
        if (local != null) closeScreen();
        instruments.values().forEach(Instrument::stopAudio);
        instruments.clear();
    }
}
