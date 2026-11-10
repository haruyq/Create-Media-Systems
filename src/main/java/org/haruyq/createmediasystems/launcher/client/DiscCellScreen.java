package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.DiscCellMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A single protected master slot; cells are charged on a physical injector, not with a UI cheat button. */
public final class DiscCellScreen extends AbstractContainerScreen<DiscCellMenu> {
    public DiscCellScreen(DiscCellMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 176; imageHeight = 184; inventoryLabelY = 91;
    }
    @Override protected void renderBg(GuiGraphics gui, float partial, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        gui.fill(x - 2, y - 2, x + 178, y + 186, 0xFFAD8952);
        gui.fill(x, y, x + 176, y + 184, 0xFF18222B);
        gui.fill(x + 5, y + 5, x + 171, y + 23, 0xFF283845);
        gui.fill(x + 8, y + 29, x + 168, y + 85, 0xFF0D151D);
        gui.fill(x + 78, y + 43, x + 98, y + 63, 0xFFBCA16D);
        gui.fill(x + 79, y + 44, x + 97, y + 62, 0xFF071017);
        for (var slot : menu.slots) if (slot.index > 0) {
            gui.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xFF52636D);
            gui.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF24313B);
        }
        if (menu.cell().getItem() instanceof DiscCellItem item) {
            int charge = Math.round(150f * DiscCellItem.energy(menu.cell()) / item.tier.capacity());
            gui.fill(x + 13, y + 72, x + 163, y + 78, 0xFF36434B);
            gui.fill(x + 13, y + 72, x + 13 + charge, y + 78, 0xFF000000 | item.tier.color);
        }
    }
    @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(font, title, 9, 10, 0xFFF1E5D1, false);
        gui.drawString(font, Component.translatable("cell.create_media_systems.original_slot"), 12, 34, 0xFFD2DDE3, false);
        if (menu.cell().getItem() instanceof DiscCellItem item) {
            gui.drawString(font, "Lv." + item.tier.level, 110, 43, item.tier.color, false);
            gui.drawString(font, DiscCellItem.shots(menu.cell()) + "/" + item.tier.shots, 110, 55, item.tier.color, false);
        }
        gui.drawString(font, playerInventoryTitle, 8, 91, 0xFFBECBD2, false);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partial) {
        super.render(gui, mouseX, mouseY, partial); renderTooltip(gui, mouseX, mouseY);
    }
}
