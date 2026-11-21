package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.AllItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

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
}
