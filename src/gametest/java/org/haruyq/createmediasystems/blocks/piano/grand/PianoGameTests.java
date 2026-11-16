package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.blocks.piano.PianoTestPlayers;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class PianoGameTests {
    @GameTest(template = "empty")
    public static void closingTheFallboardKillsOnlyCurrentPlayers(GameTestHelper test) {
        var level = test.getLevel();
        var origin = Vec3.atCenterOf(test.absolutePos(new BlockPos(2, 2, 2)));
        var soloistDeath = new AtomicReference<Component>();
        var partnerDeath = new AtomicReference<Component>();
        var idleDeath = new AtomicReference<Component>();
        var closerDeath = new AtomicReference<Component>();
        var active = PianoTestPlayers.near(test, origin, "Soloist", soloistDeath::set);
        var alsoActive = PianoTestPlayers.near(test, origin, "DuetPartner", partnerDeath::set);
        var idle = PianoTestPlayers.near(test, origin, "IdlePianist", idleDeath::set);
        var bystander = PianoTestPlayers.near(test, origin, "LidCloser", closerDeath::set);
        var open = new AtomicBoolean(true);
        var session = new PianoSession(level, PianoPackets.Target.placed(BlockPos.containing(origin)),
                () -> new PianoPackets.Frame(origin, false), open::get, action -> {
                    if (action == Action.KEYBOARD_LID) open.set(!open.get());
                });
        active.getInventory().armor.set(2, new ItemStack(Items.NETHERITE_CHESTPLATE));
        active.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        active.setLastHurtByPlayer(idle);
        var first = session.interact(active, Action.PLAY);
        var second = session.interact(alsoActive, Action.PLAY);
        var idleSession = session.interact(idle, Action.PLAY);
        test.assertTrue(first != 0 && second != 0 && first != second && idleSession != 0, "Three performers can open independent sessions");
        session.play(active, first, List.of(PianoEvent.press(60, 100)));
        session.play(alsoActive, second, List.of(PianoEvent.press(60, 70), PianoEvent.release(60, 30)));
        session.interact(bystander, Action.ROOF);
        test.assertTrue(active.isAlive() && alsoActive.isAlive(), "The roof is harmless");
        session.interact(bystander, Action.KEYBOARD_LID);
        test.assertTrue(!open.get() && !active.isAlive() && !alsoActive.isAlive(),
                "The fallboard caught both performers despite armor and a totem");
        test.assertTrue(idle.isAlive() && bystander.isAlive(), "Idle players and bystanders survive");
        test.assertTrue(session.interact(idle, Action.PLAY) == 0, "A closed keyboard cannot be played");
        assertDeathMessage(test, soloistDeath.get(), "death.attack.cms_piano_fallboard",
                active.getDisplayName(), bystander.getDisplayName());
        assertDeathMessage(test, partnerDeath.get(), "death.attack.cms_piano_fallboard",
                alsoActive.getDisplayName(), bystander.getDisplayName());

        session.interact(bystander, Action.KEYBOARD_LID);
        var closerSession = session.interact(bystander, Action.PLAY);
        var otherSession = session.interact(idle, Action.PLAY);
        session.play(bystander, closerSession, List.of(PianoEvent.press(60, 100)));
        session.play(idle, otherSession, List.of(PianoEvent.press(64, 100)));
        session.command(bystander, closerSession, Action.KEYBOARD_LID);
        assertDeathMessage(test, idleDeath.get(), "death.attack.cms_piano_fallboard",
                idle.getDisplayName(), bystander.getDisplayName());
        assertDeathMessage(test, closerDeath.get(), "death.attack.cms_piano_fallboard.self", bystander.getDisplayName());
        test.succeed();
    }

    private static void assertDeathMessage(GameTestHelper test, Component message, String key, Component... names) {
        test.assertTrue(message != null && message.getContents() instanceof TranslatableContents text
                && text.getKey().equals(key) && Arrays.equals(text.getArgs(), names),
                "Expected " + key + " with " + Arrays.toString(names) + ", got " + message);
    }

    @GameTest(template = "empty")
    public static void bearingCarriesTheEntirePianoWithOneGluedPart(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos root = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos deck = root.below(), bearingPos = deck.below();
        var state = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.OPEN, true)
                .setValue(GrandPianoBlock.KEYBOARD_OPEN, false)
                .setValue(GrandPianoBlock.FULL_SIZE, true);
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        helper.assertTrue(GrandPianoBlock.placeParts(level, root, state), "Piano placement failed");
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).deflate(.2).minmax(new AABB(root).deflate(.2))));

        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        helper.assertTrue(carrier != null && carrier.getContraption().getBlocks().size() == GrandPianoBlock.parts(state).size() + 1,
                "One glued piano part must bring the whole instrument onto the bearing");
        var local = root.subtract(deck);
        var before = MountedPiano.frame(carrier, local, state);
        for (var part : GrandPianoBlock.parts(state)) {
            var pos = part.at(root, state);
            helper.assertTrue(level.getBlockState(pos).isAir(), "A piano part was left behind: " + part);
            var captured = carrier.getContraption().getBlocks().get(pos.subtract(deck));
            helper.assertTrue(captured != null && captured.state().getValue(GrandPianoBlock.PART) == part,
                    "Missing carried part " + part);
        }
        carrier.setAngle(90);
        var after = MountedPiano.frame(carrier, local, state);
        var landedRoot = BlockPos.containing(carrier.toGlobalVector(local.getCenter(), 1));
        helper.assertTrue(after.lidOpen() && after.origin().distanceTo(before.origin()) > .5,
                "The open instrument's soundboard did not rotate with the bearing");
        bearing.disassemble();
        var landed = level.getBlockState(landedRoot);
        helper.assertTrue(landed.is(PianoFeature.PIANO.get()) && landed.getValue(GrandPianoBlock.OPEN)
                && !landed.getValue(GrandPianoBlock.KEYBOARD_OPEN), "Disassembly did not retain both lids");
        for (var part : GrandPianoBlock.parts(landed)) {
            var pos = part.at(landedRoot, landed);
            var restored = level.getBlockState(pos);
            helper.assertTrue(restored.is(PianoFeature.PIANO.get()) && restored.getValue(GrandPianoBlock.PART) == part
                    && GrandPianoBlock.root(pos, restored).equals(landedRoot), "Restored part has the wrong position " + part);
        }
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(5)).isEmpty(),
                "Moving the piano dropped a duplicate instrument");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sidewaysDisassemblyRotatesAllPartsAndTheSoundboard(GameTestHelper helper) {
        var upright = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.FULL_SIZE, true);
        var offset = new BlockPos(2, 4, -3);
        for (var axis : Direction.Axis.values()) {
            var transform = new StructureTransform(offset, axis, Rotation.CLOCKWISE_90, Mirror.NONE);
            var rotated = transform.apply(upright);
            var root = transform.apply(BlockPos.ZERO);
            for (var part : GrandPianoBlock.parts(upright)) {
                var original = part.at(BlockPos.ZERO, upright);
                var landed = transform.apply(original);
                var state = transform.apply(upright.setValue(GrandPianoBlock.PART, part));
                helper.assertTrue(part.at(root, rotated).equals(landed) && GrandPianoBlock.root(landed, state).equals(root),
                        "The " + axis + " transform separated " + part + " from the keyboard");
            }
            var before = GrandPianoBlock.soundboard(BlockPos.ZERO, upright);
            helper.assertTrue(GrandPianoBlock.soundboard(root, rotated).distanceTo(transform.apply(before)) < .00001,
                    "The " + axis + " transform misplaced the soundboard");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void grandPianoPlacesAndDropsAsOneInstrument(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(7, 2, 5));
        var item = PianoFeature.PIANO_ITEM.get();
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(90);
        player.setPos(root.getX() + 3.5, root.getY(), root.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        Direction facing = player.getDirection().getOpposite();
        var placement = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.FACING, facing)
                .setValue(GrandPianoBlock.FULL_SIZE, true);
        for (var part : GrandPianoBlock.parts(placement)) {
            if (part.y == 0) level.setBlockAndUpdate(part.at(root, placement).below(), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        var hit = new BlockHitResult(Vec3.atCenterOf(root.below()).add(0, .5, 0), Direction.UP, root.below(), false);
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        var tail = GrandPianoBlock.Part.TOP_C5.at(root, placement);
        level.setBlockAndUpdate(tail, Blocks.STONE.defaultBlockState());
        helper.assertFalse(item.place(context).consumesAction(), "The occupied tail should prevent placement");
        helper.assertTrue(level.getBlockState(root).isAir() && player.getMainHandItem().is(item),
                "An obstructed placement changed the root or the item");
        level.removeBlock(tail, false);
        helper.assertTrue(item.place(context).consumesAction() && player.getMainHandItem().isEmpty(),
                "The item did not place the full piano");
        double left = GrandPianoBlock.modelToLocal(new Vec3(GrandPianoBlock.KEY_LEFT, 0, 0), true).x;
        double right = GrandPianoBlock.modelToLocal(new Vec3(GrandPianoBlock.KEY_RIGHT, 0, 0), true).x;
        helper.assertTrue(Math.abs(right - left - (Layout.KEYS_RIGHT - Layout.KEYS_LEFT) / 16) < .00001,
                "The installed keybed must match Koland");
        for (var part : GrandPianoBlock.parts(placement)) {
            var pos = part.at(root, placement);
            var state = level.getBlockState(pos);
            helper.assertTrue(state.is(PianoFeature.PIANO.get()) && GrandPianoBlock.root(pos, state).equals(root),
                    "Missing or incorrectly rotated piano part " + part);
            helper.assertTrue((level.getBlockEntity(pos) instanceof PianoBlockEntity) == (part == GrandPianoBlock.Part.KEYBOARD),
                    "Unexpected block entity on " + part);
        }
        var piano = (PianoBlockEntity) level.getBlockEntity(root);
        piano.toggleLid();
        helper.assertTrue(level.getBlockState(root).getValue(GrandPianoBlock.OPEN) && level.getBlockEntity(root) == piano,
                "Toggling the lid did not keep the piano");
        var open = level.getBlockState(root);
        player.setShiftKeyDown(true);
        var close = GrandPianoBlock.interaction(player, GrandPianoBlock.Part.KEYBOARD, open);
        var closed = GrandPianoBlock.moveLid(open, close);
        helper.assertTrue(!closed.getValue(GrandPianoBlock.KEYBOARD_OPEN) && closed.getValue(GrandPianoBlock.OPEN),
                "Closing the keyboard also moved the roof");
        player.setShiftKeyDown(false);
        var reopen = GrandPianoBlock.interaction(player, GrandPianoBlock.Part.KEYBOARD, closed);
        helper.assertTrue(GrandPianoBlock.moveLid(closed, reopen).equals(open), "Right-click did not reopen the keyboard");
        level.destroyBlock(tail, true, player);
        for (var part : GrandPianoBlock.parts(placement)) {
            helper.assertTrue(level.getBlockState(part.at(root, placement)).isAir(), "Orphaned piano part " + part);
        }
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(4),
                entity -> entity.getItem().is(item));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "Expected exactly one piano drop");
        helper.assertTrue(level.getRecipeManager().byKey(CreateMediaSystems.id("grand_piano"))
                .filter(recipe -> recipe.value().getResultItem(level.registryAccess()).is(item)).isPresent(),
                "The shaped recipe is missing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void compactGrandExpandsWithoutResettingItsLid(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(4, 2, 2));
        var compact = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.OPEN, true);
        var full = compact.setValue(GrandPianoBlock.FULL_SIZE, true);
        for (var part : GrandPianoBlock.parts(full)) {
            if (part.y == 0) level.setBlockAndUpdate(part.at(root, full).below(), Blocks.STONE.defaultBlockState());
        }
        helper.assertTrue(GrandPianoBlock.placeParts(level, root, compact), "Compact piano fixture did not fit");
        var piano = level.getBlockEntity(root);
        helper.runAfterDelay(25, () -> {
            var state = level.getBlockState(root);
            helper.assertTrue(piano != null && piano == level.getBlockEntity(root) && state.getValue(GrandPianoBlock.FULL_SIZE)
                    && state.getValue(GrandPianoBlock.OPEN), "Upgrade replaced the grand or reset its lid");
            for (var part : GrandPianoBlock.parts(full)) {
                var at = part.at(root, full);
                helper.assertTrue(level.getBlockState(at).is(PianoFeature.PIANO.get()), "Upgrade missed " + part);
            }
            helper.assertTrue(level.getBlockState(GrandPianoBlock.Part.TAIL.at(root, compact)).isAir(), "A compact tail was left behind");
            helper.succeed();
        });
    }
}
