package org.haruyq.createmediasystems.blocks.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class LineMixerBlockTest {
    private static BlockState half(Direction facing, boolean right) {
        return AudioFeature.LINE_MIXER.get().defaultBlockState().setValue(LineMixerBlock.FACING, facing)
                .setValue(LineMixerBlock.RIGHT, right);
    }

    /** Where a structure puts the block at {@code pos}: mirrored, then turned, as it does each block's state. */
    private static BlockPos turned(BlockPos pos, Mirror mirror, Rotation rotation) {
        int x = pos.getX(), z = pos.getZ();
        if (mirror == Mirror.LEFT_RIGHT) {
            z = -z;
        } else if (mirror == Mirror.FRONT_BACK) {
            x = -x;
        }
        return new BlockPos(x, pos.getY(), z).rotate(rotation);
    }

    /** Create's contraptions, schematics and structures turn and mirror blocks: its halves still find each other. */
    @Test
    void itsHalvesStayTogetherTurnedAndMirrored() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Rotation rotation : Rotation.values()) {
                for (Mirror mirror : Mirror.values()) {
                    String how = facing + " " + rotation + " " + mirror;
                    BlockState one = half(facing, false).mirror(mirror).rotate(rotation);
                    BlockState other = half(facing, true).mirror(mirror).rotate(rotation);
                    BlockPos oneAt = turned(BlockPos.ZERO, mirror, rotation);
                    BlockPos otherAt = turned(BlockPos.ZERO.relative(LineMixerBlock.right(half(facing, false))), mirror, rotation);
                    assertNotEquals(one.getValue(LineMixerBlock.RIGHT), other.getValue(LineMixerBlock.RIGHT), how);
                    BlockPos left = one.getValue(LineMixerBlock.RIGHT) ? otherAt : oneAt;
                    assertEquals(left, LineMixerBlock.root(oneAt, one), how);
                    assertEquals(left, LineMixerBlock.root(otherAt, other), how);
                }
            }
        }
    }

    /** Aimed at, either half outlines the whole mixer; each still stands on its own block alone. */
    @Test
    void eitherHalfOutlinesBothAndCollidesAsItself() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos left = BlockPos.ZERO, right = left.relative(LineMixerBlock.right(half(facing, false)));
            AABB whole = new AABB(left).minmax(new AABB(right)).setMaxY(LineMixerBlock.HEIGHT / 16);
            assertEquals(whole, half(facing, false).getShape(EmptyBlockGetter.INSTANCE, left).bounds().move(left), facing.toString());
            assertEquals(whole, half(facing, true).getShape(EmptyBlockGetter.INSTANCE, right).bounds().move(right), facing.toString());
            assertEquals(new AABB(0, 0, 0, 1, LineMixerBlock.HEIGHT / 16, 1),
                    half(facing, true).getCollisionShape(EmptyBlockGetter.INSTANCE, right).bounds(), facing.toString());
        }
    }

    @Test
    void aPointOfThePanelIsFoundAgainFromWhereItIsInTheBlock() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Vec3 there = LineMixerBlock.panel(facing, LineMixerBlock.place(facing, 27, 4, 3));
            assertEquals(27, there.x, 1e-9);
            assertEquals(4, there.y, 1e-9);
            assertEquals(3, there.z, 1e-9);
            // The right half is where the panel's right half is.
            Vec3 right = LineMixerBlock.place(facing, 24, 0, 8);
            assertEquals(BlockPos.ZERO.relative(LineMixerBlock.right(half(facing, false))), BlockPos.containing(right));
        }
    }

    @Test
    void aFaderIsSilentAtItsLowestAndAsItIsAtItsHighest() {
        assertEquals(0, LineMixerBlock.gain(0));
        assertEquals(1, LineMixerBlock.gain(1));
    }
}
