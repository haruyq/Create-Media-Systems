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
        KEYTAR(36, 49), MELODICA(53, 32);

        public final int first, keys;
        Kind(int first, int keys) {
            this.first = first;
            this.keys = keys;
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

        Session(ServerPlayer player, InteractionHand hand, Kind kind) {
            this.hand = hand;
            stack = player.getItemInHand(hand);
            notes = new InstrumentState(kind);
            paidUntil = player.tickCount;
        }

        boolean valid(ServerPlayer player) {
            return player.isAlive() && !player.isSpectator() && player.getItemInHand(hand) == stack
                    && (notes.kind == Kind.KEYTAR || !ready(player));
        }

        boolean powered(ServerPlayer player) {
            return notes.kind == Kind.MELODICA || paidUntil > player.tickCount || ChargeableItem.energy(stack) > 0;
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
        if (!player.isAlive() || player.isSpectator() || kind == Kind.KEYTAR && hand != InteractionHand.MAIN_HAND
                || kind == Kind.MELODICA && ready(player)) return;
        Outfit outfit = player.getData(OUTFIT);
        Session session = new Session(player, hand, kind);
        outfit.session = session;
        broadcast(player, outfit, appearance(player, session), true);
        PacketDistributor.sendToPlayer(player, new WearablePackets.Open(session.token, hand == InteractionHand.MAIN_HAND,
                kind == Kind.KEYTAR));
    }

    static void input(ServerPlayer player, int token, int message) {
        Outfit outfit = player.getData(OUTFIT);
        Session session = outfit.session;
        if (session == null || session.token != token) return;
        if (!session.valid(player) || message == WearablePackets.CLOSE) {
            finish(player, outfit);
            return;
        }
        if (!session.allows() || !session.notes.apply(message)) return;
        session.pay(player);
        var visible = appearance(player, session);
        if (!visible.equals(outfit.previous)) broadcast(player, outfit, visible, false);
        PacketDistributor.sendToPlayersTrackingEntity(player, update(player, session, visible, false, List.of(message)));
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
