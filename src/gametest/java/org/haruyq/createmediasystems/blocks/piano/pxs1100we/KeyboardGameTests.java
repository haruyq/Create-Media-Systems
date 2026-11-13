package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
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
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KeyboardGameTests {
    @GameTest(template = "empty")
    public static void keyboardCraftsPlacesChargesAndDropsAsOneItem(GameTestHelper test) {
        var level = test.getLevel();
        var recipe = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.SMOOTH_QUARTZ), new ItemStack(Items.SMOOTH_QUARTZ), new ItemStack(Items.SMOOTH_QUARTZ),
                AllItems.BRASS_SHEET.asStack(), new ItemStack(Items.NOTE_BLOCK), AllItems.BRASS_SHEET.asStack(),
                AllItems.ANDESITE_ALLOY.asStack(), AllItems.PRECISION_MECHANISM.asStack(), AllItems.ANDESITE_ALLOY.asStack()));
        var item = PxS1100Feature.ITEM.get();
        test.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, recipe, level)
                .filter(match -> match.id().equals(CreateMediaSystems.id("px_s1100"))
                        && match.value().assemble(recipe, level.registryAccess()).is(item)).isPresent(), "Wrong keyboard recipe");
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        var root = test.absolutePos(new BlockPos(2, 2, 2));
        player.setYRot(90);
        player.setPos(root.getX() + 2.5, root.getY(), root.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        var facing = player.getDirection().getOpposite();
        var placed = PxS1100Feature.BLOCK.get().defaultBlockState().setValue(KeyboardBlock.FACING, facing)
                .setValue(KeyboardBlock.FULL_SIZE, true);
        for (var part : KeyboardBlock.parts(placed)) {
            if (part.y == 0) level.setBlockAndUpdate(part.at(root, placed).below(), Blocks.STONE.defaultBlockState());
        }
        var treble = KeyboardBlock.Part.TOP_TREBLE.at(root, placed);
        var dcIn = KeyboardBlock.dcInput(placed).at(root, placed);
        var hit = new BlockHitResult(root.below().getCenter().add(0, .5, 0), Direction.UP, root.below(), false);
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        level.setBlockAndUpdate(treble, Blocks.STONE.defaultBlockState());
        test.assertFalse(item.place(context).consumesAction() || !level.getBlockState(root).isAir(),
                "An obstruction allowed partial placement");
        level.removeBlock(treble, false);
        test.assertTrue(item.place(context).consumesAction() && player.getMainHandItem().isEmpty(),
                "The recipe output did not place the instrument");
        var keyboard = (KeyboardBlockEntity) level.getBlockEntity(root);
        test.assertTrue(keyboard != null && KeyboardBlock.part(level.getBlockState(treble), KeyboardBlock.Part.TOP_TREBLE, placed)
                && level.getBlockEntity(treble) == null, "The upper treble part did not share the root block entity");
        var model = KeyboardLayout.PX;
        var low = model.keys.getFirst().hits().getFirst();
        var high = model.keys.getLast().hits().getFirst();
        var a = KeyboardBlock.point(root, placed, new Vec3(low.maxX(), low.y(), low.minZ()));
        var b = KeyboardBlock.point(root, placed, new Vec3(high.minX(), low.y(), low.minZ()));
        test.assertTrue(Math.abs(a.distanceTo(b) - (Layout.KEYS_RIGHT - Layout.KEYS_LEFT) / 16) < .00001
                && KeyboardBlock.local(root, placed, a).distanceTo(new Vec3(low.maxX(), low.y(), low.minZ())) < .00001,
                "The installed keybed must match Koland and preserve hit coordinates");
        var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, dcIn, facing.getOpposite());
        test.assertTrue(port != null && level.getCapability(Capabilities.EnergyStorage.BLOCK, dcIn, facing) == null
                && level.getCapability(Capabilities.EnergyStorage.BLOCK, root, facing.getOpposite()) == null,
                "The DC input must face the back of the bass half");
        test.assertTrue(port.receiveEnergy(4000, true) == 2048 && port.getEnergyStored() == 0
                && port.receiveEnergy(200, false) == 200 && !keyboard.runtime().powered(), "FE simulation or off switch is incorrect");
        var data = keyboard.saveWithoutMetadata(level.registryAccess());
        data.getCompound(KeyboardBlockEntity.DATA).putBoolean("Switch", true);
        keyboard.loadWithComponents(data, level.registryAccess());
        test.assertTrue(keyboard.runtime().powered(), "Power switch or FE was lost after reload");
        keyboard.runtime().tick();
        test.assertTrue(port.getEnergyStored() == 190 && port.extractEnergy(100, false) == 0, "Wrong power draw");
        keyboard.decorations(false, false);
        var bass = KeyboardBlock.Part.BASS.at(root, placed);
        test.assertFalse(keyboard.state().getValue(KeyboardBlock.STAND) || level.getBlockState(bass).getValue(KeyboardBlock.RACK)
                || !level.getBlockState(dcIn).isAir() || !level.getBlockState(treble).isAir(), "Tabletop mode left stand parts");
        test.assertTrue(!port.canReceive() && level.getCapability(Capabilities.EnergyStorage.BLOCK, bass, facing.getOpposite()) != null,
                "Tabletop mode did not move the DC input down to the case");
        var rotated = keyboard.state().setValue(KeyboardBlock.FACING, Rotation.CLOCKWISE_90.rotate(facing));
        for (var part : KeyboardBlock.parts(rotated)) {
            level.setBlockAndUpdate(part.at(root, rotated).below(), Blocks.STONE.defaultBlockState());
        }
        var wrenchHit = new BlockHitResult(root.getCenter(), Direction.UP, root, false);
        test.assertTrue(PxS1100Feature.BLOCK.get().onWrenched(keyboard.state(),
                new UseOnContext(player, InteractionHand.MAIN_HAND, wrenchHit)).consumesAction()
                && level.getBlockState(bass).isAir() && level.getBlockEntity(root) == keyboard
                && keyboard.runtime().energy() == 190, "Wrench rotation misplaced a half or reset the keyboard");
        bass = KeyboardBlock.Part.BASS.at(root, rotated);
        level.destroyBlock(bass, true, player);
        for (var part : KeyboardBlock.parts(rotated)) {
            test.assertTrue(level.getBlockState(part.at(root, rotated)).isAir(), "Breaking the bass part left " + part);
        }
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(3), drop -> drop.getItem().is(item));
        test.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "Expected exactly one keyboard drop");
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void keyboardCarriesPowerAndAllPartsOnCreate(GameTestHelper test) {
        var level = test.getLevel();
        var root = test.absolutePos(new BlockPos(3, 4, 3));
        var deck = root.below();
        var bearingPos = deck.below();
        var state = PxS1100Feature.BLOCK.get().defaultBlockState().setValue(KeyboardBlock.FULL_SIZE, true);
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(DirectionalBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        test.assertTrue(KeyboardBlock.place(level, root, state), "Fixture placement failed");
        var source = (KeyboardBlockEntity) level.getBlockEntity(root);
        source.runtime().charge(1400, false);
        var data = source.saveWithoutMetadata(level.registryAccess());
        data.getCompound(KeyboardBlockEntity.DATA).putIntArray("Tape", new int[] {
                0, KeyboardMidi.note(60, 92).pack(), 12, KeyboardMidi.note(60, 0).pack()});
        source.loadWithComponents(data, level.registryAccess());
        source.decorations(true, false);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).deflate(.2).minmax(new AABB(root).deflate(.2))));
        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        test.assertTrue(carrier != null && carrier.getContraption().getBlocks().size() == KeyboardBlock.parts(state).size() + 1,
                "Create did not pick up the whole keyboard");
        for (var part : KeyboardBlock.parts(state)) {
            test.assertTrue(level.getBlockState(part.at(root, state)).isAir(), "Create left behind " + part);
        }
        var local = root.subtract(deck);
        var target = new KeyboardPackets.Target(carrier.getId(), local);
        var runtime = MountedKeyboard.find(level, target);
        test.assertTrue(runtime != null && runtime.energy() == 1400, "Captured keyboard lost its FE or actor");
        runtime.charge(450, false);
        carrier.setAngle(90);
        var landedPos = BlockPos.containing(carrier.toGlobalVector(Vec3.atCenterOf(local), 1));
        bearing.disassemble();
        var landed = (KeyboardBlockEntity) level.getBlockEntity(landedPos);
        test.assertTrue(landed != null && landed.runtime().energy() == 1850 && landed.runtime().view().getInt("Events") == 2
                && !landed.state().getValue(KeyboardBlock.RACK), "Disassembly lost power or the rack setting");
        for (var part : KeyboardBlock.parts(state)) {
            test.assertTrue(KeyboardBlock.part(level.getBlockState(part.at(landedPos, landed.state())), part, landed.state()),
                    "Create did not restore " + part);
        }
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void compactKeyboardExpandsWhenItsNewFootprintIsClear(GameTestHelper test) {
        var level = test.getLevel();
        var root = test.absolutePos(new BlockPos(3, 2, 3));
        var compact = PxS1100Feature.BLOCK.get().defaultBlockState();
        var full = compact.setValue(KeyboardBlock.FULL_SIZE, true);
        for (var part : KeyboardBlock.parts(full)) {
            if (part.y == 0) level.setBlockAndUpdate(part.at(root, full).below(), Blocks.STONE.defaultBlockState());
        }
        test.assertTrue(KeyboardBlock.place(level, root, compact), "Compact keyboard fixture did not fit");
        var keyboard = (KeyboardBlockEntity) level.getBlockEntity(root);
        keyboard.runtime().charge(640, false);
        var blocked = KeyboardBlock.Part.TOP_TREBLE.at(root, full);
        level.setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
        test.runAfterDelay(25, () -> {
            test.assertFalse(keyboard.state().getValue(KeyboardBlock.FULL_SIZE), "Upgrade overwrote an occupied block");
            level.removeBlock(blocked, false);
        });
        test.runAfterDelay(50, () -> {
            test.assertTrue(keyboard == level.getBlockEntity(root) && keyboard.state().getValue(KeyboardBlock.FULL_SIZE)
                    && keyboard.runtime().energy() == 640
                    && KeyboardBlock.part(level.getBlockState(blocked), KeyboardBlock.Part.TOP_TREBLE, full),
                    "Upgrade did not expand the existing charged keyboard");
            test.succeed();
        });
    }
}
