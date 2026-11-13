package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.blocks.piano.grand.GrandPianoBlock;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoBlockEntity;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoFeature;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;
import org.jetbrains.annotations.Nullable;

public final class PianoClient implements PianoPackets.Client {
    private static final PianoClient INSTANCE = new PianoClient();
    private static final PianoState IDLE = new PianoState();
    private final Map<PianoPackets.Target, Instrument> instruments = new HashMap<>();
    @Nullable private Instrument local;

    static final class Instrument {
        final PianoPackets.Target target;
        final int session;
        final PianoState keys = new PianoState();
        final PianoAction action = new PianoAction();
        final PianoAudio.Source sound = new PianoAudio.Source();
        PianoPackets.Frame frame;
        long received;

        Instrument(PianoPackets.Target target, int session, PianoPackets.Frame frame, long now) {
            this.target = target;
            this.session = session;
            this.frame = frame;
            received = now;
        }

        void play(List<PianoEvent> events) {
            if (events.isEmpty()) return;
            long now = System.nanoTime();
            for (PianoEvent event : events) {
                keys.apply(event);
                action.accept(event, now);
            }
            sound.play(events);
        }
    }

    private PianoClient() {}

    public static void register(IEventBus bus) {
        PianoPackets.client(INSTANCE);
        PianoRenderer.register(bus);
        bus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                new SimplePreparableReloadListener<PianoSamples>() {
                    @Override protected PianoSamples prepare(ResourceManager resources, ProfilerFiller profiler) {
                        try {
                            return PianoSamples.load(resources);
                        } catch (java.io.IOException | RuntimeException error) {
                            LogUtils.getLogger().warn("Could not load the grand piano's recordings", error);
                            return null;
                        }
                    }

                    @Override protected void apply(PianoSamples bank, ResourceManager resources, ProfilerFiller profiler) {
                        PianoAudio.INSTANCE.samples(bank);
                    }
                }));
        NeoForge.EVENT_BUS.addListener(INSTANCE::tick);
        WorldEvents.onLevelChanged(INSTANCE::clear);
    }

    static PianoState keys(PianoPackets.Target target) {
        var instrument = INSTANCE.instruments.get(target);
        return instrument == null ? IDLE : instrument.keys;
    }

    static PianoAction action(PianoPackets.Target target) {
        var instrument = INSTANCE.instruments.get(target);
        return instrument == null ? PianoAction.REST : instrument.action;
    }

    @Nullable
    public static GrandPianoSampler sampler(int rate, int frames) {
        return PianoAudio.INSTANCE.sampler(rate, frames);
    }

    @Override public void open(PianoPackets.Open packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (local != null) command(local, false);
        var instrument = new Instrument(packet.target(), packet.session(), packet.frame(), mc.level.getGameTime());
        instruments.put(packet.target(), instrument);
        local = instrument;
        mc.setScreen(new PianoScreen(this, instrument));
        audio(mc);
    }

    @Override public void update(PianoPackets.Update packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = instruments.get(packet.target());
        long now = mc.level.getGameTime();
        if (packet.snapshot()) {
            if (instrument != null && instrument == local) return;
            instrument = new Instrument(packet.target(), packet.session(), packet.frame(), now);
            instruments.put(packet.target(), instrument);
        } else if (instrument == null || instrument.session != packet.session()) {
            return;
        }
        instrument.frame = packet.frame();
        instrument.received = now;
        instrument.play(packet.events());
    }

    @Override public void end(PianoPackets.End packet) {
        var instrument = instruments.get(packet.target());
        if (instrument == null || instrument.session != packet.session()) return;
        instruments.remove(packet.target());
        if (instrument == local) {
            local = null;
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof PianoScreen screen && screen.instrument() == instrument) mc.setScreen(null);
        }
        audio(Minecraft.getInstance());
    }

    void play(Instrument instrument, List<PianoEvent> events) {
        if (instrument != local || events.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        instrument.play(events);
        for (int at = 0; at < events.size(); at += PianoPackets.BATCH_LIMIT) {
            PacketDistributor.sendToServer(new PianoPackets.Play(instrument.target, instrument.session,
                    List.copyOf(events.subList(at, Math.min(events.size(), at + PianoPackets.BATCH_LIMIT)))));
        }
    }

    void command(Instrument instrument, boolean lid) {
        if (instrument != local) return;
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null && mc.level != null) {
            PacketDistributor.sendToServer(new PianoPackets.Command(instrument.target, instrument.session, lid));
        }
        if (!lid) {
            local = null;
            instruments.remove(instrument.target, instrument);
            audio(mc);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (local != null && now - local.received > 60) {
            end(new PianoPackets.End(local.target, local.session));
        }
        instruments.values().removeIf(instrument -> now - instrument.received > 60);
        audio(mc);
    }

    private void audio(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            PianoAudio.INSTANCE.sources(List.of());
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 ear = camera.getPosition();
        float yaw = camera.getYRot();
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        float volume = mc.isPaused() ? 0 : mc.options.getSoundSourceVolume(SoundSource.RECORDS)
                * mc.options.getSoundSourceVolume(SoundSource.MASTER);
        List<PianoAudio.Source> audible = new ArrayList<>();
        for (var instrument : instruments.values()) {
            var frame = frame(mc, instrument);
            Vec3 offset = frame.origin().subtract(ear);
            double distance = offset.length();
            double range = PianoBlockEntity.AUDIBLE_RANGE;
            float gain = (float) (volume * Math.clamp((range - distance) / 10, 0, 1)
                    / (1 + distance * distance / 196));
            float pan = (float) (right.dot(offset.normalize()) * Math.min(.92, distance / 3));
            instrument.sound.position = new PianoAudio.Position(gain, pan, frame.lidOpen());
            audible.add(instrument.sound);
        }
        PianoAudio.INSTANCE.sources(audible);
    }

    private static PianoPackets.Frame frame(Minecraft mc, Instrument instrument) {
        var target = instrument.target;
        var pos = target.pos();
        if (target.mounted()) {
            if (mc.level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier && carrier.getContraption() != null) {
                var block = carrier.getContraption().getBlocks().get(pos);
                if (block != null && block.state().is(PianoFeature.PIANO.get())) {
                    var at = carrier.toGlobalVector(GrandPianoBlock.soundboard(pos, block.state()), 1);
                    return new PianoPackets.Frame(SableCompat.toRenderWorld(mc.level, carrier.blockPosition(), at),
                            block.state().getValue(GrandPianoBlock.OPEN));
                }
            }
        } else {
            var state = mc.level.getBlockState(pos);
            if (state.is(PianoFeature.PIANO.get())) {
                var at = SableCompat.toRenderWorld(mc.level, pos, GrandPianoBlock.soundboard(pos, state));
                return new PianoPackets.Frame(at, state.getValue(GrandPianoBlock.OPEN));
            }
        }
        return instrument.frame;
    }

    private void clear() {
        local = null;
        instruments.clear();
        PianoAudio.INSTANCE.sources(List.of());
    }
}
