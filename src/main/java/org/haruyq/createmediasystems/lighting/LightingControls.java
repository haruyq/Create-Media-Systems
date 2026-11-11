package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.lighting.ConsoleLayout.Button;
import org.haruyq.createmediasystems.lighting.ConsoleLayout.Slot;
import org.haruyq.createmediasystems.lighting.LightingConsolePage.Area;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.haruyq.createmediasystems.lighting.LightingPackets.FixtureAction;
import org.haruyq.createmediasystems.lighting.LightingPackets.PresetAction;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.utility.RaycastHelper;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class LightingControls {
    private static Hit held;
    private static boolean pressed;
    private static int heldGroup;
    private static Hit hovered;
    private static Hit activated;
    private static long activatedAt;
    private static long noticeUntil;
    private static Component notice;
    private static LightingConsoleBlock.Entity presetConsole;
    private static boolean customPresets;
    private static int savingGroup = -1;

    private LightingControls() {
    }

    public static void register() {
        LightingLinkHandler.register();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        WorldEvents.onLevelChanged(() -> {
            held = null;
            pressed = false;
            hovered = null;
            activated = null;
            presetConsole = null;
            savingGroup = -1;
            noticeUntil = 0;
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent event) -> {
            if (scroll(event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
            if (event.isUseItem() && (pressed || held != null || press())) {
                pressed = true;
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        });
    }

    /** A control pointed at {@code point}: the console's own ({@code slot}), or a page's ({@code page}; slot is then null). */
    record Hit(LightingConsoleBlock.Entity console, @Nullable Slot slot, Vec3 point, boolean monitor,
               @Nullable LightingConsolePage.Control page) {
        Hit(LightingConsoleBlock.Entity console, Slot slot, Vec3 point) {
            this(console, slot, point, slot.monitor(), null);
        }

        @Nullable
        Control control() {
            return slot == null ? null : slot.control();
        }

        int index() {
            return slot == null ? 0 : slot.index();
        }

        /** Where it lights up, as pointed at. */
        Area area() {
            return page != null ? page.area() : slot.area(point);
        }

        /** This, if the point is within it; null if not. */
        @Nullable
        Hit within() {
            return area().contains(point.x, monitor ? point.y : point.z) ? this : null;
        }
    }

    static Hit hovered(LightingConsoleBlock.Entity console) {
        return hovered != null && hovered.console == console ? hovered : null;
    }

    static Hit activated(LightingConsoleBlock.Entity console) {
        return activated != null && activated.console == console && Util.getMillis() - activatedAt < 350 ? activated : null;
    }

    static boolean customPresets(LightingConsoleBlock.Entity console) {
        return presetConsole == console && customPresets;
    }

    static boolean saving(LightingConsoleBlock.Entity console) {
        return presetConsole == console && savingGroup == console.settings.selectedGroup;
    }

    static Component presetName(LightingConsoleBlock.Entity console, int slot) {
        return LightingText.presetName(console, slot, customPresets(console));
    }

    private static boolean hand() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.screen == null
                && (mc.player.getMainHandItem().isEmpty() || mc.player.getMainHandItem().is(AllItems.WRENCH.get()));
    }

    @Nullable
    private static BlockHitResult pointed(double range) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = RaycastHelper.rayTraceRange(mc.level, mc.player, range);
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean press() {
        Minecraft mc = Minecraft.getInstance();
        if (!hand()) {
            return false;
        }
        Hit hit = hit();
        if (hit == null) {
            return false;
        }
        boolean shift = mc.player.isShiftKeyDown();
        if (hit.page != null) {
            savingGroup = -1;
            if (hit.page.drags()) {
                held = hit;
                heldGroup = hit.console.settings.selectedGroup;
                drag(true);
            } else {
                hit.page.press(shift);
            }
            feedback(hit, "hover", label(hit), scope(hit), value(hit));
            return true;
        }
        LightingSettings settings = hit.console.settings;
        Button button = hit.slot.button();
        if (button == Button.PRESET_BANK) {
            presetConsole = hit.console;
            customPresets = hit.index() == 1;
            savingGroup = -1;
        } else if (button == Button.PRESET_SAVE) {
            boolean cancel = saving(hit.console);
            presetConsole = hit.console;
            customPresets = true;
            savingGroup = cancel ? -1 : settings.selectedGroup;
            feedback(hit, cancel ? "preset_cancelled" : "preset_choose_slot",
                    LightingText.group(settings, settings.selectedGroup));
            return true;
        } else {
            Control control = hit.control();
            if (control != Control.PRESET) {
                savingGroup = -1;
            }
            switch (control.press) {
                case DRAG -> {
                    held = hit;
                    heldGroup = control.indexIsGroup() ? hit.index() : settings.selectedGroup;
                    if (control == Control.BRIGHTNESS) {
                        change(hit, Control.GROUP, 0, hit.index(), 0);
                    }
                    drag(true);
                }
                case PICK -> {
                    int slot = shift ? Math.min(LightingSettings.COLORS - 1, settings.selected().colorCount)
                            : settings.selectedColor;
                    change(hit, Control.COLOR, slot, LightingSettings.palette(ConsoleLayout.paletteColumn(hit.point.x),
                            ConsoleLayout.paletteRow(hit.point.z)), 0);
                }
                case PRESET -> {
                    boolean save = saving(hit.console);
                    int slot = hit.index();
                    if (!save && customPresets(hit.console) && hit.console.presets[slot] == null) {
                        feedback(hit, "preset_empty_help", LightingText.number(slot + 1));
                        return true;
                    }
                    PresetAction action = save ? PresetAction.SAVE
                            : customPresets(hit.console) ? PresetAction.LOAD : PresetAction.FACTORY;
                    change(hit, Control.PRESET, slot, action.ordinal(), 0);
                    savingGroup = -1;
                    feedback(hit, save ? "preset_saved" : "preset_loaded", presetName(hit.console, slot),
                            LightingText.group(settings, settings.selectedGroup));
                    return true;
                }
                case SELECT -> change(hit, control, 0, hit.index(), 0);
                case STEP -> adjust(hit, shift ? -1 : 1);
                case NONE -> {
                }
            }
        }
        feedback(hit, "hover", label(hit), scope(hit), value(hit));
        return true;
    }

    private static boolean scroll(double delta) {
        if (!hand() || delta == 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Hit hit = hit();
        int step = delta > 0 ? 1 : -1;
        if (hit != null && hit.page != null) {
            savingGroup = -1;
            hit.page.scroll(step, mc.player.isShiftKeyDown(), controlDown());
            feedback(hit, "hover", label(hit), scope(hit), value(hit));
            return true;
        }
        if (hit != null) {
            Control control = hit.control();
            if (control == null || !control.steps()) {
                return true;
            }
            savingGroup = -1;
            if (control == Control.BRIGHTNESS) {
                change(hit, Control.GROUP, 0, hit.index(), 0);
            }
            if (control == Control.RANGE && mc.player.isShiftKeyDown()) {
                step *= LightingSettings.FAST_RANGE_STEP;
            }
            adjust(hit, step);
            feedback(hit, "hover", label(hit), scope(hit), value(hit));
            return true;
        }
        BlockHitResult at = pointed(Reach.HAND);
        if (at != null && mc.level.getBlockEntity(at.getBlockPos()) instanceof StageLightBlock.Entity light) {
            FixtureAction action = controlDown() ? FixtureAction.COLOR
                    : mc.player.isShiftKeyDown() ? FixtureAction.MOVE : FixtureAction.GROUP;
            int value = switch (action) {
                case GROUP -> Math.floorMod(light.group() + step, LightingSettings.GROUPS);
                case COLOR -> Math.floorMod(light.individualColor + step, LightingSettings.COLORS);
                case MOVE -> step;
            };
            PacketDistributor.sendToServer(new LightingPackets.Fixture(light.getBlockPos(), action, value));
            return true;
        }
        return false;
    }

    private static void adjust(Hit hit, int step) {
        Control control = hit.control();
        if (control == Control.RANGE && controlDown()) {
            change(hit, Control.RANGE_ALL, 0, step, 0);
            return;
        }
        LightingPackets.Stepped next = control.step(hit.console.settings, hit.index(), step,
                Minecraft.getInstance().player.isShiftKeyDown());
        change(hit, next.control(), next.index(), next.value(), 0);
    }

    private static void change(Hit hit, Control control, int index, float value, float other) {
        int group = control.indexIsGroup() ? hit.index() : hit.console.settings.selectedGroup;
        LightingConsolePage.send(hit.console, control, group, index, value, other);
    }

    private static void drag(boolean force) {
        if (held == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!hand() || held.console.isRemoved() || !held.console.formed) {
            held = null;
            pressed = false;
            return;
        }
        Vec3 point = intersect(held.console, false);
        boolean down = mc.options.keyUse.isDown();
        if (point != null && (force || !down || mc.level.getGameTime() % 2 == 0)) {
            if (held.page != null) {
                held.page.drag(point);
            } else if (held.control() == Control.AIM) {
                LightingConsolePage.send(held.console, Control.AIM, heldGroup, 0, ConsoleLayout.aimPan(point),
                        ConsoleLayout.aimTilt(point));
            } else {
                LightingConsolePage.send(held.console, held.control(), heldGroup, 0,
                        ConsoleLayout.faderValue(point.z), 0);
            }
            activated = held;
            activatedAt = Util.getMillis();
            noticeUntil = 0;
        }
        if (!down && !force) {
            held = null;
        }
    }

    @Nullable
    private static Hit hit() {
        BlockHitResult at = pointed(Reach.HAND);
        if (at == null) {
            return null;
        }
        LightingConsoleBlock.Entity console = LightingConsoleBlock.find(Minecraft.getInstance().level, at.getBlockPos());
        if (console == null) {
            return null;
        }
        Vec3 monitor = intersect(console, true);
        Vec3 top = intersect(console, false);
        boolean onMonitor = monitor != null && (top == null || distance(console, monitor) < distance(console, top));
        Vec3 point = onMonitor ? monitor : top;
        if (point == null) {
            return null;
        }
        // Right of the left bay, another mod's page answers while it shows.
        LightingConsolePage page = LightingConsolePage.showing(console);
        if (page != null && (point.x >= 1 || page.ownsLeftBay())) {
            LightingConsolePage.Control control = page.hit(console, point, onMonitor);
            return control == null ? null : new Hit(console, null, point, onMonitor, control).within();
        }
        Slot slot = ConsoleLayout.slot(point, onMonitor);
        return slot == null ? null : new Hit(console, slot, point);
    }

    /** The player's eye, in {@code console}'s space. */
    private static Vec3 eye(LightingConsoleBlock.Entity console, Vec3 eye) {
        BlockPos origin = console.getBlockPos();
        Direction facing = console.getBlockState().getValue(LightingConsoleBlock.FACING);
        return ConsoleLayout.toLocal(origin, facing, SableCompat.toBlockSpace(console.getLevel(), origin, eye));
    }

    private static double distance(LightingConsoleBlock.Entity console, Vec3 point) {
        return point.distanceToSqr(eye(console,
                SableCompat.eye(Minecraft.getInstance().player, 1)));
    }

    @Nullable
    private static Vec3 intersect(LightingConsoleBlock.Entity console, boolean monitor) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = SableCompat.eye(mc.player, 1);
        Vec3 from = eye(console, eye);
        Vec3 to = eye(console, eye.add(mc.player.getViewVector(1)));
        return ConsoleLayout.intersect(from, to.subtract(from), monitor, Reach.HAND);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            pressed = false;
        }
        drag(false);
        hovered = hand() ? held != null ? held : hit() : null;
        if (presetConsole != null && (!hand() || presetConsole.isRemoved() || !presetConsole.formed
                || distance(presetConsole, new Vec3(ConsoleLayout.BAYS / 2.0, 1, 0.75)) > Reach.HAND * Reach.HAND)) {
            presetConsole = null;
            savingGroup = -1;
        }
        if (presetConsole != null && savingGroup >= 0 && savingGroup != presetConsole.settings.selectedGroup) {
            savingGroup = -1;
        }
        if (hand() && noticeUntil > Util.getMillis()) {
            mc.player.displayClientMessage(notice, true);
            return;
        }
        if (hand()) {
            Hit hit = hovered;
            if (hit != null) {
                status("hover", label(hit), scope(hit), value(hit));
            } else {
                BlockHitResult at = pointed(Reach.HAND);
                if (at != null && mc.level.getBlockEntity(at.getBlockPos()) instanceof StageLightBlock.Entity light) {
                    LightingConsoleBlock.Entity owner = light.controller();
                    status("fixture_help", LightingText.group(owner == null ? null : owner.settings, light.group()),
                            LightingText.number(light.order() + 1), LightingText.number(light.individualColor + 1));
                }
            }
        }
    }

    private static Component value(Hit hit) {
        if (hit.page != null) {
            return hit.page.value();
        }
        Button button = hit.slot.button();
        if (button == Button.PRESET_BANK) {
            return LightingText.mode("preset_bank." + hit.index());
        }
        if (button == Button.PRESET_SAVE) {
            return LightingText.message(saving(hit.console) ? "preset_cancel_help" : "preset_start_help");
        }
        return hit.control().format(new LightingPackets.View(hit.console, hit.index(), controlDown(),
                customPresets(hit.console), saving(hit.console)));
    }

    private static void status(String key, Object... args) {
        Minecraft.getInstance().player.displayClientMessage(LightingText.message(key, args), true);
    }

    static Component label(Hit hit) {
        if (hit.page != null) {
            return hit.page.label();
        }
        Control control = hit.control();
        var label = Component.translatable("create_media_systems.lighting.label."
                + (control == null ? hit.slot.button().key : control.key)).withColor(LightingText.TEXT);
        return control == Control.RANGE ? label.append(" ").append(LightingText.message("range_help")
                .withColor(LightingText.MUTED)) : label;
    }

    private static Component scope(Hit hit) {
        return hit.control() == Control.RANGE && controlDown()
                ? LightingText.message("all_groups").withColor(LightingText.WARNING)
                : LightingText.group(hit.console.settings, group(hit));
    }

    private static boolean controlDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static int group(Hit hit) {
        Control control = hit.control();
        return control != null && control.indexIsGroup() ? hit.index() : hit.console.settings.selectedGroup;
    }

    private static void feedback(Hit hit, String key, Object... args) {
        activated = hit;
        activatedAt = Util.getMillis();
        notice = LightingText.message(key, args);
        noticeUntil = activatedAt + 1800;
        Minecraft.getInstance().player.displayClientMessage(notice, true);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.2f));
    }
}
