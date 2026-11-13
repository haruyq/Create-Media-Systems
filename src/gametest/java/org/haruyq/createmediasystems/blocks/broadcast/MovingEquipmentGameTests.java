package org.haruyq.createmediasystems.blocks.broadcast;

import java.util.List;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.items.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.items.launcher.DiscCellChargerBlock;
import org.haruyq.createmediasystems.items.launcher.DiscCellTier;
import org.haruyq.createmediasystems.items.launcher.DiscProjectile;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MovingEquipmentGameTests {
    private static MechanicalBearingBlockEntity bearing(GameTestHelper h, BlockPos base) {
        var level = h.getLevel();
        var at = base.below();
        level.setBlockAndUpdate(at, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(DirectionalBlock.FACING, Direction.UP));
        return (MechanicalBearingBlockEntity) level.getBlockEntity(at);
    }

    @GameTest(batch = "launcher", template = "empty", timeoutTicks = 70)
    public static void rotatingEquipmentSavesPowerChargedCellAndPlaybackOnDisassembly(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos basePos = h.absolutePos(new BlockPos(4, 3, 4));
        BlockPos headPos = basePos.above(2), sourcePos = basePos.east(), chargerPos = basePos.west();
        var bearing = bearing(h, basePos);
        level.setBlockAndUpdate(basePos, BroadcastFeature.BASE.get().defaultBlockState());
        level.setBlockAndUpdate(basePos.above(), BroadcastFeature.MAST.get().defaultBlockState());
        level.setBlockAndUpdate(headPos, BroadcastFeature.DIGITAL_HORN.get().defaultBlockState());
        level.setBlockAndUpdate(sourcePos, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(chargerPos, LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState());
        var tower = (BroadcastTowerBlock.Tower) level.getBlockEntity(headPos);
        var base = (BroadcastBaseBlock.Base) level.getBlockEntity(basePos);
        var source = (VideoPlayerBlock.Entity) level.getBlockEntity(sourcePos);
        var charger = (DiscCellCharger) level.getBlockEntity(chargerPos);
        var disc = new ItemStack(DiscFeature.BLU_RAY.get());
        var owner = UUID.randomUUID();
        var url = new DiscUrl(owner, UUID.randomUUID()).toString();
        disc.set(DiscFeature.PLAYLIST.get(), List.of(url, new DiscUrl(owner, UUID.randomUUID()).toString()));
        source.insert(disc, null);
        source.transport().clock().seek(820);
        base.port().receiveEnergy(500, false);
        tower.configure(level, sourcePos, .9f, Streams.Channel.RIGHT);
        int initialPower = base.port().getEnergyStored();
        var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, chargerPos, Direction.WEST);
        h.assertTrue(port != null && port.receiveEnergy(2048, false) == 2048, "A mounted charger can be precharged through FE");
        var cell = new ItemStack(LauncherFeature.cell(DiscCellTier.COPPER));
        charger.insert(cell);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(chargerPos).minmax(new AABB(headPos.east()))));
        level.setBlockAndUpdate(basePos.below(2), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        for (var head : List.of(BroadcastFeature.HORN, BroadcastFeature.DIGITAL_HORN, BroadcastFeature.ARRAY)) {
            h.assertTrue(MovementBehaviour.REGISTRY.get(head.get()) != null, "Each head shares the moving behavior");
        }
        h.runAfterDelay(10, () -> {
            var carrier = bearing.getMovedContraption();
            h.assertTrue(carrier != null && carrier.isAlive() && carrier.getContraption().getBlocks().size() == 5,
                    "The running bearing assembled the mast, both powered devices and the actual linked source");
            var data = carrier.getContraption().getBlocks().get(BlockPos.ZERO).nbt();
            h.assertTrue(BroadcastBaseBlock.stored(data) < initialPower && BroadcastBaseBlock.stored(data) > 0,
                    "The linked deck broadcasts on the carried FE reserve");
            var mock = h.makeMockPlayer(GameType.SURVIVAL);
            BlockPos chargerLocal = chargerPos.subtract(basePos);
            h.assertTrue(carrier.getContraption().getBlocks().get(chargerLocal).state().getValue(DiscCellChargerBlock.CHARGING),
                    "The carried charger's active model tracks the charge in progress");
            var interact = MovingInteractionBehaviour.REGISTRY.get(LauncherFeature.DISC_CELL_CHARGER.get());
            interact.handlePlayerInteraction(mock, InteractionHand.MAIN_HAND, chargerLocal, carrier);
            var returned = mock.getInventory().getItem(0);
            int charged = ChargeableItem.energy(returned);
            h.assertTrue(returned.is(cell.getItem()) && charged > 0 && charged <= 2048,
                    "Extracting the charged cell while mounted returns the real cell");
            interact.handlePlayerInteraction(mock, InteractionHand.MAIN_HAND, chargerLocal, carrier);
            h.assertTrue(mock.getMainHandItem().isEmpty(), "The cell goes back into the mounted charger once");

            carrier.setAngle(90);
            BlockPos sourceAt = BlockPos.containing(carrier.toGlobalVector(sourcePos.subtract(basePos).getCenter(), 1));
            BlockPos chargerAt = BlockPos.containing(carrier.toGlobalVector(chargerLocal.getCenter(), 1));
            h.assertTrue(!sourceAt.equals(sourcePos), "The assembly moved its source to a different block");
            bearing.disassemble();
            var restored = (VideoPlayerBlock.Entity) level.getBlockEntity(sourceAt);
            var restoredCharger = (DiscCellCharger) level.getBlockEntity(chargerAt);
            var restoredTower = (BroadcastTowerBlock.Tower) level.getBlockEntity(headPos);
            restoredTower.followSource(level);
            h.assertTrue(restoredTower.source().filter(sourceAt::equals).isPresent(),
                    "The link follows the source's saved identity to " + sourceAt + ", current " + restoredTower.source()
                    + ", deck " + restored + ", id " + BlockIdentity.of(level, sourceAt));
            h.assertTrue(restored != null && restored.nowPlaying() != null && url.equals(restored.nowPlaying().url())
                    && restored.nowPlaying().ticks() > 820 && ItemStack.matches(restored.slot().get(), disc),
                    "The disc and its advanced playback position survive disassembly");
            h.assertTrue(restoredCharger != null && ChargeableItem.energy(restoredCharger.cell()) == charged
                    && level.getEntitiesOfClass(ItemEntity.class, new AABB(basePos).inflate(4)).isEmpty(),
                    "There is one charged cell and no duplicate drop");
            h.succeed();
        });
    }

    @GameTest(batch = "launcher", template = "empty")
    public static void discStaysOnARotatingCreateSurfaceAndCleansUpOnRemoval(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(4, 2, 4));
        var bearing = bearing(h, base);
        level.setBlockAndUpdate(base, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(base.east(), Blocks.IRON_BLOCK.defaultBlockState());
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(base).minmax(new AABB(base.east()))));
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        h.assertTrue(carrier != null, "The surface assembled");
        var disc = new DiscProjectile(level, h.makeMockPlayer(GameType.SURVIVAL), new ItemStack(Items.MUSIC_DISC_CAT));
        disc.setPos(base.east().getCenter().add(0, 2, 0));
        disc.setDeltaMovement(0, -2, 0);
        disc.tick();
        CompoundTag tag = new CompoundTag();
        disc.addAdditionalSaveData(tag);
        h.assertTrue(disc.embedded() && !disc.isRemoved() && tag.contains("CmsDiscMovingSurface"),
                "The swept disc hits the block inside Create's virtual world: " + disc.position());
        Vec3 first = disc.position();
        carrier.setAngle(90);
        disc.tick();
        h.assertTrue(!disc.isRemoved() && disc.position().distanceTo(first) > .8,
                "The same disc follows the rotated surface from " + first + " to " + disc.position()
                + ", alive " + !disc.isRemoved() + ", carrier " + level.getEntity(carrier.getUUID()));
        bearing.disassemble();
        disc.tick();
        h.assertTrue(disc.isRemoved(), "An absent carrier cannot leave a floating emitter");
        h.succeed();
    }
}
