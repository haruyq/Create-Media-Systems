package org.haruyq.createmediasystems.audio;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.broadcast.BroadcastTowerBlock;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;

import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

/** The range quill's save panel, the same one Create's schematic and quill uses to name a schematic. */
final class RangeQuillScreen extends AbstractSimiScreen {
    /** Called with (volume 0..1, reset pressed, chosen option or 0). */
    interface Save {
        void apply(float volume, boolean reset, int option);
    }

    private final AllGuiTextures background = AllGuiTextures.SCHEMATIC_PROMPT;
    private final ItemStack icon;
    private final int initialVolume;
    @Nullable
    private final List<Component> options;
    private final int initialOption;
    private final Supplier<Component> status;
    private final Component resetTooltip;
    private final Save save;
    private final Runnable discard;
    private ScrollInput volume;
    @Nullable
    private SelectionScrollInput option;

    private RangeQuillScreen(Component title, ItemStack icon, float initialVolume, @Nullable List<Component> options,
                             int initialOption, Supplier<Component> status, Component resetTooltip, Save save,
                             Runnable discard) {
        super(title);
        this.icon = icon;
        this.initialVolume = Math.round(initialVolume * 100);
        this.options = options;
        this.initialOption = initialOption;
        this.status = status;
        this.resetTooltip = resetTooltip;
        this.save = save;
        this.discard = discard;
    }

    static RangeQuillScreen forJukebox() {
        EmitterRange current = RangeQuillHandler.current();
        return new RangeQuillScreen(Component.translatable("create_media_systems.range.title"), new ItemStack(Items.JUKEBOX),
                current == null ? 1 : current.volume(), null, 0, RangeQuillScreen::rangeStatus,
                Component.translatable("create_media_systems.range.reset", (int) EmitterRange.DEFAULT_RANGE),
                (volume, reset, option) -> RangeQuillHandler.save(volume, reset), RangeQuillHandler::discard);
    }

    /** {@code picked} is the quill's picked source; saving links the speaker to it. */
    static RangeQuillScreen forSpeaker(RangeQuill.SpeakerInfo info, @Nullable BlockPos picked) {
        Optional<BlockPos> connectTo = Optional.ofNullable(picked).filter(p -> !p.equals(info.source().orElse(null)));
        Supplier<Component> status = () -> connectTo.map(p -> Component.translatable("create_media_systems.speaker.status_connect", xyz(p)))
                .or(() -> info.source().map(p -> Component.translatable("create_media_systems.speaker.status_connected", xyz(p))))
                .orElseGet(() -> Component.translatable("create_media_systems.speaker.status_unlinked"));
        List<Component> channels = Arrays.stream(Streams.Channel.values()).map(Streams.Channel::label).toList();
        var level = Minecraft.getInstance().level;
        var block = level == null ? AudioFeature.SPEAKER.get() : level.getBlockState(info.speaker()).getBlock();
        var icon = block instanceof BroadcastTowerBlock
                ? new ItemStack(block) : new ItemStack(AudioFeature.SPEAKER_ITEM.get());
        return new RangeQuillScreen(Component.translatable("create_media_systems.speaker.title"), icon,
                info.volume(), channels, info.channel().ordinal(), status, Component.translatable("create_media_systems.speaker.disconnect"),
                (volume, reset, channel) -> PacketDistributor.sendToServer(new RangeQuill.SetSpeaker(
                        info.speaker(), reset ? Optional.empty() : connectTo, reset, volume, Streams.Channel.byId(channel))),
                () -> { });
    }

    @Override
    protected void init() {
        setWindowSize(background.getWidth(), background.getHeight());
        super.init();
        int x = guiLeft;
        int y = guiTop + 2;
        int volumeWidth = options == null ? 139 : 72;

        Label label = new Label(x + 49, y + 26, Component.empty()).withShadow();
        volume = new ScrollInput(x + 45, y + 21, volumeWidth, 18)
                .withRange(0, 101)
                .titled(Component.translatable("create_media_systems.range.volume"))
                .format(v -> Component.translatable("create_media_systems.range.volume_value", v))
                .writingTo(label);
        volume.setState(initialVolume);
        volume.onChanged();
        addRenderableWidget(label);

        if (options != null) {
            Label optionLabel = new Label(x + 45 + volumeWidth + 9, y + 26, Component.empty()).withShadow();
            option = new SelectionScrollInput(x + 45 + volumeWidth + 5, y + 21, 139 - volumeWidth - 5, 18);
            option.forOptions(options)
                    .titled(Component.translatable("create_media_systems.speaker.channel"))
                    .writingTo(optionLabel);
            option.setState(initialOption);
            option.onChanged();
            addRenderableWidget(optionLabel);
        }

        IconButton abort = new IconButton(x + 7, y + 53, AllIcons.I_TRASH);
        abort.withCallback(() -> {
            discard.run();
            onClose();
        });
        abort.setToolTip(Component.translatable("create_media_systems.range.discard"));
        addRenderableWidget(abort);

        IconButton reset = new IconButton(x + 158, y + 53, AllIcons.I_REFRESH);
        reset.withCallback(() -> save(true));
        reset.setToolTip(resetTooltip);
        addRenderableWidget(reset);

        IconButton confirm = new IconButton(x + 180, y + 53, AllIcons.I_CONFIRM);
        confirm.withCallback(() -> save(false));
        confirm.setToolTip(Component.translatable("create_media_systems.range.confirm"));
        addRenderableWidget(confirm);

        // Widgets draw their tooltip right after themselves, so the scroll inputs (which draw nothing else) go
        // last to keep their tooltips above the labels and buttons.
        addRenderableWidget(volume);
        if (option != null) {
            addRenderableWidget(option);
        }
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = guiLeft;
        int y = guiTop;
        background.render(graphics, x, y);
        graphics.drawString(font, title, x + (background.getWidth() - 8 - font.width(title)) / 2, y + 4, 0x505050, false);
        graphics.drawCenteredString(font, status.get(), x + background.getWidth() / 2, y + background.getHeight() + 4,
                0xFFFFFF);
        GuiGameElement.of(icon).at(x + 22, y + 24, 0).render(graphics);
        GuiGameElement.of(new ItemStack(AudioFeature.RANGE_QUILL.get()))
                .scale(3)
                .at(x + background.getWidth() + 6, y + background.getHeight() - 38, -200)
                .render(graphics);
    }

    private static Component rangeStatus() {
        BoundingBox selection = RangeQuillHandler.selection();
        if (selection != null) {
            return Component.translatable("create_media_systems.range.status_new", size(selection));
        }
        EmitterRange current = RangeQuillHandler.current();
        return current == null || current.area().isEmpty()
                ? Component.translatable("create_media_systems.range.status_default", (int) EmitterRange.DEFAULT_RANGE)
                : Component.translatable("create_media_systems.range.status_kept", size(current.area().get()));
    }

    private static String size(BoundingBox box) {
        return box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan();
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            save(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void save(boolean reset) {
        save.apply(volume.getState() / 100f, reset, option == null ? 0 : option.getState());
        onClose();
    }
}
