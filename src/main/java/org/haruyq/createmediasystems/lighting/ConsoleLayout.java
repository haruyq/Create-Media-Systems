package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.lighting.LightingConsolePage.Area;
import org.haruyq.createmediasystems.lighting.LightingPackets.Control;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The lighting console's shape, from its blocks to its controls: which blocks make it, the deck and monitor faces they
 * form, and where each control sits on them and how its value maps to where it is drawn. The block forms and outlines
 * the console from it, the renderer draws it, the controls are pointed at and dragged through it, and Ponder, the
 * demos and the game tests build it with it.
 *
 * <p>Console space: x across from its left edge (0) to its right (BAYS), y up from the desks' foot, z back from the
 * deck's front edge. A deck point is (x, z), the deck at deckHeight(z) below it; a monitor point (x, y), the monitors
 * leaning back at monitorZ(y).
 */
public final class ConsoleLayout {
    /** Desks across, each a bay with its monitor. */
    public static final int BAYS = 4;
    /** Desks front to back; the monitors stand on the back row. */
    public static final int ROWS = 2;

    // The deck: a slope rising to the back, its panels and printed controls a little above it.
    private static final float DECK_FOOT = 0.78f;
    private static final float DECK_RISE = 0.12f;
    /** How far panels stand above the deck. */
    public static final float PANEL_LIFT = 0.075f;
    /** How far back the deck can be pointed at. */
    public static final float DECK_DEPTH = 1.55f;
    // The monitors: their screens from MONITOR_BOTTOM to MONITOR_TOP, leaning back by MONITOR_LEAN (tan 22.5°).
    public static final float MONITOR_BOTTOM = 1.12f;
    public static final float MONITOR_TOP = 1.86f;
    private static final double MONITOR_FOOT_Z = 1.455;
    public static final double MONITOR_LEAN = 0.4142;

    // Faders: a value from 0 at the front of their travel to 1 at the back.
    private static final float FADER_FRONT = 0.25f;
    private static final float FADER_TRAVEL = 0.95f;
    public static final float MASTER_X = 1.88f;
    /** Rows of the monitors' lists, as the group list and the left settings show them. */
    public static final int MONITOR_ROWS = 6;
    private static final float ROW_HEIGHT = 0.117f;
    /** The palette's cells, across and back. */
    public static final int PALETTE_CELLS = 8;
    public static final float PALETTE_X = 2.08f;
    public static final float PALETTE_Z = 0.15f;
    public static final float CELL_WIDTH = 0.105f;
    public static final float CELL_DEPTH = 0.125f;
    public static final float KNOB_Z = 1.35f;
    /** The aim pad: its middle, and how far from it the pan and tilt reach at their limits. */
    private static final float AIM_X = 3.5f;
    private static final float AIM_Z = 0.625f;
    private static final float AIM_REACH_X = 0.4f;
    private static final float AIM_REACH_Z = 0.475f;

    private ConsoleLayout() {
    }

    /** A block of the console: a desk at ({@code column}, the back row if {@code back}), or the monitor on it. */
    public record Part(BlockPos pos, int column, boolean back, boolean monitor) {
    }

    /** Where ({@code x} across, {@code y} up, {@code z} back) of a console at {@code origin} facing {@code facing} is. */
    public static BlockPos at(BlockPos origin, Direction facing, int x, int y, int z) {
        return origin.relative(facing.getCounterClockWise(), x).relative(facing.getOpposite(), z).above(y);
    }

    /** {@code point} (in its level's block space) in console space, {@code origin} its front left desk. */
    public static Vec3 toLocal(BlockPos origin, Direction facing, Vec3 point) {
        Vec3 offset = point.subtract(Vec3.atCenterOf(origin));
        Direction right = facing.getCounterClockWise();
        Direction back = facing.getOpposite();
        return new Vec3(0.5 + offset.x * right.getStepX() + offset.z * right.getStepZ(),
                point.y - origin.getY(), 0.5 + offset.x * back.getStepX() + offset.z * back.getStepZ());
    }

    /** The front left desk of the console the part in {@code state} at {@code pos} is of, as its state says. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        boolean monitor = state.getBlock() instanceof LightingMonitorBlock;
        return at(monitor ? pos.below() : pos, state.getValue(LightingConsoleBlock.FACING),
                -state.getValue(LightingConsoleBlock.COLUMN), 0,
                monitor || state.getValue(LightingConsoleBlock.BACK) ? -1 : 0);
    }

    /** The desk under {@code pos} if that is a monitor ({@code state}), else {@code pos}. */
    public static BlockPos desk(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof LightingMonitorBlock ? pos.below() : pos;
    }

    /** Every block of a console at {@code origin}: its desks, column by column, then its monitors. */
    public static List<Part> parts(BlockPos origin, Direction facing) {
        List<Part> parts = new ArrayList<>(BAYS * (ROWS + 1));
        for (int x = 0; x < BAYS; x++) {
            for (int z = 0; z < ROWS; z++) {
                parts.add(new Part(at(origin, facing, x, 0, z), x, z == ROWS - 1, false));
            }
        }
        for (int x = 0; x < BAYS; x++) {
            parts.add(new Part(at(origin, facing, x, 1, ROWS - 1), x, true, true));
        }
        return parts;
    }

    /** The desks a console whose part at {@code pos} is a desk could start from (front left), nearest first. */
    public static List<BlockPos> origins(BlockPos pos, Direction facing) {
        List<BlockPos> origins = new ArrayList<>(BAYS * ROWS);
        for (int z = 0; z < ROWS; z++) {
            for (int x = 0; x < BAYS; x++) {
                origins.add(at(pos, facing, -x, 0, -z));
            }
        }
        return origins;
    }

    /**
     * The state a part takes in a console facing {@code facing}: as a player places it, {@code desk} or
     * {@code monitor}'s default turned to face; with {@code formed}, as the console forms it.
     */
    public static BlockState state(Part part, BlockState desk, BlockState monitor, Direction facing, boolean formed) {
        if (part.monitor()) {
            BlockState state = monitor.setValue(LightingMonitorBlock.FACING, facing);
            return formed ? state.setValue(LightingMonitorBlock.COLUMN, part.column())
                    .setValue(LightingMonitorBlock.FORMED, true) : state;
        }
        BlockState state = desk.setValue(LightingConsoleBlock.FACING, facing);
        return formed ? state.setValue(LightingConsoleBlock.COLUMN, part.column())
                .setValue(LightingConsoleBlock.BACK, part.back()).setValue(LightingConsoleBlock.FORMED, true) : state;
    }

    /**
     * Places a console at {@code origin} facing {@code facing} as a player would, each block with {@code flags}: it
     * forms on its next tick, or at once on its root desk's updateStructure. Its blocks' positions.
     */
    public static List<BlockPos> place(Level level, BlockPos origin, Direction facing, int flags) {
        BlockState desk = LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState();
        BlockState monitor = LightingFeature.LIGHTING_MONITOR.get().defaultBlockState();
        List<BlockPos> placed = new ArrayList<>();
        for (Part part : parts(origin, facing)) {
            level.setBlock(part.pos(), state(part, desk, monitor, facing, false), flags);
            placed.add(part.pos());
        }
        return placed;
    }

    /** The whole console at {@code origin}, desks and monitors. */
    public static AABB bounds(BlockPos origin, Direction facing) {
        return new AABB(origin).minmax(new AABB(at(origin, facing, BAYS - 1, 1, ROWS - 1)));
    }

    /** The deck's height at {@code z}. */
    public static float deckHeight(float z) {
        return DECK_FOOT + z * DECK_RISE;
    }

    /** How far back the monitors are at height {@code y}. */
    public static double monitorZ(float y) {
        return MONITOR_FOOT_Z + (y - 1) * MONITOR_LEAN;
    }

    /** The deck's slope (radians). */
    public static double deckAngle() {
        return Math.atan(DECK_RISE);
    }

    /**
     * Where the line from {@code from} along {@code direction} (console space) meets the deck's panels, or the
     * monitors if {@code monitor}, within {@code reach} lengths of {@code direction}: null if it misses them.
     */
    @Nullable
    public static Vec3 intersect(Vec3 from, Vec3 direction, boolean monitor, double reach) {
        double divisor = monitor ? direction.z - MONITOR_LEAN * direction.y : direction.y - DECK_RISE * direction.z;
        if (Math.abs(divisor) < 0.00001) {
            return null;
        }
        double t = monitor ? (MONITOR_FOOT_Z - MONITOR_LEAN - from.z + MONITOR_LEAN * from.y) / divisor
                : (DECK_FOOT + PANEL_LIFT + DECK_RISE * from.z - from.y) / divisor;
        Vec3 point = from.add(direction.scale(t));
        return t >= 0 && t <= reach && point.x >= 0 && point.x <= BAYS
                && (monitor ? point.y >= MONITOR_BOTTOM && point.y <= MONITOR_TOP : point.z >= 0 && point.z <= DECK_DEPTH)
                ? point : null;
    }

    /** The z a fader's cap is at for {@code value}. */
    public static float faderZ(float value) {
        return FADER_FRONT + value * FADER_TRAVEL;
    }

    /** The value a fader dragged to {@code z} takes. */
    public static float faderValue(double z) {
        return (float) ((z - FADER_FRONT) / FADER_TRAVEL);
    }

    /** Group {@code group}'s fader's x. */
    public static float groupFaderX(int group) {
        return 0.12f + group * 0.145f;
    }

    /** Preset key {@code slot}'s middle: three across, two back. */
    public static float presetX(int slot) {
        return 1.15f + slot % 3 * 0.27f;
    }

    public static float presetZ(int slot) {
        return 0.35f + slot / 3 * 0.5f;
    }

    /** The left of the preset bank key {@code key} (0 basic, 1 saved; 2 the save key). */
    public static float bankX(int key) {
        return 1.04f + key * 0.26f;
    }

    /** The palette's column and row (row 0 at the back) at a deck point. */
    public static int paletteColumn(double x) {
        return Mth.clamp((int) ((x - PALETTE_X) / CELL_WIDTH), 0, PALETTE_CELLS - 1);
    }

    public static int paletteRow(double z) {
        return Mth.clamp((int) ((PALETTE_Z + PALETTE_CELLS * CELL_DEPTH - z) / CELL_DEPTH), 0, PALETTE_CELLS - 1);
    }

    /** The left front corner of palette cell ({@code column}, {@code row}). */
    public static float paletteX(int column) {
        return PALETTE_X + column * CELL_WIDTH;
    }

    public static float paletteZ(int row) {
        return PALETTE_Z + (PALETTE_CELLS - 1 - row) * CELL_DEPTH;
    }

    /** The red, green and blue knobs' x ({@code channel} 0 to 2). */
    public static float rgbKnobX(int channel) {
        return 2.2f + channel * 0.3f;
    }

    /** The range, spread and speed knobs' x ({@code knob} 0 to 2). */
    public static float settingKnobX(int knob) {
        return 3.2f + knob * 0.3f;
    }

    /** The settings the right knobs turn, in order. */
    public static final Control[] SETTING_KNOBS = {Control.RANGE, Control.SPREAD, Control.SPEED};

    /** The aim pad's cross for {@code pan} and {@code tilt}: (x, z). */
    public static float aimX(float pan) {
        return AIM_X + Mth.clamp(pan / LightingSettings.PAN.max(), -1, 1) * AIM_REACH_X;
    }

    public static float aimZ(float tilt) {
        return AIM_Z + Mth.clamp(tilt / LightingSettings.TILT.max(), -1, 1) * AIM_REACH_Z;
    }

    /** The pan and tilt the aim pad dragged to {@code point} sets. */
    public static float aimPan(Vec3 point) {
        return (float) ((point.x - AIM_X) / AIM_REACH_X * LightingSettings.PAN.max());
    }

    public static float aimTilt(Vec3 point) {
        return (float) ((point.z - AIM_Z) / AIM_REACH_Z * LightingSettings.TILT.max());
    }

    /** The top of monitor list row {@code row}. */
    public static float rowTop(int row) {
        return MONITOR_TOP - row * (MONITOR_TOP - MONITOR_BOTTOM) / MONITOR_ROWS;
    }

    /** Monitor list row {@code row} of bay {@code bay}. */
    public static Area row(int bay, int row) {
        float top = rowTop(row);
        return new Area(bay + 0.065f, top - ROW_HEIGHT, bay + 0.935f, top);
    }

    /** The settings the second monitor lists, top to bottom. */
    public static final Control[] SETTING_ROWS = {Control.ENABLED, Control.AUDIO, Control.BAND, Control.CYCLE,
            Control.PATTERN, Control.SOURCES};

    /** What the client draws and works on the console without the server's knowing: the preset bank's keys. */
    public enum Button {
        /** Index 0: the built-in presets; 1: those saved. */
        PRESET_BANK("preset_bank"),
        /** Saves the selected group to the next preset key pressed. */
        PRESET_SAVE("preset_save");

        /** Its label's lang key, after {@code create_media_systems.lighting.label.}. */
        public final String key;

        Button(String key) {
            this.key = key;
        }
    }

    /**
     * A control's place: a console setting's ({@code control}) or a client key's ({@code button}), {@code index}
     * (its group, slot, channel or key), within {@code area} on the deck or the monitors.
     */
    public record Slot(@Nullable Control control, @Nullable Button button, int index, Area area, boolean monitor) {
        /** The part of it pointed at {@code point} that lights up: the palette's cell, the rest whole. */
        public Area area(Vec3 point) {
            if (control != Control.COLOR) {
                return area;
            }
            int column = paletteColumn(point.x);
            int row = paletteRow(point.z);
            float x = paletteX(column);
            float z = paletteZ(row);
            return new Area(x, z, x + CELL_WIDTH, z + CELL_DEPTH);
        }

        /** Whether {@code point} is on it. */
        public boolean contains(Vec3 point) {
            return area.contains(point.x, monitor ? point.y : point.z);
        }
    }

    /** Every control on the console's face. */
    public static final List<Slot> SLOTS = slots();

    private static List<Slot> slots() {
        List<Slot> slots = new ArrayList<>();
        for (int group = 0; group < LightingSettings.GROUPS; group++) {
            float x = groupFaderX(group);
            slots.add(deck(Control.BRIGHTNESS, group, new Area(x - 0.065f, 0.15f, x + 0.065f, 1.3f)));
            slots.add(new Slot(Control.GROUP, null, group, row(0, group), true));
        }
        slots.add(deck(Control.MASTER, 0, new Area(MASTER_X - 0.07f, 0.15f, MASTER_X + 0.07f, 1.3f)));
        for (int slot = 0; slot < LightingSettings.PRESETS; slot++) {
            float x = presetX(slot);
            float z = presetZ(slot);
            slots.add(deck(Control.PRESET, slot, new Area(x - 0.105f, z - 0.11f, x + 0.105f, z + 0.11f)));
        }
        for (int key = 0; key < 3; key++) {
            Area area = new Area(bankX(key), 1.22f, bankX(key) + 0.24f, 1.48f);
            slots.add(key < 2 ? new Slot(null, Button.PRESET_BANK, key, area, false)
                    : new Slot(null, Button.PRESET_SAVE, 0, area, false));
        }
        slots.add(deck(Control.COLOR, 0, new Area(PALETTE_X, PALETTE_Z, PALETTE_X + PALETTE_CELLS * CELL_WIDTH,
                PALETTE_Z + PALETTE_CELLS * CELL_DEPTH)));
        for (int i = 0; i < 3; i++) {
            slots.add(deck(Control.RGB, i, knob(rgbKnobX(i))));
            slots.add(deck(SETTING_KNOBS[i], 0, knob(settingKnobX(i))));
        }
        slots.add(deck(Control.AIM, 0, new Area(AIM_X - AIM_REACH_X, 0.15f, AIM_X + AIM_REACH_X, 1.1f)));
        for (int row = 0; row < SETTING_ROWS.length; row++) {
            slots.add(new Slot(SETTING_ROWS[row], null, 0, row(1, row), true));
        }
        for (int slot = 0; slot < LightingSettings.COLORS; slot++) {
            slots.add(new Slot(Control.SLOT, null, slot, new Area(paletteX(slot), 1.70f, paletteX(slot) + CELL_WIDTH,
                    1.85f), true));
        }
        slots.add(new Slot(Control.COUNT, null, 0, new Area(2.065f, 1.54f, 2.935f, 1.70f), true));
        slots.add(new Slot(Control.MAPPING, null, 0, new Area(2.065f, 1.38f, 2.935f, 1.54f), true));
        slots.add(new Slot(Control.VIDEO, null, 0, new Area(2.065f, 1.24f, 2.935f, 1.38f), true));
        slots.add(new Slot(Control.MOTION, null, 0, new Area(3.065f, 1.70f, 3.935f, 1.86f), true));
        slots.add(new Slot(Control.TARGET, null, 0, new Area(3.065f, 1.54f, 3.935f, 1.70f), true));
        return List.copyOf(slots);
    }

    private static Slot deck(Control control, int index, Area area) {
        return new Slot(control, null, index, area, false);
    }

    private static Area knob(float x) {
        return new Area(x - 0.1f, 1.24f, x + 0.1f, 1.46f);
    }

    /** The control at {@code point}, on the monitors if {@code monitor}, else the deck; null between controls. */
    @Nullable
    public static Slot slot(Vec3 point, boolean monitor) {
        for (Slot slot : SLOTS) {
            if (slot.monitor() == monitor && slot.contains(point)) {
                return slot;
            }
        }
        return null;
    }

    /** {@code control}'s place ({@code index} its group, slot or channel), if it has one on the face. */
    @Nullable
    public static Slot slot(Control control, int index) {
        for (Slot slot : SLOTS) {
            if (slot.control() == control && slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    /** A setting the monitors show, its label and value written from ({@code x}, {@code y}). */
    public record Readout(Control control, float x, float y) {
    }

    /** What the monitors show of the selected group, beside the controls they hold. */
    public static final List<Readout> READOUTS = readouts();

    private static List<Readout> readouts() {
        List<Readout> readouts = new ArrayList<>();
        for (int row = 0; row < SETTING_ROWS.length; row++) {
            readouts.add(new Readout(SETTING_ROWS[row], 1.08f, rowTop(row) - 0.015f));
        }
        readouts.add(new Readout(Control.COUNT, 2.08f, 1.665f));
        readouts.add(new Readout(Control.MAPPING, 2.08f, 1.505f));
        readouts.add(new Readout(Control.VIDEO, 2.08f, 1.345f));
        readouts.add(new Readout(Control.MOTION, 3.08f, 1.82f));
        readouts.add(new Readout(Control.TARGET, 3.08f, 1.68f));
        readouts.add(new Readout(Control.RANGE, 3.08f, 1.49f));
        readouts.add(new Readout(Control.SPREAD, 3.08f, 1.37f));
        readouts.add(new Readout(Control.SPEED, 3.08f, 1.25f));
        return List.copyOf(readouts);
    }
}
