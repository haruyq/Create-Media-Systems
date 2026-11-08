package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.lighting.LightingConsolePage.Area;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.utility.RaycastHelper;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class LightingControls {
    private static ClientLevel knownLevel;
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

    /** A control pointed at: the console's own ({@code control}), or a page's ({@code page}; control is then null). */
    record Hit(LightingConsoleBlock.Entity console, Control control, int index, Vec3 point, boolean monitor,
               @Nullable LightingConsolePage.Control page) {
        Hit(LightingConsoleBlock.Entity console, Control control, int index, Vec3 point, boolean monitor) {
            this(console, control, index, point, monitor, null);
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
        boolean empty = customPresets(console) && console.presets[slot] == null;
        return Component.translatable("create_media_systems.lighting." + (customPresets(console)
                ? empty ? "preset_empty" : "preset_slot" : "preset." + slot),
                LightingText.text(slot + 1, empty ? LightingText.MUTED : LightingText.NUMBER))
                .withColor(empty ? LightingText.MUTED : LightingText.MODE);
    }

    static Area area(Hit hit) {
        if (hit.page != null) {
            return hit.page.area();
        }
        int index = hit.index;
        if (hit.monitor) {
            if (hit.control == Control.GROUP) {
                float top = 1.86f - index * 0.74f / 6;
                return new Area(0.065f, top - 0.117f, 0.935f, top);
            }
            int row = switch (hit.control) {
                case ENABLED -> 0;
                case AUDIO -> 1;
                case BAND -> 2;
                case CYCLE -> 3;
                case PATTERN -> 4;
                case SOURCES -> 5;
                default -> -1;
            };
            if (row >= 0) {
                float top = 1.86f - row * 0.74f / 6;
                return new Area(1.065f, top - 0.117f, 1.935f, top);
            }
            return switch (hit.control) {
                case SLOT -> new Area(2.08f + index * 0.105f, 1.70f, 2.185f + index * 0.105f, 1.85f);
                case COUNT -> new Area(2.065f, 1.54f, 2.935f, 1.70f);
                case MAPPING -> new Area(2.065f, 1.38f, 2.935f, 1.54f);
                case VIDEO -> new Area(2.065f, 1.24f, 2.935f, 1.38f);
                case MOTION -> new Area(3.065f, 1.70f, 3.935f, 1.86f);
                case TARGET -> new Area(3.065f, 1.54f, 3.935f, 1.70f);
                default -> new Area(0, 0, 0, 0);
            };
        }
        return switch (hit.control) {
            case BRIGHTNESS -> new Area(0.055f + index * 0.145f, 0.15f, 0.185f + index * 0.145f, 1.3f);
            case MASTER -> new Area(1.81f, 0.15f, 1.95f, 1.3f);
            case PRESET -> new Area(1.045f + index % 3 * 0.27f, 0.24f + index / 3 * 0.5f,
                    1.255f + index % 3 * 0.27f, 0.46f + index / 3 * 0.5f);
            case PRESET_BANK -> new Area(1.04f + index * 0.26f, 1.22f, 1.28f + index * 0.26f, 1.48f);
            case PRESET_SAVE -> new Area(1.56f, 1.22f, 1.80f, 1.48f);
            case COLOR -> {
                int col = Mth.clamp((int) ((hit.point.x - 2.08) / 0.105), 0, 7);
                int row = Mth.clamp((int) ((hit.point.z - 0.15) / 0.125), 0, 7);
                yield new Area(2.08f + col * 0.105f, 0.15f + row * 0.125f,
                        2.185f + col * 0.105f, 0.275f + row * 0.125f);
            }
            case RGB -> new Area(2.1f + index * 0.3f, 1.24f, 2.3f + index * 0.3f, 1.46f);
            case RANGE -> new Area(3.1f, 1.24f, 3.3f, 1.46f);
            case SPREAD -> new Area(3.4f, 1.24f, 3.6f, 1.46f);
            case SPEED -> new Area(3.7f, 1.24f, 3.9f, 1.46f);
            case AIM -> new Area(3.1f, 0.15f, 3.9f, 1.1f);
            default -> new Area(0, 0, 0, 0);
        };
    }

    static Hit within(Hit hit) {
        Area area = area(hit);
        double vertical = hit.monitor ? hit.point.y : hit.point.z;
        return area.contains(hit.point.x, vertical) ? hit : null;
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
        if (hit.control != Control.PRESET && hit.control != Control.PRESET_SAVE && hit.control != Control.PRESET_BANK) {
            savingGroup = -1;
        }
        if (hit.control == Control.BRIGHTNESS || hit.control == Control.MASTER || hit.control == Control.AIM) {
            held = hit;
            heldGroup = hit.control == Control.BRIGHTNESS ? hit.index : hit.console.settings.selectedGroup;
            if (hit.control == Control.BRIGHTNESS) {
                change(hit, Control.GROUP, 0, hit.index, 0);
            }
            drag(true);
        } else if (hit.control == Control.COLOR) {
            int slot = hit.console.settings.selectedColor;
            if (shift) {
                slot = Math.min(LightingSettings.COLORS - 1, hit.console.settings.groups[hit.console.settings.selectedGroup].colorCount);
            }
            int column = Mth.clamp((int) ((hit.point.x - 2.08) / 0.84 * 8), 0, 7);
            int row = Mth.clamp((int) ((1.15 - hit.point.z) * 8), 0, 7);
            change(hit, Control.COLOR, slot, LightingSettings.palette(column, row), 0);
        } else if (hit.control == Control.PRESET) {
            boolean save = saving(hit.console);
            if (!save && customPresets(hit.console) && hit.console.presets[hit.index] == null) {
                feedback(hit, "preset_empty_help", LightingText.number(hit.index + 1));
                return true;
            }
            change(hit, Control.PRESET, hit.index, save ? 1 : customPresets(hit.console) ? 0 : 2, 0);
            savingGroup = -1;
            feedback(hit, save ? "preset_saved" : "preset_loaded", presetName(hit.console, hit.index),
                    LightingText.group(hit.console.settings, hit.console.settings.selectedGroup));
            return true;
        } else if (hit.control == Control.PRESET_BANK) {
            presetConsole = hit.console;
            customPresets = hit.index == 1;
            savingGroup = -1;
        } else if (hit.control == Control.PRESET_SAVE) {
            boolean cancel = saving(hit.console);
            presetConsole = hit.console;
            customPresets = true;
            savingGroup = cancel ? -1 : hit.console.settings.selectedGroup;
            feedback(hit, cancel ? "preset_cancelled" : "preset_choose_slot",
                    LightingText.group(hit.console.settings, hit.console.settings.selectedGroup));
            return true;
        } else if (hit.control == Control.SLOT || hit.control == Control.GROUP) {
            change(hit, hit.control, 0, hit.index, 0);
        } else {
            adjust(hit, shift ? -1 : 1);
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
            if (hit.control == Control.PRESET || hit.control == Control.PRESET_BANK || hit.control == Control.PRESET_SAVE
                    || hit.control == Control.AIM) {
                return true;
            }
            savingGroup = -1;
            if (hit.control == Control.BRIGHTNESS) {
                change(hit, Control.GROUP, 0, hit.index, 0);
            }
            if (hit.control == Control.RANGE && mc.player.isShiftKeyDown()) {
                step *= 10;
            }
            adjust(hit, step);
            feedback(hit, "hover", label(hit), scope(hit), value(hit));
            return true;
        }
        BlockHitResult at = pointed(Reach.HAND);
        if (at != null && mc.level.getBlockEntity(at.getBlockPos()) instanceof StageLightBlock.Entity light) {
            int action = controlDown() ? 1 : mc.player.isShiftKeyDown() ? 2 : 0;
            int value = action == 0 ? Math.floorMod(light.group + step, LightingSettings.GROUPS)
                    : action == 1 ? Math.floorMod(light.individualColor + step, LightingSettings.COLORS) : step;
            PacketDistributor.sendToServer(new LightingPackets.Fixture(light.getBlockPos(), action, value));
            return true;
        }
        return false;
    }

    private static void adjust(Hit hit, int step) {
        LightingSettings settings = hit.console.settings;
        LightingSettings.Group group = settings.groups[settings.selectedGroup];
        int index = hit.index;
        float value;
        switch (hit.control) {
            case GROUP -> value = Math.floorMod(settings.selectedGroup + step, LightingSettings.GROUPS);
            case BRIGHTNESS -> value = settings.groups[index].brightness + step * 0.05f;
            case MASTER -> value = settings.master + step * 0.05f;
            case COLOR, SLOT -> {
                change(hit, Control.SLOT, 0, Math.floorMod(settings.selectedColor + step, LightingSettings.COLORS), 0);
                return;
            }
            case COUNT -> value = group.colorCount + step;
            case RGB -> {
                value = (group.colors[settings.selectedColor] >> ((2 - index) * 8) & 255)
                        + step * (Minecraft.getInstance().player.isShiftKeyDown() ? 1 : 8);
                index += settings.selectedColor * 3;
            }
            case MAPPING -> value = Math.floorMod(group.mapping + step, 4);
            case VIDEO -> value = 1 - group.colorMode;
            case AUDIO -> value = group.audio ? 0 : 1;
            case BAND -> value = Math.floorMod(group.audioBand + step, 4);
            case MOTION -> value = Math.floorMod(group.motion + step, 4);
            case PATTERN -> value = Math.floorMod(group.pattern + step, 5);
            case RANGE -> {
                if (controlDown()) {
                    change(hit, Control.RANGE_ALL, 0, step, 0);
                    return;
                }
                value = group.range + step;
            }
            case SPREAD -> value = group.spread + step;
            case SPEED -> value = group.speed + step * 0.05f;
            case ENABLED -> value = settings.enabled ? 0 : 1;
            case CYCLE -> value = group.colorCycle ? 0 : 1;
            case TARGET -> value = step;
            case SOURCES -> value = step < 0 ? 0 : 1;
            default -> { return; }
        }
        change(hit, hit.control, index, value, 0);
    }

    private static void change(Hit hit, Control control, int index, float value, float other) {
        int group = control == Control.BRIGHTNESS ? hit.index : hit.console.settings.selectedGroup;
        send(hit.console, group, control, index, value, other);
    }

    private static void send(LightingConsoleBlock.Entity console, int group, Control control, int index,
                             float value, float other) {
        LightingPackets.Change change = new LightingPackets.Change(console.getBlockPos(), group, control.ordinal(), index, value, other);
        LightingPackets.apply(console, change, Minecraft.getInstance().player);
        PacketDistributor.sendToServer(change);
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
            } else if (held.control == Control.AIM) {
                send(held.console, heldGroup, Control.AIM, 0, (float) ((point.x - 3.5) / 0.4 * 180),
                        (float) ((point.z - 0.625) / 0.475 * 85));
            } else {
                send(held.console, heldGroup, held.control, 0, (float) ((point.z - 0.25) / 0.95), 0);
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
        // Right of the left bay, another mod's page answers while it shows.
        LightingConsolePage page = LightingConsolePage.showing(console);
        Vec3 point = onMonitor ? monitor : top;
        if (page != null && point != null && (point.x >= 1 || page.ownsLeftBay())) {
            LightingConsolePage.Control control = page.hit(console, point, onMonitor);
            return control == null ? null : within(new Hit(console, null, 0, point, onMonitor, control));
        }
        if (onMonitor) {
            int column = Mth.clamp((int) monitor.x, 0, 3);
            int row = Mth.clamp((int) ((1.86 - monitor.y) / 0.74 * 6), 0, 5);
            if (column == 0) {
                return within(new Hit(console, Control.GROUP, row, monitor, true));
            }
            if (column == 1) {
                Control[] rows = {Control.ENABLED, Control.AUDIO, Control.BAND, Control.CYCLE, Control.PATTERN, Control.SOURCES};
                return within(new Hit(console, rows[row], 0, monitor, true));
            }
            if (column == 2) {
                if (monitor.y < 1.24) {
                    return null;
                }
                Control control = monitor.y >= 1.70 ? Control.SLOT : monitor.y >= 1.54 ? Control.COUNT
                        : monitor.y >= 1.38 ? Control.MAPPING : Control.VIDEO;
                return within(new Hit(console, control, Mth.clamp((int) ((monitor.x - 2.08) / 0.84 * 8), 0, 7), monitor, true));
            }
            return monitor.y >= 1.54
                    ? within(new Hit(console, monitor.y >= 1.70 ? Control.MOTION : Control.TARGET, 0, monitor, true)) : null;
        }
        if (top == null) {
            return null;
        }
        double x = top.x;
        double z = top.z;
        if (x < 1 && z >= 0.15 && z <= 1.3) {
            return within(new Hit(console, Control.BRIGHTNESS, Mth.clamp((int) Math.round((x - 0.12) / 0.145), 0, 5), top, false));
        }
        if (x >= 1 && x < 2) {
            if (x > 1.80 && z >= 0.15 && z <= 1.3) {
                return within(new Hit(console, Control.MASTER, 0, top, false));
            }
            if (x < 1.80 && z >= 1.22 && z <= 1.48) {
                int column = Mth.clamp((int) ((x - 1.04) / 0.26), 0, 2);
                return within(new Hit(console, column == 2 ? Control.PRESET_SAVE : Control.PRESET_BANK, column == 2 ? 0 : column, top, false));
            }
            if (x < 1.80 && z >= 0.15 && z < 1.15) {
                int column = Mth.clamp((int) Math.round((x - 1.15) / 0.27), 0, 2);
                return within(new Hit(console, Control.PRESET, column + (z > 0.60 ? 3 : 0), top, false));
            }
        }
        if (x >= 2.08 && x <= 2.92) {
            if (z >= 0.15 && z <= 1.15) {
                return within(new Hit(console, Control.COLOR, 0, top, false));
            }
            if (z > 1.15 && z < 1.55) {
                return within(new Hit(console, Control.RGB, Mth.clamp((int) Math.round((x - 2.2) / 0.3), 0, 2), top, false));
            }
        }
        if (x >= 3.1 && x <= 3.9) {
            if (z >= 0.15 && z <= 1.1) {
                return within(new Hit(console, Control.AIM, 0, top, false));
            }
            if (z > 1.15 && z < 1.55) {
                Control[] controls = {Control.RANGE, Control.SPREAD, Control.SPEED};
                return within(new Hit(console, controls[Mth.clamp((int) Math.round((x - 3.2) / 0.3), 0, 2)], 0, top, false));
            }
        }
        return null;
    }

    private static double distance(LightingConsoleBlock.Entity console, Vec3 point) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = SableCompat.toBlockSpace(mc.level, console.getBlockPos(),
                SableCompanion.INSTANCE.getEyePositionInterpolated(mc.player, 1));
        return point.distanceToSqr(LightingConsoleBlock.toLocal(console.getBlockPos(), console.getBlockState().getValue(LightingConsoleBlock.FACING), eye));
    }

    @Nullable
    private static Vec3 intersect(LightingConsoleBlock.Entity console, boolean monitor) {
        Minecraft mc = Minecraft.getInstance();
        Direction facing = console.getBlockState().getValue(LightingConsoleBlock.FACING);
        BlockPos origin = console.getBlockPos();
        Vec3 eye = SableCompanion.INSTANCE.getEyePositionInterpolated(mc.player, 1);
        Vec3 from = LightingConsoleBlock.toLocal(origin, facing, SableCompat.toBlockSpace(mc.level, origin, eye));
        Vec3 to = LightingConsoleBlock.toLocal(origin, facing,
                SableCompat.toBlockSpace(mc.level, origin, eye.add(mc.player.getViewVector(1))));
        Vec3 direction = to.subtract(from);
        double divisor = monitor ? direction.z - 0.4142 * direction.y : direction.y - 0.12 * direction.z;
        if (Math.abs(divisor) < 0.00001) {
            return null;
        }
        double t = monitor ? (1.0408 - from.z + 0.4142 * from.y) / divisor
                : (0.855 + 0.12 * from.z - from.y) / divisor;
        Vec3 point = from.add(direction.scale(t));
        return t >= 0 && t <= Reach.HAND && point.x >= 0 && point.x <= 4
                && (monitor ? point.y >= 1.12 && point.y <= 1.86 : point.z >= 0 && point.z <= 1.55) ? point : null;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (knownLevel != mc.level) {
            knownLevel = mc.level;
            held = null;
            pressed = false;
            hovered = null;
            activated = null;
            presetConsole = null;
            savingGroup = -1;
            noticeUntil = 0;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            pressed = false;
        }
        drag(false);
        hovered = hand() ? held != null ? held : hit() : null;
        if (presetConsole != null && (!hand() || presetConsole.isRemoved() || !presetConsole.formed
                || distance(presetConsole, new Vec3(2, 1, 0.75)) > Reach.HAND * Reach.HAND)) {
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
                    status("fixture_help", LightingText.group(owner == null ? null : owner.settings, light.group),
                            LightingText.number(light.order + 1), LightingText.number(light.individualColor + 1));
                }
            }
        }
    }

    private static Component value(Hit hit) {
        if (hit.page != null) {
            return hit.page.value();
        }
        LightingSettings s = hit.console.settings;
        LightingSettings.Group g = s.groups[s.selectedGroup];
        return switch (hit.control) {
            case BRIGHTNESS -> LightingText.number(Math.round(s.groups[hit.index].brightness * 100) + "%");
            case MASTER -> LightingText.number(Math.round(s.master * 100) + "%");
            case RANGE -> {
                float min = g.range;
                float max = g.range;
                if (controlDown()) {
                    for (LightingSettings.Group group : s.groups) {
                        min = Math.min(min, group.range);
                        max = Math.max(max, group.range);
                    }
                }
                yield LightingText.number((min == max ? String.valueOf(Math.round(min))
                        : Math.round(min) + "–" + Math.round(max)) + " m");
            }
            case SPREAD -> LightingText.number(Math.round(g.spread) + "°");
            case SPEED -> LightingText.number(String.format(java.util.Locale.ROOT, "%.2f", g.speed));
            case COLOR, RGB, SLOT -> LightingText.number(s.selectedColor + 1).append(LightingText.text(" / ", LightingText.TEXT))
                    .append(LightingText.text(String.format(java.util.Locale.ROOT, "#%06X", g.colors[s.selectedColor]),
                            LightingText.readable(g.colors[s.selectedColor])));
            case COUNT -> LightingText.number(g.colorCount);
            case MAPPING -> LightingText.mode("mapping", g.mapping);
            case PATTERN -> LightingText.mode("pattern", g.pattern);
            case MOTION -> LightingText.mode("motion", g.motion);
            case BAND -> LightingText.mode("band", g.audioBand);
            case VIDEO -> LightingText.on(g.colorMode == 1);
            case AUDIO -> LightingText.on(g.audio);
            case ENABLED -> LightingText.on(s.enabled);
            case CYCLE -> LightingText.on(g.colorCycle);
            case TARGET -> g.target != null && Minecraft.getInstance().level.getPlayerByUUID(g.target) != null
                    ? Minecraft.getInstance().level.getPlayerByUUID(g.target).getName().copy()
                    : LightingText.text("—", LightingText.MUTED);
            case PRESET -> LightingText.message(saving(hit.console) ? "preset_save_help" : "preset_help",
                    presetName(hit.console, hit.index));
            case PRESET_BANK -> LightingText.mode("preset_bank", hit.index);
            case PRESET_SAVE -> LightingText.message(saving(hit.console) ? "preset_cancel_help" : "preset_start_help");
            case GROUP -> LightingText.message("select").withColor(LightingText.MUTED);
            case AIM -> LightingText.number(Math.round(g.pan) + "° / " + Math.round(g.tilt) + "°");
            case SOURCES -> LightingText.message("source_state",
                    LightingText.source(hit.console.audioSource != null, hit.console.hasAudio),
                    LightingText.source(hit.console.videoSource != null, hit.console.hasVideo));
            default -> LightingText.text("", LightingText.TEXT);
        };
    }

    private static void status(String key, Object... args) {
        Minecraft.getInstance().player.displayClientMessage(LightingText.message(key, args), true);
    }

    static Component label(Hit hit) {
        if (hit.page != null) {
            return hit.page.label();
        }
        var label = Component.translatable("create_media_systems.lighting.label." + hit.control.name().toLowerCase(java.util.Locale.ROOT))
                .withColor(LightingText.TEXT);
        return hit.control == Control.RANGE ? label.append(" ").append(LightingText.message("range_help")
                .withColor(LightingText.MUTED)) : label;
    }

    private static Component scope(Hit hit) {
        return hit.control == Control.RANGE && controlDown()
                ? LightingText.message("all_groups").withColor(LightingText.WARNING)
                : LightingText.group(hit.console.settings, group(hit));
    }

    private static boolean controlDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static int group(Hit hit) {
        return hit.control == Control.BRIGHTNESS || hit.control == Control.GROUP ? hit.index : hit.console.settings.selectedGroup;
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
