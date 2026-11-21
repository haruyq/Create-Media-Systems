package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
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
}
