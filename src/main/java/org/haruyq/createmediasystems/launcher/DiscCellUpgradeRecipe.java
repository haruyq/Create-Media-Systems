package org.haruyq.createmediasystems.launcher;

import java.util.ArrayList;
import java.util.List;


import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** A real upgrade, not an ordinary shapeless recipe that would delete a stored master disc. */
public final class DiscCellUpgradeRecipe extends CustomRecipe {
    public final DiscCellTier target;
    public DiscCellUpgradeRecipe(int level) { super(CraftingBookCategory.EQUIPMENT); target = DiscCellTier.at(level); }

    // A finite 3x3 recipe with declared inputs/output. CustomRecipe's hidden default excludes it from JEI.
    @Override public boolean isSpecial() { return false; }

    @Override public boolean matches(CraftingInput input, Level level) {
        List<String> needed = new ArrayList<>(target.upgradeMaterials());
        int cells = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack item = input.getItem(i);
            if (item.isEmpty()) continue;
            if (item.getItem() instanceof DiscCellItem cell && cell.tier.level == target.level - 1) cells++;
            else if (!needed.remove(BuiltInRegistries.ITEM.getKey(item.getItem()).toString())) return false;
        }
        return cells == 1 && needed.isEmpty();
    }

    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        for (int i = 0; i < input.size(); i++)
            if (input.getItem(i).getItem() instanceof DiscCellItem cell && cell.tier.level == target.level - 1)
                return DiscCellItem.upgrade(input.getItem(i), target);
        return ItemStack.EMPTY;
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 9; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return new ItemStack(LauncherFeature.cell(target)); }
    @Override public RecipeSerializer<?> getSerializer() { return LauncherFeature.CELL_UPGRADE_RECIPE.get(); }
    @Override public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(LauncherFeature.cell(DiscCellTier.at(target.level - 1))));
        for (String id : target.upgradeMaterials())
            ingredients.add(Ingredient.of(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))));
        return ingredients;
    }

    public static final class Serializer implements RecipeSerializer<DiscCellUpgradeRecipe> {
        private static final MapCodec<DiscCellUpgradeRecipe> CODEC = Codec.intRange(2, 6).fieldOf("level")
                .xmap(DiscCellUpgradeRecipe::new, recipe -> recipe.target.level);
        private static final StreamCodec<RegistryFriendlyByteBuf, DiscCellUpgradeRecipe> STREAM = StreamCodec.of(
                (buf, recipe) -> buf.writeVarInt(recipe.target.level), buf -> new DiscCellUpgradeRecipe(buf.readVarInt()));
        @Override public MapCodec<DiscCellUpgradeRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, DiscCellUpgradeRecipe> streamCodec() { return STREAM; }
    }
}
