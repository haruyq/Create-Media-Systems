package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;

public final class WearableInstruments implements Feature {
    public static final DeferredItem<InstrumentItem> KEYTAR = CreateMediaSystems.ITEMS.register("keytar", InstrumentItem.Keytar::new);
    public static final DeferredItem<InstrumentItem> MELODICA = CreateMediaSystems.ITEMS.register("melodica",
            () -> new InstrumentItem(Kind.MELODICA));

    public static final Supplier<DataComponentType<KeytarFinish>> KEYTAR_FINISH = CreateMediaSystems.COMPONENTS
            .registerComponentType("keytar_finish", builder -> builder.persistent(KeytarFinish.CODEC)
                    .networkSynchronized(KeytarFinish.STREAM_CODEC));
    public static final Supplier<KeytarFinishRecipe.Serializer> KEYTAR_FINISH_RECIPE = CreateMediaSystems.RECIPE_SERIALIZERS
            .register("keytar_finish", KeytarFinishRecipe.Serializer::new);

    public static final int STRAPPED = 1, READY = 2, CARRIED = 4, POWERED = 8;
    private static final int ENERGY_INTERVAL = 10;
    private static final Supplier<AttachmentType<Outfit>> OUTFIT = CreateMediaSystems.ATTACHMENTS.register("instrument_wear",
            () -> AttachmentType.builder(Outfit::new).build());
    private static int nextSession;

    public enum Kind {
        KEYTAR(1, 36, 49, null), MELODICA(0, 53, 32, null),
        STRATOCASTER(2, 40, 47, InstrumentKind.STRATOCASTER),
        FLUTE(3, 60, 37, InstrumentKind.FLUTE), TRUMPET(4, 52, 31, InstrumentKind.TRUMPET);

        public final int first, keys;
        final int wireId;
        @Nullable public final InstrumentKind model;

        Kind(int wireId, int first, int keys, @Nullable InstrumentKind model) {
            this.wireId = wireId;
            this.first = first;
            this.keys = keys;
            this.model = model;
        }

        public boolean wind() { return this == FLUTE || this == TRUMPET; }
        public boolean bendsPitch() { return this == KEYTAR || this == STRATOCASTER; }

        static Kind fromFlags(int flags) {
            int modelId = flags >>> 4;
            return modelId == 0 ? ((flags & CARRIED) != 0 ? MELODICA : KEYTAR) : byId(modelId);
        }

        // The first two wire IDs match the original melodica/keytar boolean.
        static Kind byId(int id) {
            return switch (id) {
                case 0 -> MELODICA;
                case 1 -> KEYTAR;
                case 2 -> STRATOCASTER;
                case 3 -> FLUTE;
                case 4 -> TRUMPET;
                default -> throw new IllegalArgumentException("Unknown wearable instrument " + id);
            };
        }

        public int last() { return first + keys - 1; }
        boolean contains(int note) { return note >= first && note <= last(); }
    }

    private record Appearance(int flags, KeytarFinish finish) {}

    private static final class Outfit {
        @Nullable Session session;
        Appearance previous = new Appearance(0, KeytarFinish.DEFAULT);
    }

    private static final class Session {
        final int token = ++nextSession;
        final InteractionHand hand;
        final ItemStack stack;
        final InstrumentState notes;
        int paidUntil;
        long window;
        int messages;
        double audioCredit = 8;
        long audioAt = System.nanoTime();

        boolean allowsAudio() {
            long now = System.nanoTime();
            audioCredit = Math.min(12, audioCredit + (now-audioAt) / 20_000_000.0);
            audioAt = now;
            if (audioCredit < 1) return false;
            audioCredit--;
            return true;
        }

        Session(ServerPlayer player, InteractionHand hand, Kind kind) {
            this.hand = hand;
            stack = player.getItemInHand(hand);
            notes = new InstrumentState(kind, kind == Kind.STRATOCASTER
                    ? stack.getOrDefault(InstrumentFeature.GUITAR_AMP.get(), GuitarAmp.DEFAULT) : GuitarAmp.DEFAULT);
            paidUntil = player.tickCount;
        }

        boolean valid(ServerPlayer player) {
            return player.isAlive() && !player.isSpectator() && player.getItemInHand(hand) == stack
                    && stack.getItem() instanceof InstrumentItem item && item.canPlay(player, hand);
        }

        boolean powered(ServerPlayer player) {
            return notes.kind != Kind.KEYTAR || paidUntil > player.tickCount || ChargeableItem.energy(stack) > 0;
        }

        void pay(ServerPlayer player) {
            if (notes.kind != Kind.KEYTAR || !notes.sounding() || paidUntil > player.tickCount) return;
            int remaining = ChargeableItem.energy(stack);
            int paid = Math.min(remaining, ENERGY_INTERVAL);
            if (paid == 0) return;
            ChargeableItem.setEnergy(stack, remaining - paid);
            paidUntil = player.tickCount + paid;
        }

        boolean allows() {
            long now = System.nanoTime();
            if (now - window >= 1_000_000_000L || messages == 0) {
                window = now;
                messages = 0;
            }
            return ++messages <= 1500;
        }
    }

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player) tick(player);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.StartTracking event) -> {
            if (event.getEntity() instanceof ServerPlayer listener && event.getTarget() instanceof ServerPlayer player) {
                Outfit outfit = player.getData(OUTFIT);
                var visible = appearance(player, outfit.session);
                if (visible.flags() != 0) {
                    PacketDistributor.sendToPlayer(listener, update(player, outfit.session, visible, true, List.of()));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) finish(player, player.getData(OUTFIT));
        });
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) { WearablePackets.register(registrar); }

    public static boolean ready(Player player) {
        return player.getMainHandItem().is(KEYTAR.get()) && player.isAlive() && !player.isSpectator();
    }

    public static boolean carriesMelodica(Player player) {
        return !ready(player) && player.isAlive() && !player.isSpectator()
                && (player.getMainHandItem().is(MELODICA.get()) || player.getOffhandItem().is(MELODICA.get()));
    }

    public static ItemStack wornKeytar(Player player) {
        if (!player.isAlive() || player.isSpectator()) return ItemStack.EMPTY;
        if (player.getMainHandItem().is(KEYTAR.get())) return player.getMainHandItem();
        if (player.getOffhandItem().is(KEYTAR.get())) return player.getOffhandItem();
        for (var stack : player.getInventory().items) {
            if (stack.is(KEYTAR.get())) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean strapsKeytar(Player player) { return !wornKeytar(player).isEmpty(); }

    public static void addKeytarFinishes(CreativeModeTab.Output output) {
        for (var finish : List.of(new KeytarFinish(false, DyeColor.YELLOW), new KeytarFinish(true, DyeColor.LIGHT_GRAY),
                new KeytarFinish(true, DyeColor.RED))) {
            var stack = new ItemStack(KEYTAR.get());
            finish.applyTo(stack);
            output.accept(stack);
        }
    }

    static void begin(ServerPlayer player, InteractionHand hand, Kind kind) {
        if (!player.isAlive() || player.isSpectator() || !(player.getItemInHand(hand).getItem() instanceof InstrumentItem item)
                || item.kind != kind || !item.canPlay(player, hand)) return;
        Outfit outfit = player.getData(OUTFIT);
        Session session = new Session(player, hand, kind);
        outfit.session = session;
        broadcast(player, outfit, appearance(player, session), true);
        PacketDistributor.sendToPlayer(player, new WearablePackets.Open(session.token, hand == InteractionHand.MAIN_HAND, kind));
    }

    static void input(ServerPlayer player, int token, int message) {
        Outfit outfit = player.getData(OUTFIT);
        Session session = outfit.session;
        if (session == null || session.token != token) return;
        if (!session.valid(player) || message == WearablePackets.CLOSE) {
            finish(player, outfit);
            return;
        }
        var previousAmp = session.notes.amp();
        if (!session.allows() || !session.notes.apply(message)) return;
        if (!previousAmp.equals(session.notes.amp())) session.stack.set(InstrumentFeature.GUITAR_AMP.get(), session.notes.amp());
        session.pay(player);
        var visible = appearance(player, session);
        if (!visible.equals(outfit.previous)) broadcast(player, outfit, visible, false);
        PacketDistributor.sendToPlayersTrackingEntity(player, update(player, session, visible, false, List.of(message)));
    }

    static void guitar(ServerPlayer player, int token, byte[] frame) {
        Session session = player.getData(OUTFIT).session;
        if (session == null || session.token != token || session.notes.kind != Kind.STRATOCASTER
                || !session.valid(player) || !session.allowsAudio() || !GuitarFrame.valid(frame)) return;
        var packet = new WearablePackets.GuitarOutput(player.getUUID(), token, frame);
        for (var listener : player.serverLevel().getChunkSource().chunkMap.getPlayers(player.chunkPosition(), false)) {
            if (listener != player && listener.distanceToSqr(player) <= 48 * 48)
                PacketDistributor.sendToPlayer(listener, packet);
        }
    }

    private static void tick(ServerPlayer player) {
        Outfit outfit = player.getData(OUTFIT);
        Session session = outfit.session;
        if (session != null && !session.valid(player)) {
            finish(player, outfit);
            return;
        }
        if (session != null) session.pay(player);
        var visible = appearance(player, session);
        if (!visible.equals(outfit.previous)) broadcast(player, outfit, visible, false);
    }

    private static void finish(ServerPlayer player, Outfit outfit) {
        if (outfit.session == null) return;
        outfit.session = null;
        broadcast(player, outfit, appearance(player, null), true);
    }

    private static Appearance appearance(ServerPlayer player, @Nullable Session session) {
        var keytar = wornKeytar(player);
        int flags = keytar.isEmpty() ? 0 : STRAPPED;
        if (ready(player)) {
            flags |= READY;
            boolean powered = session != null && session.notes.kind == Kind.KEYTAR && session.valid(player)
                    ? session.powered(player) : ChargeableItem.energy(keytar) > 0;
            if (powered) flags |= POWERED;
        }
        if (carriesMelodica(player)) flags |= CARRIED;
        if (session != null && session.notes.kind.model != null) flags |= session.notes.kind.wireId << 4;
        return new Appearance(flags, KeytarFinish.of(keytar));
    }

    private static void broadcast(ServerPlayer player, Outfit outfit, Appearance visible, boolean snapshot) {
        outfit.previous = visible;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, update(player, outfit.session, visible, snapshot, List.of()));
    }

    private static WearablePackets.Update update(ServerPlayer player, @Nullable Session session, Appearance visible,
            boolean snapshot, List<Integer> messages) {
        if (snapshot && session != null) messages = session.notes.snapshot();
        return new WearablePackets.Update(player.getUUID(), session == null ? 0 : session.token, visible.flags(), visible.finish(),
                snapshot, messages);
    }
}
