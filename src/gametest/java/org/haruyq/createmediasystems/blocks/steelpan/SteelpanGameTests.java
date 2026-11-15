package org.haruyq.createmediasystems.blocks.steelpan;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SteelpanGameTests {
    @GameTest(template="empty")
    public static void standRegistersPlacesRotatesAndAcceptsAll29StrikesOnDedicatedServer(GameTestHelper helper) {
        BlockPos pos=new BlockPos(2,2,2);
        helper.setBlock(pos,SteelpanFeature.BLOCK.get());
        SteelpanBlock.Entity entity=helper.getBlockEntity(pos);
        helper.assertTrue(entity!=null,"Placed stand has no renderer entity");
        var state=helper.getBlockState(pos);
        for(int i=0;i<4;i++) {
            helper.assertTrue(state.getValue(SteelpanBlock.FACING)==new Direction[]{Direction.SOUTH,Direction.WEST,Direction.NORTH,Direction.EAST}[i],"Bad stand rotation");
            state=state.rotate(Rotation.CLOCKWISE_90);
        }
        for (int midi = SteelpanLayout.LOWEST; midi <= SteelpanLayout.HIGHEST; midi++) entity.strike(midi, 127);
        entity.strike(59, 100);
        entity.strike(89, 100);
        entity.strike(60, 0);
        entity.strike(60, 128);
        helper.assertTrue(helper.getBlockEntity(pos) == entity, "Striking replaced the instrument");
        var level = helper.getLevel();
        helper.assertTrue(level.getRecipeManager().byKey(CreateMediaSystems.id("steelpan_low_c"))
                .filter(recipe -> recipe.value().getResultItem(level.registryAccess()).is(SteelpanFeature.ITEM.get())).isPresent(),
                "The steelpan crafting recipe did not load");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bearingCarriesTheSteelpanAndItsStrikes(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos bearingPos = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos deck = bearingPos.east(), pos = deck.above();
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(pos, SteelpanFeature.BLOCK.get().defaultBlockState());
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).deflate(.2).minmax(new AABB(pos).deflate(.2))));

        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        helper.assertTrue(carrier != null && level.getBlockState(pos).isAir(), "The steelpan was left behind");
        var local = pos.subtract(deck);
        var target = new SteelpanPackets.Target(carrier.getId(), local);
        helper.assertTrue(carrier.getContraption().getActorAt(local) != null, "The mounted steelpan has no movement actor");
        var before = target.origin(level, 1);
        carrier.setAngle(90);
        var after = target.origin(level, 1);
        helper.assertTrue(before != null && after != null && before.distanceTo(after) > .7,
                "The sound origin did not follow the moving contraption");
        MountedSteelpan.strike(level, target, 88, 127);
        bearing.disassemble();
        helper.assertTrue(target.origin(level, 1) == null, "Disassembly left a playable mounted steelpan behind");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void everyPitchIsHittableOnTheRealTiltedSurfaceAfterRotation(GameTestHelper helper) {
        for(var facing:Direction.Plane.HORIZONTAL) for(var note:SteelpanLayout.DATA.notes()) {
            Vec3 target=new Vec3(note.center()[0],note.center()[1],note.center()[2])
                    .xRot((float)-Math.toRadians(SteelpanLayout.DATA.tilt()))
                    .add(0,SteelpanLayout.DATA.rim_height(),0)
                    .yRot((float)-Math.toRadians(facing.toYRot()));
            int hit=SteelpanBlock.rayNote(target.add(0,1,0),new Vec3(0,-1,0),facing);
            helper.assertTrue(hit==note.midi(),"Wrong field "+note.label()+" at "+facing);
        }
        helper.succeed();
    }
}
