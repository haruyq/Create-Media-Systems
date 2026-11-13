package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class LightingControlTest {
    @Test
    void everyControlIsFoundAtItsOwnPlace() {
        for (ConsoleLayout.Slot slot : ConsoleLayout.SLOTS) {
            assertSame(slot, ConsoleLayout.slot(middle(slot.area(), slot.monitor()), slot.monitor()),
                    slot + " is covered by another control");
        }
    }

    @Test
    void gapsBetweenPresetPadsDoNotActivateControls() {
        LightingConsolePage.Area first = ConsoleLayout.slot(Control.PRESET, 0).area();
        LightingConsolePage.Area beside = ConsoleLayout.slot(Control.PRESET, 1).area();
        LightingConsolePage.Area behind = ConsoleLayout.slot(Control.PRESET, 3).area();
        float middle = (first.bottom() + first.top()) / 2;
        assertNull(ConsoleLayout.slot(new Vec3((first.right() + beside.left()) / 2, 0, middle), false));
        float between = first.bottom() > behind.top() ? (first.bottom() + behind.top()) / 2 : (first.top() + behind.bottom()) / 2;
        assertNull(ConsoleLayout.slot(new Vec3((first.left() + first.right()) / 2, 0, between), false));
    }

    /** The middle of {@code area}, on the deck or a monitor. */
    private static Vec3 middle(LightingConsolePage.Area area, boolean monitor) {
        double x = (area.left() + area.right()) / 2, vertical = (area.bottom() + area.top()) / 2;
        return monitor ? new Vec3(x, vertical, 0) : new Vec3(x, 0, vertical);
    }

    @Test
    void aPagesControlIsPointedAtOnlyWithinItsOwnArea() {
        LightingConsolePage.Control key = new LightingConsolePage.Control() {
            @Override
            public LightingConsolePage.Area area() {
                return new LightingConsolePage.Area(1.1f, 0.2f, 1.4f, 0.5f);
            }

            @Override
            public boolean monitor() {
                return false;
            }

            @Override
            public net.minecraft.network.chat.Component label() {
                return net.minecraft.network.chat.Component.empty();
            }

            @Override
            public net.minecraft.network.chat.Component value() {
                return net.minecraft.network.chat.Component.empty();
            }
        };
        var inside = new LightingControls.Hit(null, null, new Vec3(1.2, 0.9, 0.3), false, key);
        assertSame(inside, inside.within());
        assertEquals(key.area(), inside.area());
        assertNull(new LightingControls.Hit(null, null, new Vec3(1.5, 0.9, 0.3), false, key).within());
        // On a monitor, the area's vertical is y, not z.
        var shown = new LightingControls.Hit(null, null, new Vec3(1.2, 0.3, 9), true, key);
        assertSame(shown, shown.within());
    }

    @Test
    void controlsKeepTheirWireIds() {
        // Ids clients and servers of other versions send: the enum's order before ids were declared.
        String[] wire = {"GROUP", "BRIGHTNESS", "MASTER", "COLOR", "SLOT", "COUNT", "RGB", "MAPPING", "VIDEO", "AUDIO",
                "BAND", "MOTION", "PATTERN", "RANGE", "SPREAD", "SPEED", "AIM", "ENABLED", "CYCLE", "TARGET", "PRESET",
                "SOURCES", null, null, "RANGE_ALL", "STROBE", "CHASE"};
        for (int id = 0; id < wire.length; id++) {
            Control control = Control.byId(id);
            assertEquals(wire[id], control == null ? null : control.name(), "id " + id);
        }
    }

    @Test
    void theStrobeRowStepsThroughTheStrobeThenTheRandomLightsAndRound() {
        LightingSettings settings = new LightingSettings();
        assertEquals(new LightingPackets.Stepped(Control.STROBE, 0, 1), Control.STROBE.step(settings, 0, 1, false));
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 8), Control.STROBE.step(settings, 0, -1, false));
        settings.selected().strobe = LightingSettings.STROBE.max();
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 1), Control.STROBE.step(settings, 0, 1, false));
        settings.selected().strobe = 0;
        settings.selected().chase = 1;
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 3), Control.STROBE.step(settings, 0, 1, false));
        assertEquals(new LightingPackets.Stepped(Control.STROBE, 0, LightingSettings.STROBE.max()),
                Control.STROBE.step(settings, 0, -1, false));
        settings.selected().chase = 3;
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 5), Control.STROBE.step(settings, 0, 1, false));
        settings.selected().chase = 8;
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 0), Control.STROBE.step(settings, 0, 1, false));
        // One a mod set between the row's: the next above it, and on from there.
        settings.selected().chase = 6;
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 0), Control.STROBE.step(settings, 0, 1, false));
        assertEquals(new LightingPackets.Stepped(Control.CHASE, 0, 5), Control.STROBE.step(settings, 0, -1, false));
    }

    @Test
    void malformedControlPacketsAreRejectedBeforeReadingConsoleState() {
        int rgb = Control.RGB.id;
        int preset = Control.PRESET.id;
        int rangeAll = Control.RANGE_ALL.id;
        LightingPackets.Change[] malformed = {
                new LightingPackets.Change(BlockPos.ZERO, -1, rgb, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, LightingSettings.GROUPS, rgb, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, -1, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, 27, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, -1, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 64, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, Float.NaN, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, Float.POSITIVE_INFINITY, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, 0, Float.NEGATIVE_INFINITY),
                new LightingPackets.Change(BlockPos.ZERO, 0, preset, 6, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, preset, 0, 3, 0),
                // The preset bank and save keys' old ids: the client's own, never sent.
                new LightingPackets.Change(BlockPos.ZERO, 0, 22, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, 23, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rangeAll, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rangeAll, 0, 2, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rangeAll, 0, -300, 0)
        };
        for (LightingPackets.Change change : malformed) {
            assertFalse(LightingPackets.apply(null, change, null));
        }
    }

    @Test
    void fixturesComeLeftToRightAsTheConsolesPlayerSeesThemThenNearFirst() {
        // Facing south, its player looks north: east is their right, and north further from them.
        BlockPos left = new BlockPos(-3, 70, -10);
        BlockPos right = new BlockPos(4, 64, -9);
        assertTrue(ConsoleLayout.compareFixtures(Direction.SOUTH, left, right) < 0);
        assertTrue(ConsoleLayout.compareFixtures(Direction.NORTH, left, right) > 0);
        assertTrue(ConsoleLayout.compareFixtures(Direction.SOUTH, new BlockPos(0, 64, -5), new BlockPos(0, 64, -9)) < 0);
        assertEquals(0, ConsoleLayout.compareFixtures(Direction.EAST, new BlockPos(5, 64, 2), new BlockPos(5, 80, 2)));
    }

    @Test
    void multiblockCoordinatesAndControlSurfacesAgreeForEveryFacing() {
        BlockPos origin = new BlockPos(19, -32, -47);
        assertEquals(origin.east(), ConsoleLayout.at(origin, Direction.SOUTH, 1, 0, 0));
        assertEquals(origin.west(), ConsoleLayout.at(origin, Direction.NORTH, 1, 0, 0));
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            for (int column = 0; column < 4; column++) {
                for (int back = 0; back < 2; back++) {
                    for (int height = 0; height < 2; height++) {
                        BlockPos part = ConsoleLayout.at(origin, facing, column, height, back);
                        Vec3 local = ConsoleLayout.toLocal(origin, facing, part.getCenter());
                        assertEquals(new Vec3(column + 0.5, height + 0.5, back + 0.5), local);
                    }
                }
            }
        }
    }
}
