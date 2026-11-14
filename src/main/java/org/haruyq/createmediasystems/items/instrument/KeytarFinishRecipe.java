package org.haruyq.createmediasystems.items.instrument;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Changes only the finish of the instrument placed in the crafting grid. */
public final class KeytarFinishRecipe extends CustomRecipe {
    private final Item material;

    KeytarFinishRecipe(Item material) {
        super(CraftingBookCategory.EQUIPMENT);
        this.material = material;
    }

    @Override public boolean matches(CraftingInput input, Level level) { return !instrument(input).isEmpty(); }

    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        var source = instrument(input);
        if (source.isEmpty()) return ItemStack.EMPTY;
        return paint(source);
    }

    private ItemStack instrument(CraftingInput input) {
        if (input.ingredientCount() != 2) return ItemStack.EMPTY;
        ItemStack instrument = ItemStack.EMPTY;
        boolean pigment = false;
        for (ItemStack item : input.items()) {
            if (item.is(WearableInstruments.KEYTAR.get())) instrument = item;
            if (item.is(material)) pigment = true;
        }
        return pigment ? instrument : ItemStack.EMPTY;
    }

    private ItemStack paint(ItemStack source) {
        var result = source.copyWithCount(1);
        KeytarFinish.of(source).paintedWith(material).applyTo(result);
        return result;
    }

    @Override public boolean isSpecial() { return false; }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public RecipeSerializer<?> getSerializer() { return WearableInstruments.KEYTAR_FINISH_RECIPE.get(); }

    @Override public ItemStack getResultItem(HolderLookup.Provider registries) {
        return paint(new ItemStack(WearableInstruments.KEYTAR.get()));
    }

    @Override public NonNullList<Ingredient> getIngredients() {
        return NonNullList.of(Ingredient.EMPTY, Ingredient.of(WearableInstruments.KEYTAR.get()), Ingredient.of(material));
    }

    static final class Serializer implements RecipeSerializer<KeytarFinishRecipe> {
        private static final MapCodec<KeytarFinishRecipe> CODEC = BuiltInRegistries.ITEM.byNameCodec()
                .validate(item -> KeytarFinish.accepts(item) ? DataResult.success(item) : DataResult.error(() -> "Invalid keytar pigment"))
                .fieldOf("material").xmap(KeytarFinishRecipe::new, recipe -> recipe.material);
        private static final StreamCodec<RegistryFriendlyByteBuf, KeytarFinishRecipe> STREAM =
                ByteBufCodecs.registry(Registries.ITEM).map(KeytarFinishRecipe::new, recipe -> recipe.material);

        @Override public MapCodec<KeytarFinishRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, KeytarFinishRecipe> streamCodec() { return STREAM; }
    }
}
