package org.haruyq.createmediasystems.train;

import static org.haruyq.createmediasystems.train.AnnounceCondition.Trigger.AFTER_DEPARTURE;
import static org.haruyq.createmediasystems.train.AnnounceCondition.Trigger.ARRIVAL;
import static org.haruyq.createmediasystems.train.AnnounceCondition.Trigger.BEFORE_ARRIVAL;
import static org.haruyq.createmediasystems.train.AnnounceCondition.Trigger.DEPARTURE;
import static org.haruyq.createmediasystems.train.AnnounceCondition.Trigger.PROGRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.createmod.catnip.data.Couple;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class TrainDiscReaderBehaviourTest {
    private final TrackGraph graph = new TrackGraph();
    private final TrackNode start = node(0, 0);
    private final TrackNode end = node(1000, 0);
    private final TrackEdge edge = connect(start, end);
    private final GlobalStation a = station("A", start, end, 100, false);
    private final GlobalStation b = station("B", start, end, 900, true);
    private final TravellingPoint leading = new TravellingPoint(start, end, edge, 100, false);
    private final TravellingPoint trailing = new TravellingPoint(start, end, edge, 98, false);
    private final Train train = new Train(UUID.randomUUID(), null, graph,
            List.of(new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag(), leading, trailing), null, 0)),
            List.of(), true, 0);
    private final CompoundTag state = new CompoundTag();
    private final Schedule schedule = schedule("*");

    @Test
    void manualDrivingFiresEveryEventOnceWithoutNavigation() {
        train.setCurrentStation(a);
        assertNull(tick(0));
        train.leaveStation();
        move(101, 1);
        assertEquals(DEPARTURE.name(), tick(1));
        assertNull(tick(2));
        move(141, 1);
        assertEquals(AFTER_DEPARTURE.name(), tick(41));
        move(501, 1);
        assertEquals(PROGRESS.name(), tick(401));
        move(851, 1);
        assertEquals(BEFORE_ARRIVAL.name(), tick(751));
        train.setCurrentStation(b);
        move(900, 0);
        assertEquals(ARRIVAL.name(), tick(800));
        assertNull(tick(801));
        assertNull(train.navigation.destination);
        assertEquals(0, train.navigation.distanceStartedAt);
        assertEquals(900, leading.position);
    }

    @Test
    void reversingFromAStationStartsANewTrip() {
        Schedule ab = stations("A", "B");
        train.setCurrentStation(b);
        move(902, 0);
        assertNull(tick(0, ab));
        train.leaveStation();
        move(901, -1);
        assertEquals("A/" + DEPARTURE.name(), tick(1, ab));
        move(501, -1);
        assertEquals("A/" + AFTER_DEPARTURE.name(), tick(41, ab));
        assertNull(tick(42, ab));
        move(151, -1);
        assertEquals("A/" + BEFORE_ARRIVAL.name(), tick(751, ab));
        train.setCurrentStation(a);
        move(102, 0);
        assertEquals("A/" + ARRIVAL.name(), tick(800, ab));
        train.leaveStation();
        move(103, 1);
        assertEquals("B/" + DEPARTURE.name(), tick(801, ab));
    }

    @Test
    void stoppingEnRouteAndStartingStationAssistanceKeepTheOriginalTrip() {
        train.setCurrentStation(a);
        assertNull(tick(0));
        train.leaveStation();
        move(101, 1);
        assertEquals(DEPARTURE.name(), tick(1));
        move(141, 1);
        assertEquals(AFTER_DEPARTURE.name(), tick(41));
        move(501, 1);
        assertEquals(PROGRESS.name(), tick(401));
        move(851, 0);
        assertNull(tick(751));
        train.navigation.destination = b;
        train.navigation.distanceToDestination = 49;
        train.navigation.distanceStartedAt = 49;
        train.speed = 1;
        // A trip started over here would announce its departure first, and again.
        assertEquals(BEFORE_ARRIVAL.name(), tick(752));
        assertNull(tick(753));
        train.navigation.destination = null;
        train.setCurrentStation(b);
        assertEquals(ARRIVAL.name(), tick(802));
    }

    @Test
    void automaticDrivingWaitsForDepartureAndStillFiresEveryEvent() {
        train.setCurrentStation(a);
        assertNull(tick(0));
        train.navigation.destination = b;
        train.navigation.distanceToDestination = 800;
        assertNull(tick(1));
        train.leaveStation();
        train.speed = 1;
        assertEquals(DEPARTURE.name(), tick(2));
        train.navigation.distanceToDestination = 760;
        assertEquals(AFTER_DEPARTURE.name(), tick(42));
        train.navigation.distanceToDestination = 400;
        assertEquals(PROGRESS.name(), tick(402));
        train.navigation.distanceToDestination = 50;
        assertEquals(BEFORE_ARRIVAL.name(), tick(752));
        train.navigation.destination = null;
        train.setCurrentStation(b);
        assertEquals(ARRIVAL.name(), tick(802));
    }

    @Test
    void manualRouteFollowsSteeringAcrossTrackEdgesAndMatchesStationFilters() {
        TrackNode straight = node(2000, 0);
        TrackNode branch = node(2000, 400);
        connect(end, straight);
        connect(end, branch);
        station("Straight", end, straight, 500, true);
        station("Branch", end, branch, 500, true);
        Schedule destinations = stations("Stra*", "Bran*");
        train.setCurrentStation(a);
        assertNull(tick(0, destinations));
        train.leaveStation();
        move(101, 1);
        assertEquals("Stra*/" + DEPARTURE.name(), tick(1, destinations));
        train.manualSteer = TravellingPoint.SteerDirection.RIGHT;
        assertEquals("Bran*/" + DEPARTURE.name(), tick(2, destinations));
        assertEquals(101, leading.position);
        assertNull(train.navigation.destination);
    }

    @Test
    void noEventsFireWhenParkedOrWhenNoScheduledStationIsAhead() {
        assertNull(tick(0));
        assertNull(tick(1));
        move(950, 1);
        assertNull(tick(2));
        assertNull(tick(12));
        train.graph = null;
        assertNull(tick(13));
    }

    @Test
    void aLoopFindsTheStationBehindTheStartingPointAndStopsWhenNoneMatch() {
        List<TrackNode> nodes = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            nodes.add(node(3000 + Math.cos(angle) * 100, Math.sin(angle) * 100));
        }
        for (int i = 0; i < nodes.size(); i++) {
            connect(nodes.get(i), nodes.get((i + 1) % nodes.size()));
        }
        station("Loop", nodes.get(0), nodes.get(1), 5, true);
        leading.node1 = nodes.get(0);
        leading.node2 = nodes.get(1);
        leading.edge = graph.getConnection(Couple.create(leading.node1, leading.node2));
        Schedule loop = stations("Loop");
        move(20, 0);
        assertNull(tick(0, loop));
        train.speed = 1;
        assertEquals("Loop/" + DEPARTURE.name(), tick(1, loop));
        assertNull(TrainDiscReaderBehaviour.dueAnnouncement(20, new CompoundTag(), train, stations("Missing")));
        assertEquals(20, leading.position);
    }

    private String tick(long now) {
        return tick(now, schedule);
    }

    private String tick(long now, Schedule schedule) {
        return TrainDiscReaderBehaviour.dueAnnouncement(now, state, train, schedule);
    }

    private void move(double position, double speed) {
        leading.position = position;
        trailing.position = position - 2;
        train.speed = speed;
    }

    private TrackNode node(double x, double z) {
        TrackNodeLocation location = new TrackNodeLocation(x, 0, z).in(Level.OVERWORLD);
        graph.loadNode(location, graph.getNodes().size(), new Vec3(0, 1, 0));
        return graph.locateNode(location);
    }

    private TrackEdge connect(TrackNode from, TrackNode to) {
        TrackEdge connection = new TrackEdge(from, to, null, TrackMaterial.ANDESITE);
        graph.putConnection(from, to, connection);
        graph.putConnection(to, from, new TrackEdge(to, from, null, TrackMaterial.ANDESITE));
        return connection;
    }

    private GlobalStation station(String name, TrackNode from, TrackNode to, double position, boolean forward) {
        GlobalStation station = EdgePointType.STATION.create();
        station.setId(UUID.randomUUID());
        station.name = name;
        station.setLocation(Couple.create(forward ? from.getLocation() : to.getLocation(), forward ? to.getLocation() : from.getLocation()),
                forward ? position : graph.getConnection(Couple.create(from, to)).getLength() - position);
        graph.addPoint(EdgePointType.STATION, station);
        return station;
    }

    /** One entry per station filter, each announcement naming its station: "A/DEPARTURE". */
    private static Schedule stations(String... filters) {
        Schedule schedule = new Schedule();
        for (String filter : filters) {
            ScheduleEntry entry = schedule(filter).entries.getFirst();
            for (ScheduleWaitCondition condition : entry.conditions.getFirst()) {
                condition.getData().putString("Url", filter + "/" + condition.getData().getString("Url"));
            }
            schedule.entries.add(entry);
        }
        return schedule;
    }

    private static Schedule schedule(String filter) {
        Schedule schedule = new Schedule();
        ScheduleEntry entry = new ScheduleEntry();
        StationInstruction station = new StationInstruction();
        station.getData().putString("Text", filter);
        entry.instruction = station;
        List<ScheduleWaitCondition> conditions = new ArrayList<>();
        for (AnnounceCondition.Trigger trigger : AnnounceCondition.Trigger.values()) {
            AnnounceCondition condition = new AnnounceCondition(trigger);
            condition.getData().putString("Url", trigger.name());
            condition.getData().putInt("Value", trigger == PROGRESS ? 50 : trigger == BEFORE_ARRIVAL ? 3 : 2);
            conditions.add(condition);
        }
        entry.conditions.add(conditions);
        schedule.entries.add(entry);
        return schedule;
    }
}
