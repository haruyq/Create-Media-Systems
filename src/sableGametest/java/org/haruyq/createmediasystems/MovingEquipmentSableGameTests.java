package org.haruyq.createmediasystems;

import java.util.List;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.broadcast.BroadcastBaseBlock;
import org.haruyq.createmediasystems.broadcast.BroadcastFeature;
import org.haruyq.createmediasystems.broadcast.BroadcastTowerBlock;
import org.haruyq.createmediasystems.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.launcher.DiscCellTier;
import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.partybox.PartyBoxEntity;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.Streams;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MovingEquipmentSableGameTests {
    @GameTest(template = "empty")
    public static void shipKeepsItsBroadcastLinkEnergyAndChargingItemWhenMoved(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 5, 2));
        level.setBlockAndUpdate(start, BroadcastFeature.BASE.get().defaultBlockState());
        level.setBlockAndUpdate(start.above(), BroadcastFeature.MAST.get().defaultBlockState());
        level.setBlockAndUpdate(start.above(2), BroadcastFeature.ARRAY.get().defaultBlockState());
        level.setBlockAndUpdate(start.east(), AudioFeature.DISC_READER.get().defaultBlockState());
        level.setBlockAndUpdate(start.west(), LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState());
        var source = (DiscReaderBlock.Entity) level.getBlockEntity(start.east());
        var tower = (BroadcastTowerBlock.Tower) level.getBlockEntity(start.above(2));
        var disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString());
        source.insert(disc, null);
        tower.configure(level, start.east(), 1, Streams.Channel.MIX);
        ((BroadcastBaseBlock.Base) level.getBlockEntity(start)).port().receiveEnergy(500, false);
        var charger = (DiscCellCharger) level.getBlockEntity(start.west());
        var cell = new ItemStack(LauncherFeature.cell(DiscCellTier.BRASS));
        ChargeableItem.setEnergy(cell, 75);
        charger.insert(cell);
        List<BlockPos> blocks = List.of(start, start.above(), start.above(2), start.east(), start.west());
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, start, blocks, new BoundingBox3i(
                start.getX() - 1, start.getY(), start.getZ(), start.getX() + 1, start.getY() + 2, start.getZ()));
        var plot = ship.getPlot().getCenterBlock();
        var afloat = (BroadcastTowerBlock.Tower) level.getBlockEntity(plot.above(2));
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(7, 0, 12);
        afloat.relay(level);
        var base = afloat.base(level).orElseThrow();
        h.assertTrue(afloat.source().orElseThrow().equals(plot.east()) && base.port().getEnergyStored() == 488,
                "The transformed tower relays the ship's source and spends its original FE");
        var chargingPort = level.getCapability(Capabilities.EnergyStorage.BLOCK, plot.west(), Direction.SOUTH);
        h.assertTrue(chargingPort != null && chargingPort.receiveEnergy(128, false) == 128,
                "The assembled charger's FE port is live");

        var mock = h.makeMockPlayer(GameType.SURVIVAL);
        mock.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PartyBoxFeature.PARTYBOX.get()));
        var localTop = plot.east().getCenter().add(0, .5, 0);
        var worldTop = SableCompat.toWorld(level, plot.east(), localTop);
        var hit = new BlockHitResult(localTop, Direction.UP, plot.east(), false);
        PartyBoxFeature.PARTYBOX.get().useOn(new UseOnContext(mock, InteractionHand.MAIN_HAND, hit));
        h.assertTrue(level.getEntitiesOfClass(PartyBoxEntity.class, new AABB(worldTop, worldTop).inflate(.5)).size() == 1
                && mock.getMainHandItem().isEmpty(), "The trolley places in world space on the turned deck");

        BlockPos land = start.south(6);
        var transform = new SubLevelAssemblyHelper.AssemblyTransform(plot, land, 1, Rotation.CLOCKWISE_90, level);
        SubLevelAssemblyHelper.moveBlocks(level, transform, blocks.stream().map(pos -> plot.offset(pos.subtract(start))).toList());
        remove(level, ship);
        var restored = (BroadcastTowerBlock.Tower) level.getBlockEntity(transform.apply(plot.above(2)));
        restored.followSource(level);
        var dock = (DiscCellCharger) level.getBlockEntity(transform.apply(plot.west()));
        h.assertTrue(restored.source().orElseThrow().equals(transform.apply(plot.east()))
                && restored.base(level).orElseThrow().port().getEnergyStored() == 488,
                "Disassembly keeps the new source coordinates and remaining reserve");
        h.assertTrue(ChargeableItem.energy(dock.cell()) == 203
                && level.getEntitiesOfClass(ItemEntity.class, new AABB(start).inflate(12)).isEmpty(),
                "The charging item and its FE are restored without duplication");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void launcherUsesBothTransformsOnAContraptionAssembledInsideAShip(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos start = h.absolutePos(new BlockPos(4, 1, 4));
        level.setBlockAndUpdate(start, Blocks.IRON_BLOCK.defaultBlockState());
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, start, List.of(start),
                new BoundingBox3i(start.getX(), start.getY(), start.getZ(), start.getX(), start.getY(), start.getZ()));
        var plot = ship.getPlot().getCenterBlock();
        var bearingPos = plot.above();
        var deck = bearingPos.above();
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(deck.east(), Blocks.IRON_BLOCK.defaultBlockState());
        h.assertTrue(level.getBlockEntity(bearingPos) instanceof MechanicalBearingBlockEntity,
                "The bearing is installed in the ship's plot");
        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).minmax(new AABB(deck.east()))));
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        h.assertTrue(carrier != null && SableCompat.containing(level, carrier.blockPosition()) == ship,
                "The contraption lives in the ship's plot");
        ship.logicalPose().orientation().rotationY(Math.PI / 2);
        ship.logicalPose().position().add(1, 0, 1);
        ship.updateBoundingBox();
        ship.forceUpdateGlobalBounds();
        var localTop = new Vec3(1.5, 1, .5);
        var top = SableCompat.toWorld(level, plot, carrier.toGlobalVector(localTop, 1));
        var disc = new DiscProjectile(level, h.makeMockPlayer(GameType.SURVIVAL), new ItemStack(Items.MUSIC_DISC_CAT));
        disc.setPos(top.add(0, 1.5, 0));
        disc.setDeltaMovement(0, -2, 0);
        var hit = ContraptionSurface.nearest(disc, disc.position(), disc.position().add(disc.getDeltaMovement()));
        h.assertTrue(hit != null && hit.location().distanceTo(top) < .05,
                "The projected ray reaches the plot's bearing at " + top + ", hit " + hit + ", bounds " + ship.boundingBox());
        disc.tick();
        var data = new CompoundTag();
        disc.addAdditionalSaveData(data);
        h.assertTrue(disc.embedded() && data.getCompound("CmsDiscMovingSurface").getBoolean("Contraption")
                && disc.position().distanceTo(top) < .05, "The disc embeds in the moving block at " + top + ", actual " + disc.position());
        carrier.setAngle(90);
        disc.tick();
        var next = SableCompat.toWorld(level, plot, carrier.toGlobalVector(localTop, 1));
        h.assertTrue(!disc.isRemoved() && disc.position().distanceTo(next) < .05, "The embedded disc follows both motions");
        bearing.disassemble();
        remove(level, ship);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void shaftsSupplyTheTowerAndChargerOnAShip(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos start = h.absolutePos(new BlockPos(4, 3, 4));
        var motor = AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalBlock.FACING, Direction.UP);
        level.setBlockAndUpdate(start.below(), motor);
        level.setBlockAndUpdate(start.west().below(), motor);
        level.setBlockAndUpdate(start, BroadcastFeature.BASE.get().defaultBlockState());
        level.setBlockAndUpdate(start.above(), BroadcastFeature.HORN.get().defaultBlockState());
        level.setBlockAndUpdate(start.west(), LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState());
        var blocks = List.of(start.below(), start.west().below(), start, start.above(), start.west());
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, start, blocks, new BoundingBox3i(
                start.getX() - 1, start.getY() - 1, start.getZ(), start.getX(), start.getY() + 1, start.getZ()));
        var plot = ship.getPlot().getCenterBlock();
        var base = (BroadcastBaseBlock.Base) level.getBlockEntity(plot);
        var charger = (DiscCellCharger) level.getBlockEntity(plot.west());
        charger.insert(new ItemStack(LauncherFeature.cell(DiscCellTier.COPPER)));
        h.runAfterDelay(8, () -> {
            h.assertTrue(Math.abs(base.getSpeed()) >= BroadcastBaseBlock.Base.MIN_RPM && base.port().getEnergyStored() == 0,
                    "The tower gets RPM through its bottom shaft on the ship without drawing reserve FE");
            h.assertTrue(ChargeableItem.energy(charger.cell()) > 0, "The other shaft charges a cell on the ship");
            remove(level, ship);
            h.succeed();
        });
    }

    private static void remove(ServerLevel level, ServerSubLevel ship) {
        var container = SubLevelContainer.getContainer(level);
        var plot = ship.getPlot().plotPos;
        var grid = container.getOrigin();
        container.removeSubLevel(plot.x - grid.x, plot.z - grid.y, SubLevelRemovalReason.REMOVED);
    }
}
