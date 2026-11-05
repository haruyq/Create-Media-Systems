package org.haruyq.createmediasystems.client;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** One text box and Done: a disc's URL, a train detector's or receiver's channel. */
public final class TextPromptScreen extends Screen {
    private final String initial;
    private final int maxLength;
    private final Consumer<String> onDone;
    private EditBox box;

    /** Asks for one line of text under the translated {@code titleKey}, starting from {@code initial}. */
    public static void open(String titleKey, String initial, int maxLength, Consumer<String> onDone) {
        Minecraft.getInstance().setScreen(new TextPromptScreen(Component.translatable(titleKey), initial, maxLength, onDone));
    }

    private TextPromptScreen(Component title, String initial, int maxLength, Consumer<String> onDone) {
        super(title);
        this.initial = initial;
        this.maxLength = maxLength;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        box = new EditBox(font, width / 2 - 150, height / 2 - 10, 300, 20, title);
        box.setMaxLength(maxLength);
        box.setValue(initial);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            onDone.accept(box.getValue());
            onClose();
        }).bounds(width / 2 - 50, height / 2 + 20, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 30, 0xFFFFFF);
    }
}
