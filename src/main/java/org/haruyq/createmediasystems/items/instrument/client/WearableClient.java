package org.haruyq.createmediasystems.items.instrument.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.haruyq.createmediasystems.blocks.piano.client.KeyMotion;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.KeytarFinish;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.instrument.WearablePackets;
import org.haruyq.createmediasystems.items.keytar.client.KeytarMesh;
import org.haruyq.createmediasystems.items.keytar.client.KeytarWear;
import org.haruyq.createmediasystems.items.melodica.client.MelodicaMesh;
import org.haruyq.createmediasystems.items.melodica.client.MelodicaWear;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class WearableClient implements WearablePackets.Client {
    private static final WearableClient INSTANCE = new WearableClient();
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    private WearableClient() {}

    public static void register(IEventBus modBus) {
        WearablePackets.client(INSTANCE);
        WearableRenderer.register(modBus);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick(Minecraft.getInstance()));
        WorldEvents.onLevelChanged(() -> {
            VIEWS.values().forEach(view -> view.audio.stop());
            VIEWS.clear();
        });
    }

    static final class View {
        final WearableAudio audio = new WearableAudio();
        int flags, session;
        KeytarFinish finish = KeytarFinish.DEFAULT;
        int missingTicks;
        @Nullable InstrumentAnimation instrument;
        InstrumentState notes = new InstrumentState(Kind.KEYTAR);
        KeyMotion motion = new KeyMotion(Kind.KEYTAR.first, Kind.KEYTAR.keys);
        float ready, play, handX = KeytarMesh.keyX(60);
        float seconds;
        long lastFrame = System.nanoTime();

        void reset(Kind kind) {
            if (instrument != null && notes.kind.model == instrument.kind()) {
                int release = InstrumentState.control(InstrumentState.RELEASE_KEYS, 0);
                notes.apply(release);
                instrument.follow(notes, release, System.nanoTime());
            }
            notes = new InstrumentState(kind);
            motion = new KeyMotion(kind.first, kind.keys);
            if (kind.model != null && session != 0) {
                instrument = new InstrumentAnimation(kind.model);
                instrument.engage(true, System.nanoTime());
            } else if (instrument != null) {
                instrument.engage(false, System.nanoTime());
            }
            handX = keyX(kind, notes.lastNote());
        }

        void animate(Player player, long now) {
            float dt = Mth.clamp((now - lastFrame) / 1_000_000_000f, 0, .15f);
            lastFrame = now;
            seconds = (seconds + dt) % 1000;
            ready = Mth.approach(ready, ready(player) ? 1 : 0, dt / KeytarWear.SWING);
            boolean raised = session != 0 && notes.kind == Kind.MELODICA && carriesMelodica(player);
            play = Mth.approach(play, raised ? 1 : 0, dt / MelodicaWear.RAISE);
            handX = Mth.lerp(Math.min(dt / .06f, 1), handX, keyX(notes.kind, notes.lastNote()));
        }

        boolean ready(Player player) {
            return WearableInstruments.ready(player) || player != Minecraft.getInstance().player && has(WearableInstruments.READY);
        }

        boolean strapped(Player player) {
            return player == Minecraft.getInstance().player ? WearableInstruments.strapsKeytar(player)
                    : ready(player) || has(WearableInstruments.STRAPPED);
        }

        KeytarFinish finish(Player player) {
            if (player == Minecraft.getInstance().player) return KeytarFinish.of(WearableInstruments.wornKeytar(player));
            return finish;
        }

        boolean carriesMelodica(Player player) {
            return WearableInstruments.carriesMelodica(player)
                    || player != Minecraft.getInstance().player && has(WearableInstruments.CARRIED);
        }

        boolean powered(Player player) {
            if (!ready(player)) return false;
            return has(WearableInstruments.POWERED)
                    || player == Minecraft.getInstance().player && ChargeableItem.energy(player.getMainHandItem()) > 0;
        }

        private boolean has(int flag) { return (flags & flag) != 0; }

        void play(int message) {
            int command = InstrumentState.command(message);
            int number = InstrumentState.first(message), value = InstrumentState.second(message);
            int before = notes.breath();
            if (!notes.apply(message)) return;
            long now = System.nanoTime();
            if (command == InstrumentState.NOTE) {
                if (value > 0) motion.press(number, now);
                else motion.release(number, now);
            } else if (command == InstrumentState.CONTROL && number == InstrumentState.RELEASE_KEYS) {
                for (int note = notes.kind.first; note <= notes.kind.last(); note++) motion.release(note, now);
            }
            if (instrument != null && notes.kind.model == instrument.kind()) instrument.follow(notes, message, now);
            audio.message(notes, message, before);
        }

        float travel(Kind kind, int note, long now) {
            return notes.kind == kind ? motion.travel(note, now) : 0;
        }
    }

    static InstrumentAnimation.Frame frame(Player player, InstrumentKind model, long now) {
        var view = VIEWS.get(player.getUUID());
        return view != null && view.instrument != null && view.instrument.kind() == model
                ? view.instrument.sample(now) : InstrumentAnimation.rest();
    }

    static View view(Player player) {
        return VIEWS.computeIfAbsent(player.getUUID(), ignored -> new View());
    }

    static boolean shouldDraw(Player player) {
        return VIEWS.containsKey(player.getUUID()) || WearableInstruments.ready(player) || WearableInstruments.carriesMelodica(player)
                || player == Minecraft.getInstance().player && WearableInstruments.strapsKeytar(player);
    }

    private static float keyX(Kind kind, int note) {
        return switch (kind) {
            case KEYTAR -> KeytarMesh.keyX(note);
            case MELODICA -> MelodicaMesh.keyX(note);
            default -> 0;
        };
    }

    @Override
    public void open(WearablePackets.Open packet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Kind kind = packet.kind();
        InteractionHand hand = packet.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        mc.setScreen(new WearableScreen(mc.player, view(mc.player), packet.session(), kind, hand));
    }

    @Override
    public void update(WearablePackets.Update packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var view = VIEWS.computeIfAbsent(packet.player(), ignored -> new View());
        boolean wasPowered = view.has(WearableInstruments.POWERED);
        view.flags = packet.flags();
        view.missingTicks = 0;
        view.finish = packet.finish();
        if (packet.snapshot() || view.session != packet.session()) {
            view.audio.stop();
            view.session = packet.session();
            view.reset(packet.kind());
            if (view.session != 0 && view.notes.kind.model != null && packet.snapshot()) {
                // A wind snapshot can contain several held notes. Sound only the one currently fingered.
                for (int message : packet.events()) view.notes.apply(message);
                view.instrument.follow(view.notes, 0, System.nanoTime());
                Player player = mc.level.getPlayerByUUID(packet.player());
                if (player != null) view.audio.tick(player, view.notes, true);
                return;
            }
        } else if (view.notes.kind == Kind.KEYTAR && wasPowered && !view.has(WearableInstruments.POWERED)) {
            view.audio.stop();
        }
        for (int message : packet.events()) view.play(message);
    }

    static void input(View view, int session, int message) {
        if (view.session != session) return;
        if (message != WearablePackets.CLOSE) view.play(message);
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new WearablePackets.Input(session, message));
        }
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null) return;
        for (var entry : VIEWS.entrySet()) {
            Player player = mc.level.getPlayerByUUID(entry.getKey());
            var view = entry.getValue();
            if (player != null && player.isAlive()) {
                view.missingTicks = 0;
                if (view.session != 0) view.audio.tick(player, view.notes, view.notes.kind != Kind.KEYTAR || view.powered(player));
                else view.audio.stop();
            } else {
                view.audio.stop();
                view.missingTicks++;
            }
        }
        // Tracking can deliver the session snapshot before the vanilla player spawn.
        VIEWS.values().removeIf(view -> view.missingTicks > 20);
    }
}
