package org.haruyq.createmediasystems.client;

import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class ItemRenderers {
    private ItemRenderers() {
    }

    /** Draws {@code items} with one renderer, made the first time the game asks for it. */
    public static void register(RegisterClientExtensionsEvent event,
                                Supplier<? extends BlockEntityWithoutLevelRenderer> renderer, Item... items) {
        var made = Suppliers.memoize(renderer::get);
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return made.get();
            }
        }, items);
    }
}
