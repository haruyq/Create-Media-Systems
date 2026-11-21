package org.haruyq.createmediasystems.items.instrument;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InstrumentGameTests {
    @GameTest(template = "empty")
    public static void allSixInstrumentsCraftAndRequireAFreeSupportingHand(GameTestHelper test) {
        var level = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        var dyes = List.of(Items.RED_DYE, Items.WHITE_DYE, Items.BROWN_DYE, Items.BLACK_DYE);
        for (int i = 0; i < InstrumentFeature.ITEMS.size(); i++) {
            var item = InstrumentFeature.ITEMS.get(i);
            Item air = Items.AIR, iron = AllItems.IRON_SHEET.get(), brass = AllItems.BRASS_SHEET.get();
            List<Item> grid = switch (item.get().kind) {
                case STRATOCASTER -> List.of(air, air, iron, air, Items.STRING, Items.OAK_PLANKS, dyes.get(i), Items.NOTE_BLOCK, Items.LEATHER);
                case FLUTE -> List.of(air, air, Items.IRON_NUGGET, air, iron, Items.NOTE_BLOCK, Items.NOTE_BLOCK, iron, air);
                case TRUMPET -> List.of(air, air, brass, AllItems.BRASS_NUGGET.get(), AllItems.BRASS_INGOT.get(), Items.NOTE_BLOCK, brass, brass, air);
                default -> throw new AssertionError(item.getId());
            };
            var input = CraftingInput.of(3, 3, grid.stream().map(Item::getDefaultInstance).toList());
            var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
            var stack = recipe.value().assemble(input, level.registryAccess());
            test.assertTrue(recipe.id().equals(item.getId()) && stack.is(item.get()) && !recipe.value().isSpecial(),
                    "Craftable recipe viewer entry for " + item.getId());
            for (var hand : InteractionHand.values()) {
                var support = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                player.setItemInHand(hand, stack);
                player.setItemInHand(support, new ItemStack(Items.TORCH));
                test.assertTrue(!item.get().use(level, player, hand).getResult().consumesAction(), "Needs a supporting hand");
                player.setItemInHand(support, ItemStack.EMPTY);
                test.assertTrue(item.get().use(level, player, hand).getResult().consumesAction(), "Right click starts playing");
                test.assertTrue(player.getItemInHand(hand) == stack && stack.getCount() == 1, "The instrument stays in hand");
            }
        }
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void guitarPedalsConnectInOrderAndBypassIndependently(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        var guitar = new ItemStack(InstrumentFeature.CORAL_RED.get());
        var otherGuitar = new ItemStack(InstrumentFeature.BLACK.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, guitar);
        var wah = helper.absolutePos(new BlockPos(0, 2, 0));
        var chorus = helper.absolutePos(new BlockPos(1, 2, 0));
        var start = Vec3.atCenterOf(wah).add(0, 0, 1);
        player.setPos(start);
        level.setBlockAndUpdate(wah, InstrumentFeature.WAH.get().defaultBlockState());
        level.setBlockAndUpdate(chorus, InstrumentFeature.CHORUS.get().defaultBlockState());
        var wahHit = new BlockHitResult(Vec3.atCenterOf(wah), Direction.UP, wah, false);
        var chorusHit = new BlockHitResult(Vec3.atCenterOf(chorus), Direction.UP, chorus, false);
        for (var hit : List.of(wahHit, chorusHit)) {
            var context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(guitar.useOn(context).consumesAction(), "Right click didn't connect a pedal");
        }
        var patch = GuitarPatch.get(guitar);
        helper.assertTrue(patch.links().stream().map(GuitarPatch.Link::effect).toList().equals(List.of(AutoEffect.WAH, AutoEffect.CHORUS)),
                "The chain did not keep connection order");
        helper.assertTrue(GuitarPatch.get(otherGuitar).links().isEmpty(), "The pedals leaked to a second guitar");
        var restored = ItemStack.parse(level.registryAccess(), guitar.save(level.registryAccess())).orElseThrow();
        helper.assertTrue(GuitarPatch.get(restored).equals(patch), "The chain wasn't saved on the guitar");
        helper.assertTrue(amounts(patch, player).equals(List.of(2, 2)), "The pedals aren't active at their default setting");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.getBlockState(wah).useWithoutItem(level, player, wahHit);
        helper.assertTrue(amounts(patch, player).equals(List.of(0, 2)), "Bypass affected the wrong pedal");
        player.setShiftKeyDown(true);
        level.getBlockState(chorus).useWithoutItem(level, player, chorusHit);
        player.setShiftKeyDown(false);
        helper.assertTrue(amounts(patch, player).equals(List.of(0, 3)), "The knob change didn't reach the chain");

        player.setPos(start.add(16, 0, 0));
        helper.assertTrue(amounts(patch, player).equals(List.of(0, 0)), "An out-of-range pedal stayed active");
        player.setPos(start);
        level.setBlockAndUpdate(wah, InstrumentFeature.COMPRESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(chorus, Blocks.AIR.defaultBlockState());
        helper.assertTrue(amounts(patch, player).equals(List.of(0, 0)), "A broken or replaced pedal stayed active");
        player.setItemInHand(InteractionHand.MAIN_HAND, guitar);
        player.setShiftKeyDown(true);
        guitar.getItem().use(level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(GuitarPatch.get(guitar).links().isEmpty(), "Clearing did not remove unavailable links");
        helper.succeed();
    }

    private static List<Integer> amounts(GuitarPatch patch, net.minecraft.world.entity.player.Player player) {
        return patch.settings(player).stream().map(GuitarPatch.Setting::amount).toList();
    }

    @GameTest(template = "empty")
    public static void driveAndDistortionFollowTheSavedGuitarToItsNextSession(GameTestHelper helper) {
        var level = helper.getLevel();
        var packets = new ArrayList<CustomPacketPayload>();
        var player = VideoPlayerGameTests.listener(level, helper.absolutePos(new BlockPos(1, 2, 1)), packets);
        try {
            var guitar = new ItemStack(InstrumentFeature.BLACK.get());
            int session = playGuitar(player, guitar, packets);
            WearableInstruments.input(player, session, InstrumentState.control(InstrumentState.AMP, 127));
            WearableInstruments.input(player, session, InstrumentState.control(InstrumentState.DRIVE, 101));
            WearableInstruments.input(player, session, WearablePackets.CLOSE);
            var saved = ItemStack.parse(level.registryAccess(), guitar.save(level.registryAccess())).orElseThrow();
            helper.assertTrue(new GuitarAmp(true, 101).equals(saved.get(InstrumentFeature.GUITAR_AMP.get())), "Lost saved amp");

            session = playGuitar(player, saved, packets);
            WearableInstruments.input(player, session, InstrumentState.control(InstrumentState.DRIVE, 33));
            helper.assertTrue(new GuitarAmp(true, 33).equals(saved.get(InstrumentFeature.GUITAR_AMP.get())), "Lost distortion on reopening");
            var other = new ItemStack(InstrumentFeature.BLACK.get());
            session = playGuitar(player, other, packets);
            WearableInstruments.input(player, session, InstrumentState.control(InstrumentState.DRIVE, 33));
            helper.assertTrue(new GuitarAmp(false, 33).equals(other.get(InstrumentFeature.GUITAR_AMP.get())), "Mode leaked to a second guitar");
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void metalZoneKnobsSaveAndControlThePatchedGuitar(GameTestHelper helper) {
        var level = helper.getLevel();
        var grid = List.of(Items.COMPARATOR, Items.COMPARATOR, Items.COMPARATOR, Items.LEVER, Items.REDSTONE,
                Items.LEVER, Items.AIR, AllBlocks.COPPER_CASING.asItem(), Items.AIR);
        var input = CraftingInput.of(3, 3, grid.stream().map(Item::getDefaultInstance).toList());
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        helper.assertTrue(recipe.value().assemble(input, level.registryAccess()).is(InstrumentFeature.METAL_ZONE.asItem()),
                "The Metal Zone recipe should craft a placeable pedal");
        var pos = helper.absolutePos(new BlockPos(0, 2, 0));
        var switchHit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        var near = Vec3.atCenterOf(pos).add(0, 0, 1);
        player.setPos(near);
        level.setBlockAndUpdate(pos, InstrumentFeature.METAL_ZONE.get().defaultBlockState());
        var pedal = (MetalZoneBlock.Entity) level.getBlockEntity(pos);
        var expected = MetalZoneSettings.DEFAULT;
        for (var knob : MetalZoneBlock.Knob.values()) {
            int value = 12 + knob.ordinal() * 13;
            new MetalZoneBlock.SetKnob(pos, knob.ordinal(), value).apply(player);
            expected = expected.with(knob, value);
        }
        helper.assertTrue(pedal.settings().equals(expected), "The six controls did not change independently");
        new MetalZoneBlock.SetKnob(pos, 77, 40).apply(player);
        new MetalZoneBlock.SetKnob(pos, MetalZoneBlock.Knob.LEVEL.ordinal(), -1).apply(player);
        player.setPos(near.add(16, 0, 0));
        new MetalZoneBlock.SetKnob(pos, MetalZoneBlock.Knob.LEVEL.ordinal(), 80).apply(player);
        helper.assertTrue(pedal.settings().equals(expected), "An invalid or out-of-range knob turn was accepted");
        player.setPos(near);

        var restored = new MetalZoneBlock.Entity(pos, level.getBlockState(pos));
        restored.loadWithComponents(pedal.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        helper.assertTrue(restored.settings().equals(expected), "The knob positions were not saved");

        var guitar = new ItemStack(InstrumentFeature.POLAR_WHITE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, guitar);
        helper.assertTrue(guitar.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, switchHit)).consumesAction(),
                "Connecting the Metal Zone failed");
        var patch = GuitarPatch.get(guitar);
        var setting = patch.settings(player).getFirst();
        helper.assertTrue(setting.link().effect() == AutoEffect.METAL_ZONE && setting.amount() == 1
                && setting.metalZone().equals(expected), "The guitar did not receive the pedal's active EQ");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.getBlockState(pos).useWithoutItem(level, player, switchHit);
        setting = patch.settings(player).getFirst();
        helper.assertTrue(setting.amount() == 0 && setting.metalZone().equals(expected),
                "The footswitch bypassed the wrong device or reset its settings");
        helper.succeed();
    }

    private static int playGuitar(ServerPlayer player, ItemStack guitar, List<CustomPacketPayload> packets) {
        packets.clear();
        player.setItemInHand(InteractionHand.MAIN_HAND, guitar);
        guitar.use(player.level(), player, InteractionHand.MAIN_HAND);
        return packets.stream().filter(WearablePackets.Open.class::isInstance).map(WearablePackets.Open.class::cast)
                .findFirst().orElseThrow().session();
    }
}
