package org.haruyq.createmediasystems.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.mixin.CreativeModeInventoryScreenAccessor;
import org.haruyq.createmediasystems.mod.CreateMediaSystemsMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Splits the mod's creative tab into titled sections: each starts on a row of its own, under an empty row that its
 * banner is drawn over.
 */
public final class TabSections {
    private static final int COLUMNS = 9;
    private static final int ROWS = 5;
    private static final int SLOT = 18;

    /** The section whose banner is on each row of the tab's list, as it was last laid out. */
    private static Map<Integer, String> banners = Map.of();
    private static int topRow;

    private TabSections() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(TabSections::render);
    }

    /**
     * Called before the creative screen shows rows of its list (see ItemPickerMenuMixin). The screen refills the list
     * from the tab whenever it selects or rebuilds it, so a list that starts with an item is one to lay out.
     */
    public static void scrolling(List<ItemStack> items) {
        if (shown() && !items.isEmpty() && !items.get(0).isEmpty()) {
            Map<Item, String> sections = new HashMap<>();
            CreateMediaSystemsMod.sections().forEach((name, section) -> section.forEach(item -> sections.put(item.get(), name)));
            banners = layOut(items, stack -> sections.get(stack.getItem()), ItemStack.EMPTY);
        }
    }

    public static void scrolledTo(int row) {
        topRow = row;
    }

    /**
     * Puts a row of `empty` above each run of items of one section and fills the row a run ends in, and answers the
     * section of each row so added. An item of no section stays in the section before it.
     */
    static <T> Map<Integer, String> layOut(List<T> items, Function<T, String> sectionOf, T empty) {
        List<T> all = List.copyOf(items);
        Map<Integer, String> rows = new HashMap<>();
        String section = null;
        items.clear();
        for (T item : all) {
            String next = sectionOf.apply(item);
            if (next != null && !next.equals(section)) {
                section = next;
                items.addAll(Collections.nCopies(Math.floorMod(-items.size(), COLUMNS), empty));
                rows.put(items.size() / COLUMNS, section);
                items.addAll(Collections.nCopies(COLUMNS, empty));
            }
            items.add(item);
        }
        return rows;
    }

    private static boolean shown() {
        return CreativeModeInventoryScreenAccessor.cms$selectedTab() == CreateMediaSystemsMod.TAB.get();
    }

    private static void render(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof CreativeModeInventoryScreen) || !shown()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        banners.forEach((row, section) -> {
            if (row >= topRow && row < topRow + ROWS) {
                // Inside the border of the row's slots, so that the banner is sunk into the panel as they are.
                int y = 18 + (row - topRow) * SLOT;
                graphics.blitSprite(CreateMediaSystems.id("tab_section/" + section), 9, y, COLUMNS * SLOT - 2, SLOT - 2);
                graphics.drawString(Minecraft.getInstance().font,
                        Component.translatable("itemGroup.create_media_systems." + section), 13, y + 4, 0xFFFFFF);
            }
        });
    }
}
