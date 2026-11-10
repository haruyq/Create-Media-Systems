package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import mezz.jei.library.plugins.vanilla.crafting.CraftingCategoryExtension;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.junit.jupiter.api.Test;

/** The actual JEI implementation must accept our custom, component-preserving crafting recipe. */
class CellJeiCompatibilityTest {
    @Test void jeiAcceptsEveryCellUpgradeAsAVisibleShapelessCraftingRecipe() {
        var category=new CraftingCategoryExtension();
        for(int level=2;level<=6;level++) {
            CraftingRecipe recipe=new DiscCellUpgradeRecipe(level);
            var holder=new RecipeHolder<>(CreateMediaSystems.id("display_check_"+level),recipe);
            assertTrue(category.isHandled(holder),"JEI hides cell tier "+level);
            assertEquals(RecipeType.CRAFTING,recipe.getType());
            assertEquals(0,category.getWidth(holder),"Use JEI's ordinary shapeless layout");
            assertEquals(0,category.getHeight(holder));
        }
    }
}
