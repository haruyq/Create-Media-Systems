package org.haruyq.createmediasystems.items.walkman.nws310;

import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;

/** The Sunny Loudman, a pocket FM receiver in three colours (see LoudmanItem). */
public final class LoudmanFeature implements Feature {
    public static final Supplier<DataComponentType<LoudmanItem.Tuning>> TUNING = COMPONENTS.registerComponentType(
            "loudman_tuning", b -> b.persistent(LoudmanItem.Tuning.CODEC).networkSynchronized(LoudmanItem.Tuning.STREAM_CODEC));
    public static final DeferredItem<LoudmanItem> BLACK = item("loudman_black", 0x1B1C20);
    public static final DeferredItem<LoudmanItem> BLUE = item("loudman_blue", 0x18B7C4);
    public static final DeferredItem<LoudmanItem> PINK = item("loudman_pink", 0xEFA39A);

    private static DeferredItem<LoudmanItem> item(String id, int color) {
        return ITEMS.register(id, () -> new LoudmanItem(new Item.Properties().stacksTo(1), color));
    }

    public static List<DeferredItem<LoudmanItem>> all() {
        return List.of(BLACK, BLUE, PINK);
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        LoudmanItem.Set.register(registrar);
    }
}
