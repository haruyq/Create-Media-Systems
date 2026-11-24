package org.haruyq.createmediasystems.client;

import java.util.function.Supplier;
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
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer made;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (made == null) {
                    made = renderer.get();
                }
                return made;
            }
        }, items);
    }
}
