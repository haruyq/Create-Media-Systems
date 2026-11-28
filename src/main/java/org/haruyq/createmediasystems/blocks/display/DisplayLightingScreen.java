package org.haruyq.createmediasystems.blocks.display;

import org.haruyq.createmediasystems.lighting.LightingFeature;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A display's lighting panel, opened by Ctrl+right-clicking it with the Tuning Wrench, on the panel the range quill
 * uses: how brightly it lights the room in front of it (cinematic) and glows on the wall around it (ambient). Each
 * change is sent as it is made, so the room shows it; the bin puts back what it was.
 */
final class DisplayLightingScreen extends AbstractSimiScreen {
    private final AllGuiTextures background = AllGuiTextures.SCHEMATIC_PROMPT;
    private final BlockPos display;
    private final int initialCinematic;
    private final int initialAmbient;
    /** As set now, which init (run again when the window is resized) starts the inputs at. */
    private int cinematicNow;
    private int ambientNow;
    private ScrollInput cinematic;
    private ScrollInput ambient;

    DisplayLightingScreen(DisplayBlock.Entity display) {
        super(Component.translatable("create_media_systems.display.lighting"));
        this.display = display.getBlockPos();
        initialCinematic = Math.round(display.cinematic() * 100);
        initialAmbient = Math.round(display.ambient() * 100);
        cinematicNow = initialCinematic;
        ambientNow = initialAmbient;
    }

    @Override
    protected void init() {
        setWindowSize(background.getWidth(), background.getHeight());
        super.init();
        int x = guiLeft;
        int y = guiTop + 2;

        Label cinematicLabel = new Label(x + 49, y + 26, Component.empty()).withShadow();
        cinematic = percent(x + 45, y + 21, 65, "cinematic", cinematicLabel, cinematicNow);
        Label ambientLabel = new Label(x + 119, y + 26, Component.empty()).withShadow();
        // Wider: "Ambient 100%" is the longer label.
        ambient = percent(x + 115, y + 21, 69, "ambient", ambientLabel, ambientNow);
        addRenderableWidget(cinematicLabel);
        addRenderableWidget(ambientLabel);

        IconButton revert = new IconButton(x + 7, y + 53, AllIcons.I_TRASH);
        revert.withCallback(() -> {
            send(initialCinematic, initialAmbient);
            onClose();
        });
        revert.setToolTip(Component.translatable("create_media_systems.display.lighting_revert"));
        addRenderableWidget(revert);

        IconButton reset = new IconButton(x + 158, y + 53, AllIcons.I_REFRESH);
        reset.withCallback(() -> {
            cinematic.setState(100);
            cinematic.onChanged();
            ambient.setState(100);
            ambient.onChanged();
        });
        reset.setToolTip(Component.translatable("create_media_systems.display.lighting_reset"));
        addRenderableWidget(reset);

        IconButton confirm = new IconButton(x + 180, y + 53, AllIcons.I_CONFIRM);
        confirm.withCallback(this::onClose);
        confirm.setToolTip(Component.translatable("create_media_systems.range.confirm"));
        addRenderableWidget(confirm);

        // Widgets draw their tooltip right after themselves: the scroll inputs go last, above the labels and buttons.
        addRenderableWidget(cinematic);
        addRenderableWidget(ambient);
    }

    private ScrollInput percent(int x, int y, int width, String key, Label label, int initial) {
        ScrollInput input = new ScrollInput(x, y, width, 18)
                .withRange(0, 101)
                .withShiftStep(10)
                .titled(Component.translatable("create_media_systems.display." + key))
                .format(v -> Component.translatable("create_media_systems.display." + key + "_value", v))
                .writingTo(label);
        input.setState(initial);
        input.onChanged();
        // After the first onChanged, which only fills the label in.
        input.calling(v -> {
            cinematicNow = cinematic.getState();
            ambientNow = ambient.getState();
            send(cinematicNow, ambientNow);
        });
        return input;
    }

    private void send(int cinematic, int ambient) {
        PacketDistributor.sendToServer(new DisplayWrench.LightDisplay(display, cinematic, ambient));
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = guiLeft;
        int y = guiTop;
        background.render(graphics, x, y);
        graphics.drawString(font, title, x + (background.getWidth() - 8 - font.width(title)) / 2, y + 4, 0x505050, false);
        GuiGameElement.of(new ItemStack(DisplayFeature.DISPLAY_ITEM.get())).at(x + 22, y + 24, 0).render(graphics);
        GuiGameElement.of(new ItemStack(LightingFeature.LIGHTING_LINK.get()))
                .scale(3)
                .at(x + background.getWidth() + 6, y + background.getHeight() - 38, -200)
                .render(graphics);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
