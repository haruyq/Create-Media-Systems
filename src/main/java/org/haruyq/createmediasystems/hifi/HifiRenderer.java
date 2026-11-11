package org.haruyq.createmediasystems.hifi;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.QuadOutput;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.playback.Spectrum;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.DiscTransport;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Draws a hi-fi stack unit by unit, as its units are stacked: each a dark metal box with its controls where Control
 * has them, its legends, and what moves and lights up on it (knobs, the disc tray, a tape's reels, lamps, the
 * fluorescent displays with the spectrum and level meters of what plays here), and on the back its sockets and the
 * cables plugged into them. A unit's item is drawn the same way, alone.
 */
public final class HifiRenderer implements BlockEntityRenderer<HifiStackBlock.Entity> {
    static final RenderType METAL = RenderType.entityCutoutNoCull(CreateMediaSystems.id("textures/block/hifi_metal.png"));
    /** How near (blocks) the camera is for legends and display text to be drawn. */
    private static final double TEXT_RANGE = 12;
    /** The gap under each unit, on its four feet. */
    private static final float FOOT = 0.25f;
    private static final int BODY = 0x2A2A2E;
    private static final int FASCIA = 0x333338;
    private static final int BUTTON = 0x47474E;
    private static final int KNOB = 0x3C3C42;
    private static final int LEGEND = 0xD2D2D2;
    private static final int FAINT = 0x9A9AA0;
    private static final int GLASS = 0x05070A;
    private static final int UNLIT = 0x0E1A1F;
    private static final int CYAN = 0x7BEBFF;
    private static final int AMBER = 0xFFB347;
    private static final int RED = 0xFF4A3A;
    private static final int GREEN = 0x66FF88;
    private static final int HOVER = 0x8AEAFF;
    private static final int LEFT = 0;
    private static final int MIDDLE = 1;
    private static final int RIGHT = 2;

    /** What each stack's meters show, falling back slowly from what was last heard. */
    private static final Map<HifiStackBlock.Entity, Meters> METERS = new WeakHashMap<>();

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerBlockEntityRenderer(HifiFeature.HIFI_STACK_ENTITY.get(), context -> new HifiRenderer());
            event.registerBlockEntityRenderer(HifiFeature.HIFI_SPEAKER_ENTITY.get(), context -> new SpeakerCable());
        });
        modBus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new UnitItems();
                }
                return renderer;
            }
        }, Arrays.stream(Unit.values()).map(HifiFeature::hifiUnit).toArray(Item[]::new)));
    }

    @Override
    public void render(HifiStackBlock.Entity stack, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockPos pos = stack.getBlockPos();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean near = camera.distanceToSqr(SableCompat.toRenderWorld(stack.getLevel(), pos, Vec3.atCenterOf(pos)))
                < TEXT_RANGE * TEXT_RANGE;
        draw(stack, stack.getBlockState().getValue(HifiStackBlock.FACING), partialTick, poseStack, buffers, light, near,
                METERS.computeIfAbsent(stack, key -> new Meters()).update(stack));
    }

    /** The meters' levels as shown: up at once, down at a steady fall, each band's peak held a little longer. */
    private static final class Meters {
        final float[] bands = new float[Spectrum.BANDS];
        final float[] peaks = new float[Spectrum.BANDS];
        float left;
        float right;
        private long at;

        Meters update(HifiStackBlock.Entity stack) {
            long now = Util.getMillis();
            float fall = Math.min(1, (now - at) / 1000f);
            at = now;
            Spectrum.Frame heard = stack.power() ? NativeAudio.spectrum(stack.getBlockPos()) : null;
            for (int band = 0; band < bands.length; band++) {
                bands[band] = Math.max(heard == null ? 0 : heard.bands()[band], bands[band] - fall * 1.6f);
                peaks[band] = Math.max(bands[band], peaks[band] - fall * 0.3f);
            }
            left = Math.max(heard == null ? 0 : heard.left(), left - fall * 1.6f);
            right = Math.max(heard == null ? 0 : heard.right(), right - fall * 1.6f);
            return this;
        }
    }

    private static void draw(HifiStackBlock.Entity stack, Direction facing, float partialTick, PoseStack poseStack,
                             MultiBufferSource buffers, int light, boolean text, Meters meters) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5, 0, -0.5);
        poseStack.scale(1 / 16f, 1 / 16f, 1 / 16f);
        Matrix4f space = new Matrix4f(poseStack.last().pose());
        poseStack.popPose();
        Pen pen = new Pen(buffers, light, text);
        Control hovered = HifiControls.hovered(stack);
        Control pressed = HifiControls.pressed(stack);
        List<Unit> units = stack.units();
        float bottom = 0;
        for (Unit unit : units) {
            // The front: across from the unit's left, up from its foot, out of its face.
            pen.at = new Matrix4f(space).translate(Unit.LEFT, bottom, Unit.FRONT);
            pen.box(0, FOOT, -Unit.DEPTH, Unit.WIDTH, unit.height, 0, BODY);
            pen.box(0, FOOT, 0, Unit.WIDTH, unit.height, 0.06f, FASCIA);
            for (float x : new float[]{0.6f, Unit.WIDTH - 1.8f}) {
                pen.box(x, 0, -1.6f, x + 1.2f, FOOT, -0.4f, 0x18181A);
                pen.box(x, 0, -Unit.DEPTH + 0.4f, x + 1.2f, FOOT, -Unit.DEPTH + 1.6f, 0x18181A);
            }
            switch (unit) {
                case AMPLIFIER -> amplifier(pen, stack, pressed);
                case EQUALIZER -> equalizer(pen, stack, pressed, meters);
                case TUNER -> tuner(pen, stack, pressed);
                case DISC_PLAYER -> discPlayer(pen, stack, pressed, partialTick);
                case CASSETTE_DECK -> deck(pen, stack, pressed, meters, partialTick);
            }
            if (hovered != null && hovered.unit == unit && !hovered.rear()) {
                pen.outline(hovered, hovered.kind == Control.Kind.KNOB ? 0.75f : 0.3f);
            }
            // The back, the same way round for whoever stands behind it.
            pen.at = new Matrix4f(space).translate(Unit.LEFT + Unit.WIDTH, bottom, Unit.BACK).rotateY((float) Math.PI);
            back(pen, stack, unit);
            if (hovered != null && hovered.unit == unit && hovered.rear()) {
                pen.outline(hovered, 0.8f);
            }
            bottom += unit.height;
        }
        pen.at = new Matrix4f(space).translate(Unit.LEFT + Unit.WIDTH, 0, Unit.BACK).rotateY((float) Math.PI);
        cables(pen, stack);
    }

    private static void button(Pen pen, Control control, @Nullable Control pressed, @Nullable String label, int lamp) {
        float depth = control == pressed ? 0.09f : 0.2f;
        pen.box(control.left, control.bottom, 0.06f, control.right, control.top, depth, BUTTON);
        if (label != null) {
            pen.text(control.centerX(), control.bottom + 0.07f, depth + 0.01f, 0.2f, label, LEGEND, false, MIDDLE,
                    control.right - control.left - 0.12f);
        }
        if (lamp != 0) {
            pen.glow(control.left + 0.12f, control.top - 0.11f, control.right - 0.12f, control.top - 0.04f, depth + 0.012f, lamp);
        }
    }

    private static void amplifier(Pen pen, HifiStackBlock.Entity stack, @Nullable Control pressed) {
        boolean on = stack.power();
        UnitPart.Amplifier amplifier = stack.amplifier();
        pen.text(0.5f, 2.78f, 0.07f, 0.45f, Unit.BRAND, LEGEND, false, LEFT, 3);
        pen.text(11.5f, 0.55f, 0.07f, 0.2f, "STEREO AMPLIFIER  " + Unit.AMPLIFIER.model, FAINT, false, RIGHT, 5);
        button(pen, Control.POWER, pressed, "POWER", 0);
        pen.glow(1.78f, 2.06f, 2.02f, 2.24f, 0.07f, on ? GREEN : RED);
        button(pen, Control.DIRECT, pressed, "DIRECT", on && amplifier.direct ? AMBER : 0);
        button(pen, Control.WIDE, pressed, "WIDE", on && amplifier.wide ? AMBER : 0);
        button(pen, Control.BASS, pressed, "BASS", on && amplifier.bass ? AMBER : 0);
        pen.knob(Control.VOLUME, 0.7f, stack.knob(Control.VOLUME) / 100f);
        pen.text(5.5f, 2.74f, 0.07f, 0.2f, "VOLUME", FAINT, false, RIGHT, 2);
        pen.knob(Control.BASS_LEVEL, 0.45f, stack.knob(Control.BASS_LEVEL) / 100f);
        pen.text(Control.BASS_LEVEL.centerX(), 2.86f, 0.07f, 0.18f, "LEVEL", FAINT, false, MIDDLE, 1.4f);
        String[] inputs = {"DISC", "TUNER", "TAPE"};
        for (Control control : new Control[]{Control.INPUT_DISC, Control.INPUT_TUNER, Control.INPUT_TAPE}) {
            button(pen, control, pressed, inputs[control.index], on && amplifier.input.ordinal() == control.index ? RED : 0);
        }
        pen.text(Control.INPUT_TUNER.centerX(), 1.05f, 0.07f, 0.16f, "INPUT SELECTOR", FAINT, false, MIDDLE, 3.6f);
        // The headphone socket, for show.
        pen.box(0.65f, 0.55f, 0.06f, 1.25f, 1.15f, 0.12f, 0x1B1B1E);
        pen.glow(0.83f, 0.73f, 1.07f, 0.97f, 0.125f, 0x020203);
    }

    private static void equalizer(Pen pen, HifiStackBlock.Entity stack, @Nullable Control pressed, Meters meters) {
        boolean live = stack.live(Unit.EQUALIZER);
        UnitPart.Equalizer equalizer = stack.equalizer();
        legend(pen, Unit.EQUALIZER, "SOUND CONTROLLER", 1.75f);
        pen.glow(3, 0.95f, 9, 2.2f, 0.07f, GLASS);
        if (live) {
            for (int band = 0; band < Spectrum.BANDS; band++) {
                float x = 3.15f + band * 0.44f;
                int lit = Math.round(meters.bands[band] * 9);
                int peak = Math.round(meters.peaks[band] * 9) - 1;
                for (int row = 0; row < 9; row++) {
                    int color = row == peak && peak >= lit ? 0xFFFFFF : row >= lit ? UNLIT : row >= 8 ? RED : row >= 6 ? AMBER : CYAN;
                    pen.glow(x, 1.02f + row * 0.125f, x + 0.34f, 1.12f + row * 0.125f, 0.09f, color);
                }
            }
            pen.text(8.88f, 1.84f, 0.09f, 0.24f, equalizer.preset.name(), CYAN, true, RIGHT, 1.25f);
            pen.text(8.88f, 1.42f, 0.09f, 0.15f, "EFFECT", 0x3F8FA0, true, RIGHT, 1.25f);
            pen.glow(7.7f, 1.1f, 8.88f, 1.22f, 0.09f, UNLIT);
            pen.glow(7.7f, 1.1f, 7.7f + 1.18f * stack.knob(Control.EFFECT) / 100f, 1.22f, 0.095f, AMBER);
        }
        for (Control control : Control.values()) {
            if (control.unit == Unit.EQUALIZER && control.kind == Control.Kind.BUTTON) {
                button(pen, control, pressed, HifiStackBlock.Preset.byId(control.index).name(),
                        live && equalizer.preset.ordinal() == control.index ? CYAN : 0);
            }
        }
        pen.knob(Control.EFFECT, 0.55f, stack.knob(Control.EFFECT) / 100f);
        pen.text(Control.EFFECT.centerX(), 2.12f, 0.07f, 0.18f, "EFFECT LEVEL", FAINT, false, MIDDLE, 2);
    }

    private static void tuner(Pen pen, HifiStackBlock.Entity stack, @Nullable Control pressed) {
        boolean live = stack.live(Unit.TUNER);
        UnitPart.Tuner tuner = stack.tuner();
        legend(pen, Unit.TUNER, "STEREO TUNER", 1.75f);
        pen.glow(3, 0.95f, 7.6f, 2.2f, 0.07f, GLASS);
        if (live) {
            int station = tuner.station;
            String link = station < 0 ? "" : tuner.station(station);
            pen.text(3.25f, 1.22f, 0.09f, 0.72f, station < 0 ? "P-" : "P" + (station + 1), CYAN, true, LEFT, 1.5f);
            pen.text(7.4f, 1.68f, 0.09f, 0.26f, link.isEmpty() ? "NO STATION" : host(link), CYAN, true, RIGHT, 2.5f);
            if (!link.isEmpty() && stack.amplifier().input == HifiStackBlock.Input.TUNER) {
                pen.text(7.4f, 1.2f, 0.09f, 0.2f, "TUNED", RED, true, RIGHT, 2.5f);
            }
        }
        for (Control control : Control.values()) {
            if (control.unit == Unit.TUNER && control.kind == Control.Kind.BUTTON) {
                button(pen, control, pressed, String.valueOf(control.index + 1),
                        live && tuner.station == control.index ? CYAN : 0);
            }
        }
        pen.text(9.77f, 2.02f, 0.07f, 0.16f, "STATION", FAINT, false, MIDDLE, 3);
    }

    /** A link's host, as a station's name. */
    private static String host(String link) {
        int from = link.indexOf("://");
        String host = from < 0 ? link : link.substring(from + 3);
        int to = host.indexOf('/');
        host = to < 0 ? host : host.substring(0, to);
        return (host.startsWith("www.") ? host.substring(4) : host).toUpperCase(Locale.ROOT);
    }

    private static void discPlayer(Pen pen, HifiStackBlock.Entity stack, @Nullable Control pressed, float partialTick) {
        boolean live = stack.live(Unit.DISC_PLAYER);
        UnitPart.DiscPlayer player = stack.discPlayer();
        DiscTransport transport = player.transport;
        legend(pen, Unit.DISC_PLAYER, "DISC PLAYER", 2.3f);
        float out = (player.lastTrayOut + (player.trayOut - player.lastTrayOut) * partialTick) * 5.2f;
        Control tray = Control.TRAY;
        // The tray's front, and the drawer behind it as it comes out with the disc.
        pen.box(tray.left, tray.bottom, 0.06f + out, tray.right, tray.top, 0.2f + out, 0x3E3E44);
        pen.text(tray.left + 0.2f, tray.bottom + 0.12f, 0.21f + out, 0.17f, "DISC", FAINT, false, LEFT, 2);
        if (out > 0.05f) {
            pen.box(tray.left + 0.15f, tray.bottom + 0.12f, 0.06f, tray.right - 0.15f, tray.bottom + 0.3f, 0.06f + out, 0x1E1E21);
            if (!player.disc.isEmpty()) {
                DiscKind kind = MediaItem.kindOf(player.disc);
                int color = kind == null ? 0x55555E : switch (kind) {
                    case BLU_RAY -> 0x5878C8;
                    case DVD -> 0xD8C690;
                    case CD, CASSETTE -> 0xD0D6DE;
                    case CUSTOM_DISC -> 0x55555E;
                };
                pen.disc(tray.centerX(), tray.bottom + 0.3f, out - 2.5f, 2.3f, color);
            }
        }
        pen.glow(3, 0.5f, 8.6f, 1.55f, 0.07f, GLASS);
        if (live) {
            List<String> playlist = player.playlist();
            if (player.trayOpen) {
                pen.text(5.8f, 0.78f, 0.09f, 0.5f, "OPEN", CYAN, true, MIDDLE, 5);
            } else if (playlist.isEmpty()) {
                pen.text(5.8f, 0.78f, 0.09f, 0.5f, "NO DISC", CYAN, true, MIDDLE, 5);
            } else {
                pen.text(3.25f, 0.7f, 0.09f, 0.68f, String.format(Locale.ROOT, "%02d", player.shownIndex() + 1),
                        CYAN, true, LEFT, 1.6f);
                pen.text(4.95f, 0.72f, 0.09f, 0.26f, "/" + playlist.size(), 0x3F8FA0, true, LEFT, 1);
                boolean loading = stack.loading(HifiStackBlock.Input.DISC);
                pen.text(5.75f, 1.08f, 0.09f, 0.26f, !transport.playing() ? "STOP" : transport.paused() ? "PAUSE"
                        : loading ? "LOAD" : "PLAY", transport.playing() && !transport.paused() && !loading ? GREEN : AMBER,
                        true, LEFT, 1.2f);
                pen.text(8.4f, 0.7f, 0.09f, 0.6f, HifiStackBlock.Entity.clock(transport.clock().position()), CYAN, true, RIGHT, 2.4f);
            }
        }
        button(pen, Control.OPEN, pressed, "OPEN", 0);
        button(pen, Control.PLAY, pressed, "PLAY", live && transport.playing() && !transport.paused() ? GREEN : 0);
        button(pen, Control.STOP, pressed, "STOP", 0);
        button(pen, Control.PREVIOUS, pressed, "<<", 0);
        button(pen, Control.NEXT, pressed, ">>", 0);
    }

    private static void deck(Pen pen, HifiStackBlock.Entity stack, @Nullable Control pressed, Meters meters, float partialTick) {
        boolean live = stack.live(Unit.CASSETTE_DECK);
        UnitPart.CassetteDeck decks = stack.deck();
        pen.text(0.9f, 4.06f, 0.07f, 0.3f, Unit.BRAND, LEGEND, false, LEFT, 2.4f);
        pen.text(11.1f, 4.1f, 0.07f, 0.18f, "STEREO DOUBLE CASSETTE DECK  " + Unit.CASSETTE_DECK.model, FAINT, false, RIGHT, 6.5f);
        for (int deck = 0; deck < 2; deck++) {
            Control door = deck == 0 ? Control.DOOR_1 : Control.DOOR_2;
            pen.box(door.left, door.bottom, 0.06f, door.right, door.top, 0.14f, 0x232327);
            pen.glow(door.left + 0.5f, door.bottom + 0.35f, door.right - 0.5f, door.top - 0.45f, 0.145f, GLASS);
            pen.text(door.right - 0.2f, door.top - 0.36f, 0.145f, 0.2f, deck == 0 ? "DECK I" : "DECK II", FAINT, false, RIGHT, 2);
            if (!decks.tape(deck).isEmpty()) {
                float x = door.centerX();
                float y = door.bottom + 0.5f;
                // The cassette behind the window: its shell, its label, and the two reels showing through it.
                pen.box(x - 1.6f, y, 0.15f, x + 1.6f, y + 1.1f, 0.2f, 0x4A4A50);
                pen.box(x - 1.4f, y + 0.72f, 0.2f, x + 1.4f, y + 1.02f, 0.215f, 0xD8D2C0);
                pen.glow(x - 1.05f, y + 0.18f, x + 1.05f, y + 0.62f, 0.205f, 0x100D0A);
                float turn = decks.lastReels[deck] + (decks.reels[deck] - decks.lastReels[deck]) * partialTick;
                pen.reel(x - 0.68f, y + 0.4f, 0.21f, 0.2f, turn);
                pen.reel(x + 0.68f, y + 0.4f, 0.21f, 0.2f, turn);
            }
            HifiStackBlock.Motion motion = decks.motion(deck);
            Control[] keys = deck == 0 ? new Control[]{Control.REWIND_1, Control.PLAY_1, Control.FORWARD_1, Control.STOP_1}
                    : new Control[]{Control.REWIND_2, Control.PLAY_2, Control.FORWARD_2, Control.STOP_2};
            button(pen, keys[0], pressed, "<<", live && motion == HifiStackBlock.Motion.REWIND ? AMBER : 0);
            button(pen, keys[1], pressed, "PLAY", live && motion == HifiStackBlock.Motion.PLAY ? GREEN : 0);
            button(pen, keys[2], pressed, ">>", live && motion == HifiStackBlock.Motion.FORWARD ? AMBER : 0);
            button(pen, keys[3], pressed, "STOP", 0);
        }
        pen.glow(0.9f, 1.15f, 11.1f, 1.75f, 0.07f, GLASS);
        if (!live) {
            return;
        }
        for (int deck = 0; deck < 2; deck++) {
            int tracks = decks.tracks(deck).size();
            String counter = decks.tape(deck).isEmpty() ? "NO TAPE" : tracks == 0 ? "BLANK"
                    : decks.track(deck) >= tracks ? "END" : (decks.track(deck) + 1) + "  "
                    + HifiStackBlock.Entity.clock(decks.ticks(deck));
            pen.text(deck == 0 ? 1.05f : 8.15f, 1.3f, 0.09f, 0.3f, counter, CYAN, true, LEFT, 2.8f);
        }
        // What the tape puts out, a row per channel.
        boolean tape = stack.amplifier().input == HifiStackBlock.Input.TAPE && decks.playingDeck() >= 0;
        for (int channel = 0; channel < 2; channel++) {
            int lit = tape ? Math.round((channel == 0 ? meters.left : meters.right) * 14) : 0;
            float y = channel == 0 ? 1.5f : 1.27f;
            for (int segment = 0; segment < 14; segment++) {
                float x = 4.3f + segment * 0.245f;
                pen.glow(x, y, x + 0.19f, y + 0.13f, 0.09f,
                        segment >= lit ? UNLIT : segment >= 12 ? RED : segment >= 10 ? AMBER : CYAN);
            }
        }
    }

    /** The maker, what it is and its model number, at the left of a unit's front; the maker's name at {@code y}. */
    private static void legend(Pen pen, Unit unit, String kind, float y) {
        pen.text(0.4f, y, 0.07f, 0.32f, Unit.BRAND, LEGEND, false, LEFT, 2.3f);
        pen.text(0.4f, y - 0.3f, 0.07f, 0.16f, kind, FAINT, false, LEFT, 2.3f);
        pen.text(0.4f, y - 0.6f, 0.07f, 0.2f, unit.model, FAINT, false, LEFT, 2.3f);
    }

    /** A unit's back: its rating plate, and its sockets with the plugs in them. */
    private static void back(Pen pen, HifiStackBlock.Entity stack, Unit unit) {
        float plate = unit == Unit.AMPLIFIER ? 2.2f : 0.55f;
        float left = unit == Unit.AMPLIFIER ? 4.6f : 7.4f;
        pen.box(left, plate, 0, left + 3.4f, plate + 0.85f, 0.03f, 0xB4B4AC);
        pen.text(left + 0.15f, plate + 0.42f, 0.035f, 0.28f, Unit.BRAND + "  " + unit.model, 0x202020, false, LEFT, 3.1f);
        pen.text(left + 0.15f, plate + 0.1f, 0.035f, 0.17f, "AC 100V 50/60Hz", 0x404040, false, LEFT, 3.1f);
        for (Control control : Control.values()) {
            if (control.unit != unit || !control.rear()) {
                continue;
            }
            float middle = control.centerY();
            if (control.kind == Control.Kind.TERMINAL) {
                // A sprung clip for each of a speaker cable's two wires.
                pen.box(control.left, control.bottom, 0, control.right, control.top, 0.25f, 0x1A1A1C);
                pen.box(control.left + 0.12f, middle + 0.06f, 0.25f, control.right - 0.12f, control.top - 0.12f, 0.5f, 0xC23A32);
                pen.box(control.left + 0.12f, control.bottom + 0.12f, 0.25f, control.right - 0.12f, middle - 0.06f, 0.5f, 0x101012);
                pen.text(control.centerX(), control.top + 0.1f, 0.01f, 0.24f, control == Control.SPEAKER_LEFT ? "L" : "R",
                        LEGEND, false, MIDDLE, 1);
            } else if (control.cable == Cable.LINE) {
                // A phono socket for each channel, white over red.
                boolean wired = stack.wired(Cable.LINE);
                pen.box(control.left + 0.2f, middle + 0.1f, 0, control.right - 0.2f, control.top - 0.05f, wired ? 0.75f : 0.2f, 0xE4E4DC);
                pen.box(control.left + 0.2f, control.bottom + 0.05f, 0, control.right - 0.2f, middle - 0.1f, wired ? 0.75f : 0.2f, 0xC23A32);
                pen.text(control.centerX(), control.top + 0.08f, 0.01f, 0.18f, unit == Unit.AMPLIFIER ? "DISC IN" : "LINE OUT",
                        LEGEND, false, MIDDLE, 1.9f);
            } else if (control.cable != null) {
                // A flat cable's socket, in its cable's colour.
                pen.box(control.left, control.bottom, 0, control.right, control.top, 0.12f, control.cable.color);
                pen.glow(control.left + 0.15f, control.bottom + 0.14f, control.right - 0.15f, control.top - 0.14f, 0.125f, 0x050506);
                if (stack.wired(control.cable)) {
                    pen.box(control.left + 0.1f, control.bottom + 0.08f, 0.12f, control.right - 0.1f, control.top - 0.08f, 0.7f,
                            control.cable.color);
                }
            }
        }
        if (unit == Unit.AMPLIFIER) {
            pen.text(10.55f, 3.1f, 0.01f, 0.16f, "SPEAKERS", FAINT, false, MIDDLE, 2);
            pen.text(5.85f, 1.3f, 0.01f, 0.16f, "SYSTEM CONTROL", FAINT, false, MIDDLE, 6);
        }
    }

    /** The cables between the stack's units, hanging behind it one outside the other. */
    private static void cables(Pen pen, HifiStackBlock.Entity stack) {
        List<Unit> units = stack.units();
        int amplifier = units.indexOf(Unit.AMPLIFIER);
        if (amplifier < 0) {
            return;
        }
        for (Cable cable : Cable.values()) {
            int unit = units.indexOf(cable.unit);
            if (!stack.wired(cable) || unit < 0) {
                continue;
            }
            Control from = Control.socket(cable, false);
            Control to = Control.socket(cable, true);
            float fromY = HifiLayout.bottom(units, unit) + from.centerY();
            float toY = HifiLayout.bottom(units, amplifier) + to.centerY();
            float out = 1 + cable.ordinal() * 0.3f;
            if (cable == Cable.LINE) {
                float apart = (from.top - from.bottom) / 4;
                pen.ribbon(from.centerX(), fromY + apart, to.centerX(), toY + apart, 0.75f, out, 0.16f, 0xE4E4DC);
                pen.ribbon(from.centerX(), fromY - apart, to.centerX(), toY - apart, 0.75f, out + 0.12f, 0.16f, 0xC23A32);
            } else {
                pen.ribbon(from.centerX(), fromY, to.centerX(), toY, 0.7f, out, 1, 0x86868C);
            }
        }
    }

    /**
     * Draws in a face's space (sixteenths: x across it, y up, z out of it) as {@link #at} has it: lit metal, lamps and
     * glass that glow whatever the light, and text.
     */
    private static final class Pen {
        private final MultiBufferSource buffers;
        private final int light;
        private final boolean text;
        private final VertexConsumer metal;
        private final VertexConsumer ink;
        private final Vector3f normal = new Vector3f();
        Matrix4f at;

        Pen(MultiBufferSource buffers, int light, boolean text) {
            this.buffers = buffers;
            this.light = light;
            this.text = text;
            metal = new QuadOutput(buffers, METAL);
            ink = new QuadOutput(buffers, QuadOutput.FLAT);
        }

        /** A lit quad through four corners, facing ({@code nx}, {@code ny}, {@code nz}). */
        private void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                          float x3, float y3, float z3, float nx, float ny, float nz, int color) {
            at.transformDirection(nx, ny, nz, normal).normalize();
            vertex(x0, y0, z0, 0, 1, color);
            vertex(x1, y1, z1, 1, 1, color);
            vertex(x2, y2, z2, 1, 0, color);
            vertex(x3, y3, z3, 0, 0, color);
        }

        private void vertex(float x, float y, float z, float u, float v, int color) {
            metal.addVertex(at, x, y, z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255).setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(normal.x, normal.y, normal.z);
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, color);
            quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, color);
            quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, color);
            quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, color);
            quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, color);
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, color);
        }

        /** A flat rectangle on the face, {@code z} out of it, as bright as it is whatever the light. */
        void glow(float x0, float y0, float x1, float y1, float z, int color) {
            int r = color >> 16 & 255;
            int g = color >> 8 & 255;
            int b = color & 255;
            ink.addVertex(at, x0, y0, z).setColor(r, g, b, 255);
            ink.addVertex(at, x1, y0, z).setColor(r, g, b, 255);
            ink.addVertex(at, x1, y1, z).setColor(r, g, b, 255);
            ink.addVertex(at, x0, y1, z).setColor(r, g, b, 255);
        }

        /** {@code line} standing on {@code y}, {@code height} tall and {@code width} wide at most; only from near. */
        void text(float x, float y, float z, float height, String line, int color, boolean bright, int align, float width) {
            if (!text) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int wide = Math.max(1, font.width(line));
            float scale = Math.min(height / 7, width / wide);
            Matrix4f pose = new Matrix4f(at).translate(x - (align == LEFT ? 0 : align == MIDDLE ? wide * scale / 2 : wide * scale),
                    y + 7 * scale, z).scale(scale, -scale, scale);
            font.drawInBatch(line, 0, 0, color | 0xFF000000, false, pose, buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
                    bright ? LightTexture.FULL_BRIGHT : light);
        }

        /** A round knob filling {@code control}'s place, {@code depth} proud of the face, turned to {@code value} (0 to 1). */
        void knob(Control control, float depth, float value) {
            float radius = (control.right - control.left) / 2;
            Matrix4f face = at;
            float side = radius * 0.71f;
            for (int turn = 0; turn < 4; turn++) {
                at = new Matrix4f(face).translate(control.centerX(), control.centerY(), 0)
                        .rotateZ((float) Math.toRadians(-(value - 0.5) * 270 + turn * 22.5));
                box(-side, -side, 0.06f, side, side, depth, KNOB);
            }
            at = new Matrix4f(face).translate(control.centerX(), control.centerY(), 0)
                    .rotateZ((float) Math.toRadians(-(value - 0.5) * 270));
            glow(-radius * 0.07f, radius * 0.4f, radius * 0.07f, radius * 0.92f, depth + 0.01f, 0xF2F2F2);
            at = face;
        }

        /** A disc lying flat, its middle at ({@code x}, {@code y}, {@code z}). */
        void disc(float x, float y, float z, float radius, int color) {
            Matrix4f face = at;
            float side = radius * 0.71f;
            for (int turn = 0; turn < 6; turn++) {
                at = new Matrix4f(face).translate(x, y, z).rotateY((float) Math.toRadians(turn * 15));
                box(-side, 0, -side, side, 0.07f, side, color);
            }
            at = new Matrix4f(face).translate(x, y, z);
            box(-0.35f, 0, -0.35f, 0.35f, 0.08f, 0.35f, 0x202024);
            at = face;
        }

        /** A tape reel's six teeth, turned {@code degrees}. */
        void reel(float x, float y, float z, float radius, float degrees) {
            Matrix4f face = at;
            for (int tooth = 0; tooth < 3; tooth++) {
                at = new Matrix4f(face).translate(x, y, 0).rotateZ((float) Math.toRadians(-degrees + tooth * 60));
                glow(-0.035f, -radius, 0.035f, radius, z, 0xEDEDED);
            }
            at = face;
        }

        /** A frame round {@code control}, {@code z} out of the face. */
        void outline(Control control, float z) {
            float edge = 0.06f;
            glow(control.left - edge, control.bottom - edge, control.right + edge, control.bottom, z, HOVER);
            glow(control.left - edge, control.top, control.right + edge, control.top + edge, z, HOVER);
            glow(control.left - edge, control.bottom, control.left, control.top, z, HOVER);
            glow(control.right, control.bottom, control.right + edge, control.top, z, HOVER);
        }

        /**
         * A flat cable {@code width} wide from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) on the face: out of
         * its plug ({@code from} out) to {@code out}, across, and back in.
         */
        void ribbon(float x0, float y0, float x1, float y1, float from, float out, float width, int color) {
            float half = width / 2;
            quad(x0 - half, y0, from, x0 + half, y0, from, x0 + half, y0, out, x0 - half, y0, out, 0, 1, 0, color);
            quad(x0 - half, y0, out, x0 + half, y0, out, x1 + half, y1, out, x1 - half, y1, out, 0, 0, 1, color);
            quad(x1 - half, y1, out, x1 + half, y1, out, x1 + half, y1, from, x1 - half, y1, from, 0, 1, 0, color);
        }
    }

    /** A unit's item: the unit alone, in the middle of its block, its front where a block's is. */
    private static final class UnitItems extends BlockEntityWithoutLevelRenderer {
        private final Map<Unit, HifiStackBlock.Entity> stacks = new EnumMap<>(Unit.class);
        private final Meters still = new Meters();

        UnitItems() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack item, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                                 int light, int overlay) {
            if (!(item.getItem() instanceof HifiUnitItem unitItem)) {
                return;
            }
            HifiStackBlock.Entity stack = stacks.computeIfAbsent(unitItem.unit, unit -> {
                HifiStackBlock.Entity alone = new HifiStackBlock.Entity(BlockPos.ZERO,
                        HifiFeature.HIFI_STACK.get().defaultBlockState());
                alone.units().add(unit);
                return alone;
            });
            poseStack.pushPose();
            poseStack.translate(0, (Unit.ROOM - unitItem.unit.height) / 32, 0);
            draw(stack, Direction.NORTH, 0, poseStack, buffers, light, false, still);
            poseStack.popPose();
        }
    }

    /** A hi-fi speaker's cable, hanging from its back to its amplifier's terminal. */
    private static final class SpeakerCable implements BlockEntityRenderer<HifiSpeakerBlock.Entity> {
        private static final int SEGMENTS = 14;
        private static final float HALF = 0.012f;

        @Nullable
        private static Vec3 terminal(HifiSpeakerBlock.Entity speaker) {
            Optional<BlockPos> source = speaker.source();
            if (source.isEmpty() || speaker.getLevel() == null
                    || !(speaker.getLevel().getBlockEntity(source.get()) instanceof HifiStackBlock.Entity stack)) {
                return null;
            }
            int amplifier = stack.units().indexOf(Unit.AMPLIFIER);
            BlockPos offset = source.get().subtract(speaker.getBlockPos());
            if (amplifier < 0 || offset.distSqr(BlockPos.ZERO) > (HifiCableItem.MAX_LENGTH + 2) * (HifiCableItem.MAX_LENGTH + 2)) {
                return null;
            }
            Control control = speaker.channel() == Streams.Channel.RIGHT ? Control.SPEAKER_RIGHT : Control.SPEAKER_LEFT;
            return HifiLayout.onFace(stack.getBlockState().getValue(HifiStackBlock.FACING), true, control.centerX(),
                    HifiLayout.bottom(stack.units(), amplifier) + control.bottom + 0.35, 0.5).add(Vec3.atLowerCornerOf(offset));
        }

        @Override
        public void render(HifiSpeakerBlock.Entity speaker, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                           int light, int overlay) {
            Vec3 to = terminal(speaker);
            if (to == null) {
                return;
            }
            Vec3 from = HifiLayout.toBlock(speaker.getBlockState().getValue(HifiSpeakerBlock.FACING), 8, 4, 3.9);
            double sag = Math.min(0.5, 0.1 + from.distanceTo(to) * 0.06);
            Matrix4f pose = poseStack.last().pose();
            VertexConsumer metal = buffers.getBuffer(METAL);
            Vec3 last = from;
            for (int segment = 1; segment <= SEGMENTS; segment++) {
                double along = (double) segment / SEGMENTS;
                Vec3 next = from.lerp(to, along).add(0, -sag * 4 * along * (1 - along), 0);
                Vec3 run = next.subtract(last).normalize();
                Vec3 across = run.cross(Math.abs(run.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(HALF);
                Vec3 up = run.cross(across).normalize().scale(HALF);
                strip(metal, pose, last, next, across, up, light);
                strip(metal, pose, last, next, up, across, light);
                last = next;
            }
        }

        /** A flat strip from {@code a} to {@code b}, {@code across} wide either way, facing {@code facing}. */
        private static void strip(VertexConsumer out, Matrix4f pose, Vec3 a, Vec3 b, Vec3 across, Vec3 facing, int light) {
            Vector3f normal = pose.transformDirection((float) facing.x, (float) facing.y, (float) facing.z, new Vector3f()).normalize();
            Vec3[] corners = {a.subtract(across), a.add(across), b.add(across), b.subtract(across)};
            for (int i = 0; i < 4; i++) {
                out.addVertex(pose, (float) corners[i].x, (float) corners[i].y, (float) corners[i].z)
                        .setColor(0x30, 0x30, 0x34, 255).setUv(i == 0 || i == 3 ? 0 : 1, i < 2 ? 1 : 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(normal.x, normal.y, normal.z);
            }
        }

        @Override
        public boolean shouldRenderOffScreen(HifiSpeakerBlock.Entity speaker) {
            return true;
        }

        @Override
        public AABB getRenderBoundingBox(HifiSpeakerBlock.Entity speaker) {
            AABB box = new AABB(speaker.getBlockPos());
            return speaker.source().map(source -> box.minmax(new AABB(source))).orElse(box);
        }
    }
}
