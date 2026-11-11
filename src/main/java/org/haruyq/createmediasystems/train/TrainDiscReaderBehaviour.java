package org.haruyq.createmediasystems.train;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ItemSlot;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * On a train: plays the stored announcement schedule through the train's {@link TrainSpeakerBlock}s, each heard only
 * in its own carriage (the client picks the speaker). Each tick it works out where the train
 * is on its trip (see {@link AnnounceCondition.Trip}) and starts announcements as they come due, each once per
 * trip. A playing announcement is followed live: whoever boards while it plays (seated or standing) gets it from
 * where it is now, and whoever gets off stops hearing it.
 */
public class TrainDiscReaderBehaviour implements MovementBehaviour {
    /**
     * New riders get a playing announcement for at most this long when the API didn't say how long it is.
     * ponytail: a guess for announcements of unknown length; those already listening hear it to the end anyway.
     */
    private static final long UNKNOWN_LENGTH_RELAY_TICKS = 20 * 60 * 10;
    /** Who already has the current announcement of each train disc reader. */
    private static final Map<MovementContext, Set<ServerPlayer>> LISTENERS = new WeakHashMap<>();

    @Override
    public boolean isActive(MovementContext context) {
        return context.contraption instanceof CarriageContraption;
    }

    @Override
    public void tick(MovementContext context) {
        if (!(context.world instanceof ServerLevel level)
                || !(context.contraption.entity instanceof CarriageContraptionEntity entity)
                || entity.getCarriage() == null) {
            return;
        }
        Train train = entity.getCarriage().train;
        long now = level.getGameTime();
        String due = dueAnnouncement(now, context.data, train, schedule(level, context));
        if (due != null) {
            context.data.putString("Playing", due);
            context.data.putLong("PlayingSince", now);
            context.data.putBoolean("Loading", true);
            LISTENERS.remove(context);
        }
        if (due != null || Streams.relayDue(level)) {
            relay(context, train, now);
        }
    }

    private static void relay(MovementContext context, Train train, long now) {
        String url = context.data.getString("Playing");
        if (url.isEmpty()) {
            return;
        }
        if (context.data.getBoolean("Loading") && now - context.data.getLong("PlayingSince") >= Streams.MAX_LOAD_TICKS) {
            loaded(context, now); // nobody had it loaded in time: it plays on from here
        }
        // Loading doesn't count as playing (see Streams#tick): riders boarding meanwhile start from the top too.
        long ticks = context.data.getBoolean("Loading") ? 0 : now - context.data.getLong("PlayingSince");
        if (!UrlPolicy.isAllowed(url) || (Streams.duration(url) > 0 ? Streams.endedAt(url, ticks)
                : ticks > UNKNOWN_LENGTH_RELAY_TICKS)) {
            context.data.remove("Playing");
            LISTENERS.remove(context);
            return;
        }
        Set<ServerPlayer> aboard = passengers(train);
        Set<ServerPlayer> listeners = LISTENERS.computeIfAbsent(context, c -> new HashSet<>());
        // Those who got off stop on their own (the client checks too); if they board again they get it again.
        listeners.retainAll(aboard);
        List<Integer> carriages = carriageIds(train);
        String channel = channel(context, train);
        for (ServerPlayer player : aboard) {
            if (listeners.add(player)) {
                PacketDistributor.sendToPlayer(player, new Announce(url, channel, carriages, ticks / 20f,
                        context.blockEntityData == null ? 1 : TrainDiscReaderBlock.volume(context.blockEntityData)));
            }
        }
    }

    /** Which of the train's announcements clients play {@code context}'s on. */
    private static String channel(MovementContext context, Train train) {
        return train.id + "/" + context.localPos.asLong();
    }

    private static void loaded(MovementContext context, long now) {
        context.data.remove("Loading");
        context.data.putLong("PlayingSince", now);
    }

    /**
     * {@code player} has the announcement on {@code channel} loaded and playing, so it now counts as playing, if it
     * was still waiting. Only from a rider it was sent to.
     */
    public static void started(ServerPlayer player, String channel) {
        LISTENERS.forEach((context, listeners) -> {
            if (listeners.contains(player) && context.data.getBoolean("Loading")
                    && context.contraption.entity instanceof CarriageContraptionEntity entity
                    && entity.getCarriage() != null && channel(context, entity.getCarriage().train).equals(channel)) {
                loaded(context, player.serverLevel().getGameTime());
            }
        });
    }

    /** The announcement that came due this tick, if any (and marks every one that did as played this trip). */
    @Nullable
    static String dueAnnouncement(long now, CompoundTag state, Train train, @Nullable Schedule schedule) {
        String at = train.getCurrentStation() == null ? "" : train.getCurrentStation().name;
        Destination destination = destination(train, state, now, schedule);
        String heading = at.isEmpty() ? destination.name() : "";
        // First tick after assembly only records state, so assembling at a station isn't an "arrival".
        boolean seen = state.contains("At");
        boolean departed = seen && at.isEmpty() && (!state.getString("At").isEmpty()
                || !heading.isEmpty() && !heading.equals(state.getString("Heading")));
        boolean arrived = seen && !at.isEmpty() && !at.equals(state.getString("At"));
        state.putString("Heading", heading);
        state.putString("At", at);
        state.putDouble("Remaining", destination.distance());
        if (!seen || departed) {
            state.putLong("TripStart", now);
            state.putDouble("TripDistance", destination.distance());
            state.remove("Fired");
        }
        if (!seen || schedule == null || heading.isEmpty() && !arrived) {
            return null; // nothing due while standing at a station
        }

        double speed = Math.abs(train.speed); // blocks per tick
        double distance = state.contains("TripDistance") ? state.getDouble("TripDistance") : train.navigation.distanceStartedAt;
        AnnounceCondition.Trip trip = new AnnounceCondition.Trip(!heading.isEmpty(), arrived,
                (now - state.getLong("TripStart")) / 20.0,
                heading.isEmpty() || distance <= 1e-6 ? 1
                        : Mth.clamp(1 - destination.distance() / distance, 0, 1),
                // ponytail: at the current speed, so it jumps while accelerating or braking; Create's own
                // station-board predictions would be steadier if that ever matters.
                heading.isEmpty() || speed < 0.01 ? Double.POSITIVE_INFINITY : destination.distance() / speed / 20);

        CompoundTag fired = state.getCompound("Fired");
        String play = null;
        for (int e = 0; e < schedule.entries.size(); e++) {
            ScheduleEntry entry = schedule.entries.get(e);
            if (!(entry.instruction instanceof DestinationInstruction station)) {
                continue;
            }
            String regex = station.getFilterForRegex();
            for (int c = 0; c < entry.conditions.size(); c++) {
                List<ScheduleWaitCondition> column = entry.conditions.get(c);
                for (int r = 0; r < column.size(); r++) {
                    String key = e + "." + c + "." + r;
                    if (!(column.get(r) instanceof AnnounceCondition announce) || fired.contains(key)) {
                        continue;
                    }
                    String target = announce.trigger() == AnnounceCondition.Trigger.ARRIVAL ? at : heading;
                    if (target.isEmpty() || !target.matches(regex) || !announce.trigger().ready(announce.value(), trip)) {
                        continue;
                    }
                    fired.putBoolean(key, true);
                    // Several due at once: the first in schedule order plays; the rest are dropped rather than
                    // played next tick, where each would cut off the one before on this reader.
                    if (play == null) {
                        play = announce.url();
                    }
                }
            }
        }
        state.put("Fired", fired);
        return play;
    }

    private record Destination(String name, double distance) {
        private static final Destination NONE = new Destination("", Double.POSITIVE_INFINITY);
    }

    private static Destination destination(Train train, CompoundTag state, long now, @Nullable Schedule schedule) {
        Navigation nav = train.navigation;
        if (nav.destination != null || train.getCurrentStation() != null || schedule == null) {
            state.remove("Manual");
            return nav.destination == null ? Destination.NONE : new Destination(nav.destination.name, nav.distanceToDestination);
        }
        if (train.graph == null || train.derailed || train.carriages.isEmpty()
                || train.speed == 0 && state.getString("Heading").isEmpty()) {
            state.remove("Manual");
            return Destination.NONE;
        }
        boolean backwards = train.speed == 0 ? state.getBoolean("Backwards") : train.speed < 0;
        boolean cached = state.getBoolean("Manual") && state.getBoolean("Backwards") == backwards
                && state.getInt("Steer") == train.manualSteer.ordinal() && now - state.getLong("RouteCheck") < 10;
        state.putBoolean("Backwards", backwards);
        if (cached) {
            return new Destination(state.getString("Heading"), Math.max(0, state.getDouble("Remaining") - Math.abs(train.speed)));
        }
        state.putBoolean("Manual", true);
        state.putInt("Steer", train.manualSteer.ordinal());
        state.putLong("RouteCheck", now);

        // Manual driving has no navigation destination; scout a copy of the train's leading wheel along its steering.
        TravellingPoint point = backwards ? train.carriages.getLast().getTrailingPoint() : train.carriages.getFirst().getLeadingPoint();
        TravellingPoint scout = new TravellingPoint(point.node1, point.node2, point.edge, point.position, point.upsideDown);
        Set<TrackEdge> visited = new HashSet<>();
        Destination[] found = {Destination.NONE};
        double travelled = 0;
        while (scout.edge != null) {
            boolean revisited = !visited.add(scout.edge);
            double offset = travelled;
            // Create uses track distance on curves only when travelling more than five blocks at once.
            double step = Math.max(6, (backwards ? scout.position : scout.edge.getLength() - scout.position) + 0.001);
            travelled += Math.abs(scout.travel(train.graph, backwards ? -step : step,
                    scout.steer(train.manualSteer, new Vec3(0, 1, 0)), (distance, edgePoint) -> {
                        if (!(edgePoint.getFirst() instanceof GlobalStation station) || offset + distance <= 1e-6
                                || !station.canApproachFrom(edgePoint.getSecond().getSecond()) || station.assembling) {
                            return false;
                        }
                        for (ScheduleEntry entry : schedule.entries) {
                            if (entry.instruction instanceof DestinationInstruction instruction
                                    && station.name.matches(instruction.getFilterForRegex())) {
                                found[0] = new Destination(station.name, offset + distance);
                                return true;
                            }
                        }
                        return false;
                    }));
            if (found[0] != Destination.NONE || scout.blocked || revisited) {
                break;
            }
        }
        return found[0];
    }

    /** The stored announcement schedule, parsed once per change of the stored item (not every tick). */
    @Nullable
    private static Schedule schedule(ServerLevel level, MovementContext context) {
        if (context.blockEntityData == null) {
            return null;
        }
        CompoundTag item = context.blockEntityData.getCompound(TrainDiscReaderBlock.ITEM_TAG);
        if (context.temporaryData instanceof ParsedSchedule parsed && parsed.item() == item) {
            return parsed.schedule();
        }
        Schedule schedule = ScheduleItem.getSchedule(level.registryAccess(), ItemStack.parseOptional(level.registryAccess(), item));
        context.temporaryData = new ParsedSchedule(item, schedule);
        return schedule;
    }

    private record ParsedSchedule(CompoundTag item, @Nullable Schedule schedule) {
    }

    private static Set<ServerPlayer> passengers(Train train) {
        return carriages(train).filter(entity -> entity.level() instanceof ServerLevel)
                .flatMap(entity -> ((ServerLevel) entity.level()).players().stream().filter(p -> aboard(entity, p)))
                .collect(Collectors.toSet());
    }

    /** How far above the carriage's blocks a player's feet may be and still count as aboard (a jump, a slab). */
    private static final double ABOARD_HEIGHT = 1.5;

    /**
     * Whether {@code player} is on {@code carriage}: riding it (seated), or standing on one of its blocks (the floor,
     * a seat, the roof). Tested in the carriage's own coordinates, where its blocks sit on the grid however the
     * train has turned. Used by server and client alike, so both agree.
     */
    public static boolean aboard(CarriageContraptionEntity carriage, Player player) {
        if (player.level() != carriage.level() || player.isSpectator()) {
            return false;
        }
        if (player.getRootVehicle() == carriage) {
            return true;
        }
        Contraption contraption = carriage.getContraption();
        if (contraption == null) {
            return false;
        }
        Vec3 feet = ContraptionCollider.worldToLocalPos(player.position(), carriage);
        for (double below = 0.01; below <= ABOARD_HEIGHT + 0.01; below += 0.5) {
            var block = contraption.getBlocks().get(BlockPos.containing(feet.x, feet.y - below, feet.z));
            if (block != null && !block.state().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> carriageIds(Train train) {
        return carriages(train).map(CarriageContraptionEntity::getId).toList();
    }

    private static Stream<CarriageContraptionEntity> carriages(Train train) {
        return train.carriages.stream().map(Carriage::anyAvailableEntity).filter(Objects::nonNull);
    }

    /** Lets players insert/remove the schedule without disassembling the train. */
    public static class Interaction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos localPos,
                                               AbstractContraptionEntity entity) {
            if (player.level().isClientSide) {
                return true;
            }
            var actor = entity.getContraption().getActorAt(localPos);
            if (actor == null || actor.right == null || actor.right.blockEntityData == null) {
                return false;
            }
            // Same tag object as the contraption's stored block entity data, so it survives disassembly.
            CompoundTag data = actor.right.blockEntityData;
            ItemSlot slot = TrainDiscReaderBlock.slot();
            slot.load(data, player.registryAccess());
            slot.set(slot.exchange(player, hand, slot.get()));
            slot.save(data, player.registryAccess());
            return true;
        }
    }

    /**
     * Server → client: in-train announcement, played by the train speakers of {@code carriages} at {@code volume};
     * the client stops it once it is off all of them.
     */
    public record Announce(String url, String channel, List<Integer> carriages, float startSeconds, float volume)
            implements CustomPacketPayload {
        public static final Type<Announce> TYPE = new Type<>(CreateMediaSystems.id("announce"));
        public static final StreamCodec<ByteBuf, Announce> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Announce::url,
                ByteBufCodecs.STRING_UTF8, Announce::channel,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Announce::carriages,
                ByteBufCodecs.FLOAT, Announce::startSeconds,
                ByteBufCodecs.FLOAT, Announce::volume,
                Announce::new);

        @Override
        public Type<Announce> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: the announcement on {@code channel} is loaded and plays here (see
     * TrainDiscReaderBehaviour#started).
     */
    public record StartedAnnouncement(String channel) implements CustomPacketPayload {
        public static final Type<StartedAnnouncement> TYPE = new Type<>(CreateMediaSystems.id("started_announcement"));
        public static final StreamCodec<ByteBuf, StartedAnnouncement> CODEC =
                ByteBufCodecs.STRING_UTF8.map(StartedAnnouncement::new, StartedAnnouncement::channel);

        @Override
        public Type<StartedAnnouncement> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToClient(Announce.TYPE, Announce.CODEC, (payload, context) -> Announcements.play(payload));
        registrar.playToServer(StartedAnnouncement.TYPE, StartedAnnouncement.CODEC,
                (payload, context) -> started((ServerPlayer) context.player(), payload.channel()));
    }
}
