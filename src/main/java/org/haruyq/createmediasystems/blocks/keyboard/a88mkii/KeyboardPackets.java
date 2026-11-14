package org.haruyq.createmediasystems.blocks.keyboard.a88mkii;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardClient;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * What a keyboard's player sends. Notes take the shortest way there is: a {@link Played} message is passed on to
 * the listeners by the server's network thread the moment it arrives, and played by each client's network thread the
 * moment it arrives there, so neither side's 50 ms tick is waited for.
 */
public final class KeyboardPackets {
    /** Most messages a player may send a second: a glissando over three zones is a few hundred. */
    static final int MAX_PER_SECOND = 3000;
    public static final int END = -1;
    /** Who plays which keyboard, by player: read by the network threads. */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private KeyboardPackets() {
    }

    /** A player at a keyboard, as the network threads see them. */
    public static final class Session {
        final BlockPos pos;
        final UUID player;
        final int part;
        /** Who the player's messages go on to; set by the keyboard every relay. */
        volatile List<ServerPlayer> listeners = List.of();
        /** The messages that set the sound module up (see MidiKeyboardBlock.Entity#setsUp), for the keyboard to keep. */
        final ConcurrentLinkedQueue<Integer> setup = new ConcurrentLinkedQueue<>();
        final Map<Integer, Integer> held = new LinkedHashMap<>();
        private long second;
        private int sent;

        Session(BlockPos pos, UUID player, int part) {
            this.pos = pos;
            this.player = player;
            this.part = part;
        }

        /** Whether one more message is within what a player may send; on that player's network thread. */
        boolean allows(long nanos) {
            if (nanos - second >= 1_000_000_000L) {
                second = nanos;
                sent = 0;
            }
            return ++sent <= MAX_PER_SECOND;
        }
    }

    /** {@code player} sits down at the keyboard at {@code pos}, leaving any other they sat at. */
    static Session open(ServerPlayer player, BlockPos pos, int part) {
        Session session = new Session(pos.immutable(), player.getUUID(), part);
        SESSIONS.put(player.getUUID(), session);
        return session;
    }

    static void close(UUID player, @Nullable Session session) {
        if (session != null) {
            SESSIONS.remove(player, session);
            synchronized (session) {
                for (var listener : session.listeners) {
                    if (!listener.isRemoved()) PacketDistributor.sendToPlayer(listener, new Played(session.pos, session.part, END));
                }
                session.listeners = List.of();
            }
        }
    }

    @Nullable
    static Session session(UUID player) {
        return SESSIONS.get(player);
    }

    static void audience(Session session, List<ServerPlayer> listeners) {
        synchronized (session) {
            for (var old : session.listeners) {
                if (!old.isRemoved() && !listeners.contains(old)) {
                    PacketDistributor.sendToPlayer(old, new Played(session.pos, session.part, END));
                }
            }
            for (var listener : listeners) {
                if (session.listeners.contains(listener)) continue;
                for (int message : session.held.values()) {
                    PacketDistributor.sendToPlayer(listener, new Played(session.pos, session.part, message));
                }
            }
            session.listeners = listeners;
        }
    }

    /**
     * The player of the keyboard at {@code pos} played {@code message}: a MIDI channel message, or a key or pad going
     * down or up (see Controller). To the server from its player, and on from the server to its listeners.
     */
    public record Played(BlockPos pos, int part, int message) implements CustomPacketPayload {
        public static final Type<Played> TYPE = new Type<>(CreateMediaSystems.id("keyboard_played"));
        public static final StreamCodec<ByteBuf, Played> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Played::pos,
                ByteBufCodecs.VAR_INT, Played::part,
                ByteBufCodecs.INT, Played::message,
                Played::new);

        @Override
        public Type<Played> type() {
            return TYPE;
        }
    }

    /** Client to server: its player gets up from the keyboard at {@code pos}, or (with {@code sit}) sits down at it. */
    public record Sit(BlockPos pos, boolean sit) implements CustomPacketPayload {
        public static final Type<Sit> TYPE = new Type<>(CreateMediaSystems.id("keyboard_sit"));
        public static final StreamCodec<ByteBuf, Sit> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Sit::pos,
                ByteBufCodecs.BOOL, Sit::sit,
                Sit::new);

        @Override
        public Type<Sit> type() {
            return TYPE;
        }
    }

    /** Client to server: the player of the keyboard at {@code pos} set its panel (see Panel#bytes). */
    public record SetPanel(BlockPos pos, byte[] panel) implements CustomPacketPayload {
        public static final Type<SetPanel> TYPE = new Type<>(CreateMediaSystems.id("keyboard_panel"));
        public static final StreamCodec<ByteBuf, SetPanel> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetPanel::pos,
                ByteBufCodecs.byteArray(1024), SetPanel::panel,
                SetPanel::new);

        @Override
        public Type<SetPanel> type() {
            return TYPE;
        }
    }

    /** {@code player} sent {@code played}: on to the listeners at once, if they play that keyboard. On their network thread. */
    static void relay(ServerPlayer player, Played played) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos.equals(played.pos()) || played.part() != session.part || !valid(played.message())) return;
        synchronized (session) {
            if (SESSIONS.get(session.player) != session || !session.allows(System.nanoTime())) return;
            var update = new Played(session.pos, session.part, played.message());
            remember(session, update.message());
            for (ServerPlayer listener : session.listeners) {
                PacketDistributor.sendToPlayer(listener, update);
            }
            if (MidiKeyboardBlock.Entity.setsUp(played.message())) session.setup.add(played.message());
        }
    }

    private static void remember(Session session, int message) {
        int status = message & 255, command = status & 240, key = message >> 8 & 127, value = message >> 16 & 127;
        if (status == Controller.KEY || status == Controller.PAD || command == Controller.NOTE_ON || command == Controller.NOTE_OFF) {
            int index = ((command == Controller.NOTE_OFF ? status | 16 : status) << 7) | key;
            if (command == Controller.NOTE_OFF || value == 0) session.held.remove(index);
            else session.held.put(index, message);
        } else if (command == Controller.CONTROL && (key == 120 || key == 123)) {
            int channel = status & 15;
            session.held.keySet().removeIf(index -> (index >> 7) == (Controller.NOTE_ON | channel));
        } else if (command == Controller.PROGRAM || command == Controller.CONTROL
                || command == Controller.BEND || command == Controller.PRESSURE) {
            int index = status << 7 | (command == Controller.CONTROL ? key : 0);
            session.held.remove(index);
            session.held.put(index, message);
        }
    }

    private static boolean valid(int message) {
        if ((message & ~0x7F7FFF) != 0) return false;
        int status = message & 255, key = message >> 8 & 127;
        return status == Controller.KEY || status == Controller.PAD && key < 8
                || status >= Controller.NOTE_OFF && status < 0xF0;
    }

    static void register(PayloadRegistrar registrar) {
        registrar.executesOn(HandlerThread.NETWORK).playBidirectional(Played.TYPE, Played.CODEC, new DirectionalPayloadHandler<>(
                (played, context) -> KeyboardClient.played(played.pos(), played.part(), played.message()),
                (played, context) -> relay((ServerPlayer) context.player(), played)));
        registrar.playToServer(Sit.TYPE, Sit.CODEC, (payload, context) -> {
            MidiKeyboardBlock.Entity keyboard = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND,
                    MidiKeyboardBlock.Entity.class);
            if (keyboard != null && payload.sit()) {
                keyboard.sit((ServerPlayer) context.player());
            } else if (keyboard != null) {
                keyboard.leave((ServerPlayer) context.player());
            }
        });
        registrar.playToServer(SetPanel.TYPE, SetPanel.CODEC, (payload, context) -> {
            MidiKeyboardBlock.Entity keyboard = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND,
                    MidiKeyboardBlock.Entity.class);
            if (keyboard != null) {
                keyboard.setPanel((ServerPlayer) context.player(), payload.panel());
            }
        });
    }
}
