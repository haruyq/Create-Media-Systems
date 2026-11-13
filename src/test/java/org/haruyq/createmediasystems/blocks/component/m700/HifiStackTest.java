package org.haruyq.createmediasystems.blocks.component.m700;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class HifiStackTest {
    private static HifiStackBlock.Entity stack(Unit... units) {
        HifiStackBlock.Entity stack = new HifiStackBlock.Entity(new BlockPos(3, 64, 3),
                HifiFeature.HIFI_STACK.get().defaultBlockState());
        for (Unit unit : units) {
            stack.add(unit);
        }
        return stack;
    }

    @Test
    void aWholeSetFillsItsBlockAndTakesOneOfEach() {
        HifiStackBlock.Entity stack = stack();
        for (Unit unit : Unit.values()) {
            assertTrue(stack.canAdd(unit), unit + " fits");
            stack.add(unit);
            assertFalse(stack.canAdd(unit), "one " + unit + " is enough");
        }
        assertEquals(Unit.ROOM, stack.height());
    }

    @Test
    void controlsOfAFaceKeepToItAndToThemselves() {
        for (Control control : Control.values()) {
            assertTrue(control.left >= 0 && control.right <= Unit.WIDTH && control.bottom >= 0.25f
                    && control.top <= control.unit.height && control.left < control.right && control.bottom < control.top,
                    control + " is on its unit");
            assertSame(control, Control.at(control.unit, control.rear(), control.centerX(), control.centerY()));
            for (Control other : Control.values()) {
                if (other != control && other.unit == control.unit && other.rear() == control.rear()) {
                    assertTrue(control.right <= other.left || other.right <= control.left
                            || control.top <= other.bottom || other.top <= control.bottom, control + " overlaps " + other);
                }
            }
        }
        for (Cable cable : Cable.values()) {
            assertSame(Unit.AMPLIFIER, Control.socket(cable, true).unit);
            assertSame(cable.unit, Control.socket(cable, false).unit);
        }
    }

    @Test
    void aLookFindsTheControlItIsOnWhicheverWayTheStackFaces() {
        List<Unit> units = List.of(Unit.CASSETTE_DECK, Unit.AMPLIFIER);
        Control knob = Control.VOLUME;
        Control terminal = Control.SPEAKER_LEFT;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Vec3 out = Vec3.atLowerCornerOf(facing.getNormal());
            Vec3 onKnob = HifiLayout.onFace(facing, false, knob.centerX(), Unit.CASSETTE_DECK.height + knob.centerY(), 0);
            HifiLayout.Aim aim = HifiLayout.aim(facing, onKnob.add(out.scale(2)).add(0, 0.5, 0), out.scale(-2).add(0, -0.5, 0));
            assertNotNull(aim);
            assertFalse(aim.rear());
            assertSame(knob, HifiLayout.control(units, aim), "from the front, facing " + facing);

            Vec3 onTerminal = HifiLayout.onFace(facing, true, terminal.centerX(), Unit.CASSETTE_DECK.height + terminal.centerY(), 0);
            aim = HifiLayout.aim(facing, onTerminal.subtract(out.scale(1.5)), out);
            assertNotNull(aim);
            assertTrue(aim.rear());
            assertSame(terminal, HifiLayout.control(units, aim), "from behind, facing " + facing);
            // A stack is as deep as from its back to its front, in the middle of its block.
            assertEquals(Unit.DEPTH / 16, onKnob.subtract(HifiLayout.onFace(facing, true, Unit.WIDTH - knob.centerX(),
                    Unit.CASSETTE_DECK.height + knob.centerY(), 0)).length(), 1e-6);
        }
        assertNull(HifiLayout.aim(Direction.NORTH, new Vec3(0.5, 0.5, 0.5), new Vec3(0, 0, -1)), "from inside it");
    }
}
