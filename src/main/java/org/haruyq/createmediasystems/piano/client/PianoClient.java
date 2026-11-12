package org.haruyq.createmediasystems.piano.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
import org.haruyq.createmediasystems.piano.GrandPianoBlock;
import org.haruyq.createmediasystems.piano.PianoBlockEntity;
import org.haruyq.createmediasystems.piano.PianoEvent;
import org.haruyq.createmediasystems.piano.PianoFeature;
import org.haruyq.createmediasystems.piano.PianoPackets;
import org.haruyq.createmediasystems.piano.PianoState;
import org.jetbrains.annotations.Nullable;

public final class PianoClient implements PianoPackets.Client {
    private static final PianoClient INSTANCE = new PianoClient();
    private static final PianoState IDLE = new PianoState();
    private final Map<BlockPos, Instrument> instruments = new HashMap<>();
    @Nullable private Instrument local;

    static final class Instrument {
        final BlockPos pos;
        final int session;
        final PianoState keys = new PianoState();
        final PianoAudio.Source sound = new PianoAudio.Source();
        Vec3 origin;
        long received;

        Instrument(BlockPos pos, int session, Vec3 origin, long now) {
            this.pos = pos;
            this.session = session;
            this.origin = origin;
            received = now;
        }

        void play(List<PianoEvent> events) {
            events.forEach(keys::apply);
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

    static PianoState keys(BlockPos pos) {
        var instrument = INSTANCE.instruments.get(pos);
        return instrument == null ? IDLE : instrument.keys;
    }

    @Override public void open(PianoPackets.Open packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = new Instrument(packet.pos(), packet.session(), packet.origin(), mc.level.getGameTime());
        instruments.put(packet.pos(), instrument);
        local = instrument;
        mc.setScreen(new PianoScreen(this, instrument));
        audio(mc);
    }

    @Override public void update(PianoPackets.Update packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var instrument = instruments.get(packet.pos());
        long now = mc.level.getGameTime();
        if (packet.snapshot() || instrument == null || instrument.session != packet.session()) {
            instrument = new Instrument(packet.pos(), packet.session(), packet.origin(), now);
            instruments.put(packet.pos(), instrument);
        }
        instrument.origin = packet.origin();
        instrument.received = now;
        instrument.play(packet.events());
    }

    @Override public void end(PianoPackets.End packet) {
        var instrument = instruments.get(packet.pos());
        if (instrument == null || instrument.session != packet.session()) return;
        instruments.remove(packet.pos());
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
            PacketDistributor.sendToServer(new PianoPackets.Play(instrument.pos, instrument.session,
                    List.copyOf(events.subList(at, Math.min(events.size(), at + PianoPackets.BATCH_LIMIT)))));
        }
    }

    void command(Instrument instrument, boolean lid) {
        if (instrument != local) return;
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null && mc.level != null) {
            PacketDistributor.sendToServer(new PianoPackets.Command(instrument.pos, instrument.session, lid));
        }
        if (!lid) {
            local = null;
            instruments.remove(instrument.pos, instrument);
            audio(mc);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        instruments.values().removeIf(instrument -> instrument != local && now - instrument.received > 60);
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
            var state = mc.level.getBlockState(instrument.pos);
            boolean here = state.is(PianoFeature.PIANO.get());
            Vec3 from = here ? SableCompat.toRenderWorld(mc.level, instrument.pos,
                    GrandPianoBlock.soundboard(instrument.pos, state)) : instrument.origin;
            Vec3 offset = from.subtract(ear);
            double distance = offset.length();
            double range = PianoBlockEntity.AUDIBLE_RANGE;
            float gain = (float) (volume * Math.clamp((range - distance) / 10, 0, 1)
                    / (1 + distance * distance / 196));
            float pan = (float) (right.dot(offset.normalize()) * Math.min(.92, distance / 3));
            instrument.sound.position = new PianoAudio.Position(gain, pan, here && state.getValue(GrandPianoBlock.OPEN));
            audible.add(instrument.sound);
        }
        PianoAudio.INSTANCE.sources(audible);
    }

    private void clear() {
        local = null;
        instruments.clear();
        PianoAudio.INSTANCE.sources(List.of());
    }
}
