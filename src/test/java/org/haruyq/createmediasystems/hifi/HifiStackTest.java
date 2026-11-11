package org.haruyq.createmediasystems.hifi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.playback.Tone;
import org.haruyq.createmediasystems.stream.Streams;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
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

    /** {@code stack} as it would be saved, with {@code change} made to that. */
    private static HifiStackBlock.Entity with(HifiStackBlock.Entity stack, java.util.function.Consumer<CompoundTag> change) {
        CompoundTag tag = stack.saveWithFullMetadata(RegistryAccess.EMPTY);
        change.accept(tag);
        stack.loadWithComponents(tag, RegistryAccess.EMPTY);
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
        assertEquals(Unit.CASSETTE_DECK.height + Unit.AMPLIFIER.height,
                HifiLayout.bottom(List.of(Unit.CASSETTE_DECK, Unit.AMPLIFIER, Unit.TUNER), 2));
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

    @Test
    void itsToneIsWhatItsUnitsAreSetTo() {
        HifiStackBlock.Entity stack = stack(Unit.AMPLIFIER, Unit.EQUALIZER);
        assertSame(Tone.FLAT, stack.tone(), "on standby");
        with(stack, tag -> {
            tag.putBoolean("Power", true);
            tag.putInt("Preset", HifiStackBlock.Preset.HEAVY.ordinal());
        });
        assertEquals(0, stack.tone().bands().length, "the equalizer isn't plugged in");
        with(stack, tag -> tag.putInt("Cables", Cable.EQUALIZER.bit()));
        float[] bands = stack.tone().bands();
        assertEquals(6 * 3, bands.length, "HEAVY leaves 1 kHz alone");
        assertEquals(HifiStackBlock.Preset.HEAVY.gains[0], bands[2], "at the middle effect level");
        assertEquals(1, stack.tone().width());
        with(stack, tag -> {
            tag.putInt("Effect", 100);
            tag.putBoolean("Wide", true);
            tag.putBoolean("Bass", true);
            tag.putBoolean("Direct", true);
        });
        assertEquals(3, stack.tone().bands().length, "a disc direct: the bass boost alone");
        assertTrue(stack.tone().width() > 1);
        with(stack, tag -> tag.putInt("Input", HifiStackBlock.Input.TAPE.ordinal()));
        bands = stack.tone().bands();
        assertEquals((6 + 2) * 3, bands.length, "the preset, the bass boost and a tape's dull top");
        assertEquals(HifiStackBlock.MAX_GAIN, bands[2], "twice 6 dB at the full effect level");
        assertTrue(bands[bands.length - 1] < 0);
    }

    @Test
    void aTrackTakenUpWhereItWasLeftCountsOnFromThere() {
        DiscReaderBlock.Entity source = new DiscReaderBlock.Entity(new BlockPos(9, 64, 9),
                AudioFeature.DISC_READER.get().defaultBlockState());
        Streams.loading(source, null, 600, true);
        assertEquals(600, Streams.played(source, 1), "its emitters haven't had their turn yet");
        // Nobody is there to load it: it doesn't wait for them.
        assertEquals(600, Streams.played(source, 2 * Streams.RELAY_TICKS));
        assertEquals(650, Streams.played(source, 2 * Streams.RELAY_TICKS + 50));
        Streams.loading(source, null, 600, false);
        assertEquals(600, Streams.played(source, 2 * Streams.RELAY_TICKS + 50), "one that waits for someone to come");
    }
}
