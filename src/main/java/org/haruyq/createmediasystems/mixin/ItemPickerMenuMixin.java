package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.client.TabSections;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** Lays the mod's creative tab out in sections (see TabSections) before the screen shows any of its list. */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
abstract class ItemPickerMenuMixin {
    @Shadow @Final public NonNullList<ItemStack> items;

    @Shadow protected abstract int getRowIndexForScroll(float scrollOffs);

    @Inject(method = "scrollTo", at = @At("HEAD"))
    private void create_media_systems$sections(float pos, CallbackInfo ci) {
        TabSections.scrolling(items);
        TabSections.scrolledTo(getRowIndexForScroll(pos));
    }
}
