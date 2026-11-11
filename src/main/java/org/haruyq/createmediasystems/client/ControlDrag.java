package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.wrench.WrenchItem;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Holding right-click on a control of a block and moving it with the crosshair: a hi-fi knob, an equalizer fader. A
 * device says what a right-click works (see {@link #onUse}); a control it grabs is held (see {@link #hold}) and every
 * tick follows the look, taken in its block's own space so it holds on a moving, turning Sable structure too, until
 * right-click is let go or the block is gone. Right-clicks are kept from the game meanwhile.
 */
public final class ControlDrag {
    /**
     * Items that work blocks themselves (a quill, a lighting link, a remote): a device's own controls leave the
     * right-click to them, as they do to blocks and wrenches.
     */
    public static final TagKey<Item> BLOCK_TOOLS = TagKey.create(Registries.ITEM, CreateMediaSystems.id("block_tools"));

    private static final List<BooleanSupplier> USES = new ArrayList<>();
    @Nullable
    private static Held held;

    private ControlDrag() {
    }

    /** A control held: how it follows the look, and what it does once let go. */
    public interface Held {
        BlockPos pos();

        /** A tick held, the crosshair as {@code look} (see ControlDrag#look). */
        void drag(Look look);

        /** Let go: right-click released, or (with {@code gone}) its block no longer there. */
        void release(boolean gone);
    }

    /** The player's look in a block's space: from {@code eye}, through {@code ahead}, one block along it. */
    public record Look(Vec3 eye, Vec3 ahead) {
        public Vec3 along() {
            return ahead.subtract(eye);
        }
    }

    /**
     * {@code use} gets each right-click while nothing is held, and returns true if it took it (and may have grabbed a
     * control): the game then doesn't use the item or swing the hand.
     */
    public static void onUse(BooleanSupplier use) {
        if (USES.isEmpty()) {
            NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
                if (event.isUseItem() && (held != null || USES.stream().anyMatch(BooleanSupplier::getAsBoolean))) {
                    event.setCanceled(true);
                    event.setSwingHand(false);
                }
            });
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        }
        USES.add(use);
    }

    public static void hold(Held control) {
        held = control;
    }

    @Nullable
    public static Held held() {
        return held;
    }

    /** The player's look in the space of the block at {@code pos} (see SableCompat). */
    public static Look look(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.player.getEyePosition();
        return new Look(SableCompat.toBlockSpace(mc.level, pos, eye),
                SableCompat.toBlockSpace(mc.level, pos, eye.add(mc.player.getViewVector(1))));
    }

    /** Whether {@code player}'s right-click is for a device's controls: not while sneaking, nor with a tool for blocks. */
    public static boolean works(Player player) {
        ItemStack hand = player.getMainHandItem();
        return !player.isShiftKeyDown() && !(hand.getItem() instanceof BlockItem) && !(hand.getItem() instanceof WrenchItem)
                && !hand.is(BLOCK_TOOLS);
    }

    private static void tick() {
        Held control = held;
        if (control == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean gone = mc.level == null || mc.player == null || mc.level.getBlockEntity(control.pos()) == null;
        if (gone || !mc.options.keyUse.isDown()) {
            held = null;
            control.release(gone);
            return;
        }
        control.drag(look(control.pos()));
    }
}
