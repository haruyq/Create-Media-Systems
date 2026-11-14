package org.haruyq.createmediasystems.items.instrument;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.items.power.ChargeableItem;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KeytarFinishGameTests {
    @GameTest(template = "empty")
    public static void allDyesRepaintEitherShellAndPreserveTheInstrument(GameTestHelper test) {
        var original = new ItemStack(WearableInstruments.KEYTAR.get());
        ChargeableItem.setEnergy(original, 5678);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("My keytar"));
        test.assertTrue(KeytarFinish.of(original).equals(KeytarFinish.DEFAULT), "Unpainted keytars use the white and silver finish");

        for (var dye : DyeColor.values()) {
            var white = paint(test, original, DyeItem.byColor(dye), "blade_" + dye.getName());
            test.assertTrue(KeytarFinish.of(white).equals(new KeytarFinish(false, dye)), "White shell with blade " + dye);
            var black = paint(test, white, Items.INK_SAC, "shell_black");
            test.assertTrue(KeytarFinish.of(black).equals(new KeytarFinish(true, dye)), "Black shell with blade " + dye);
            var repainted = paint(test, black, DyeItem.byColor(dye), "blade_" + dye.getName());
            test.assertTrue(KeytarFinish.of(repainted).equals(KeytarFinish.of(black)), "Dyes preserve a black shell");
            var restored = paint(test, repainted, Items.BONE_MEAL, "shell_white");
            test.assertTrue(KeytarFinish.of(restored).equals(KeytarFinish.of(white)), "Bone meal restores only the body color");

            var registries = test.getLevel().registryAccess();
            var loaded = ItemStack.parse(registries, black.save(registries)).orElseThrow();
            test.assertTrue(ItemStack.matches(black, loaded), "The finish persists with the saved item");
        }
        test.assertTrue(KeytarFinish.of(original).equals(KeytarFinish.DEFAULT), "Crafting only changes the copy");
        test.succeed();
    }

    private static ItemStack paint(GameTestHelper test, ItemStack keytar, Item pigment, String name) {
        var input = CraftingInput.of(2, 1, List.of(keytar, new ItemStack(pigment)));
        var level = test.getLevel();
        var result = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        test.assertTrue(result.id().equals(CreateMediaSystems.id("keytar_finish/" + name)), "The expected recipe handles the grid");
        var recipe = result.value();
        test.assertTrue(!recipe.isSpecial() && recipe.getIngredients().size() == 2, "Recipe viewers can show the ingredients");
        var painted = recipe.assemble(input, level.registryAccess());
        test.assertTrue(ChargeableItem.energy(painted) == 5678 && painted.getHoverName().equals(keytar.getHoverName()),
                "Painting preserves the charge and custom name");
        return painted;
    }
}
