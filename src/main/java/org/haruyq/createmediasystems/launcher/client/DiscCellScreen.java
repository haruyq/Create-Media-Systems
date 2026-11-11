package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.DiscCellMenu;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class DiscCellScreen extends AbstractContainerScreen<DiscCellMenu> {
    private EditBox startTime;
    private Button applyStart;
    private int displayedStart;
    private ItemStack displayedMaster = ItemStack.EMPTY;

    public DiscCellScreen(DiscCellMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 216;
        inventoryLabelY = 123;
    }

    @Override
    protected void init() {
        super.init();
        displayedStart = menu.startSeconds();
        displayedMaster = menu.masterDisc().copy();
        startTime = addRenderableWidget(new EditBox(font, leftPos + 13, topPos + 96, 96, 18,
                Component.translatable("cell.create_media_systems.start_at", DiscCellItem.formatStartTime(displayedStart))));
        startTime.setMaxLength(12);
        startTime.setValue(DiscCellItem.formatStartTime(displayedStart));
        startTime.setTooltip(Tooltip.create(Component.translatable("cell.create_media_systems.start_hint")));
        applyStart = addRenderableWidget(Button.builder(Component.translatable("cell.create_media_systems.apply_start"),
                button -> saveStart()).bounds(leftPos + 115, topPos + 96, 48, 18).build());
        startTime.setResponder(text -> refreshStartInput());
        refreshStartInput();
    }

    @Override
    protected void containerTick() {
        int saved = menu.startSeconds();
        ItemStack master = menu.masterDisc();
        boolean changedMaster = !ItemStack.isSameItemSameComponents(displayedMaster, master);
        if (changedMaster || saved != displayedStart) {
            int draft = DiscCellItem.parseStartTime(startTime.getValue());
            if (changedMaster || !startTime.isFocused() || draft == displayedStart || draft == saved) {
                startTime.setValue(DiscCellItem.formatStartTime(saved));
            }
            displayedStart = saved;
            displayedMaster = master.copy();
        }
        refreshStartInput();
    }

    private void refreshStartInput() {
        boolean hasDisc = menu.hasPlayableMaster();
        int seconds = DiscCellItem.parseStartTime(startTime.getValue());
        startTime.setEditable(hasDisc);
        startTime.setTextColor(seconds < 0 ? 0xFFFF867F : 0xFFF1E5D1);
        applyStart.active = hasDisc && seconds >= 0 && seconds != menu.startSeconds();
    }

    private void saveStart() {
        int seconds = DiscCellItem.parseStartTime(startTime.getValue());
        if (seconds >= 0 && applyStart.active) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, seconds);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (startTime.canConsumeInput() && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) saveStart();
            else startTime.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override protected void renderBg(GuiGraphics gui, float partial, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        gui.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, 0xFFAD8952);
        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFF18222B);
        gui.fill(x + 5, y + 5, x + 171, y + 23, 0xFF283845);
        gui.fill(x + 8, y + 29, x + 168, y + 118, 0xFF0D151D);
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
        gui.drawString(font, Component.translatable("cell.create_media_systems.start_at",
                DiscCellItem.formatStartTime(menu.startSeconds())), 13, 84, 0xFFD2DDE3, false);
        gui.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFBECBD2, false);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partial) {
        super.render(gui, mouseX, mouseY, partial); renderTooltip(gui, mouseX, mouseY);
    }
}
