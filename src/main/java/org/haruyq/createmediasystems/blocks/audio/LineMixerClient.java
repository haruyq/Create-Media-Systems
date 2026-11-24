package org.haruyq.createmediasystems.blocks.audio;

import static org.haruyq.createmediasystems.blocks.audio.LineMixerBlock.EXTENSION_CHANNELS;
import static org.haruyq.createmediasystems.blocks.audio.LineMixerBlock.HEIGHT;
import static org.haruyq.createmediasystems.blocks.audio.LineMixerBlock.MASTER;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.ControlDrag;
import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.client.QuadOutput;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.playback.Spectrum;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A line mixer on a client: its body and panel drawn (both blocks, from the left one), its faders held and dragged,
 * and its level meters, which show what each channel and the master pass on of what plays here. Everything is in the
 * panel's sixteenths (see LineMixerBlock#place): x across from the left, z from the back edge to the front. The
 * rightmost mixer of a row has its channels' strips and the master's section; an extension has strips alone.
 */
public final class LineMixerClient implements BlockEntityRenderer<LineMixerBlock.Entity> {
    /** How near (blocks) the camera is for the lettering and the scales to be drawn. */
    private static final double TEXT_RANGE = 10;
    /** The channels' strips, side by side: where the first begins, with a master to its right and on an extension. */
    private static final float STRIPS_LEFT = 1, EXTENSION_LEFT = 2, STRIP = 4;
    private static final float MASTER_LEFT = 22, MASTER_RIGHT = 31, MASTER_FADER = 28.5f;
    /** Where a fader's knob is at its lowest (towards the front) and at its highest. */
    private static final float LOW = 13, HIGH = 5;
    /** A level meter's lamps, from the lowest: so many, each so long, one every so far. */
    private static final int LAMPS = 10;
    private static final float LAMP_LENGTH = 0.6f, LAMP_PITCH = (LOW - HIGH) / LAMPS;
    private static final int BODY = 0x26282D, PANEL = 0x3D4149, PRINT = 0xE4E4E0, SLOT = 0x0A0A0B, KNOB = 0x151517;
    private static final int UNLIT = 0x1B2420, GREEN = 0x4CF070, AMBER = 0xFFC83C, RED = 0xFF4032;

    private static final Map<LineMixerBlock.Entity, Meters> METERS = new WeakHashMap<>();
    private static final Meters STILL = new Meters();

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                AudioFeature.LINE_MIXER_ENTITY.get(), context -> new LineMixerClient()));
        modBus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, () -> {
            Minecraft mc = Minecraft.getInstance();
            return new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                @Override
                public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                         MultiBufferSource buffers, int light, int overlay) {
                    // Its two blocks about the middle of the item's one.
                    pose.pushPose();
                    pose.translate(-0.5, 0, 0);
                    draw(pose, buffers, light, Direction.SOUTH, null, false, 0, STILL, false);
                    pose.popPose();
                }
            };
        }, AudioFeature.LINE_MIXER_ITEM.get()));
        ControlDrag.onUse(LineMixerClient::grab);
    }

    /** Where fader {@code fader}'s slot is across the panel of a mixer: an {@code extension}, or the one with the master. */
    private static float across(boolean extension, int fader) {
        return fader == MASTER ? MASTER_FADER : (extension ? EXTENSION_LEFT : STRIPS_LEFT) + STRIP * fader + 1.5f;
    }

    /**
     * Where a look crosses the top of the mixer at {@code pos} (its left block), on its panel; null if it doesn't come
     * down onto it from above. From the look, not the hit, so it holds on a moving, turning sub-level too.
     */
    @Nullable
    private static Vec3 aim(BlockPos pos, Direction facing, ControlDrag.Look look) {
        // Where the knobs are held: half way up them.
        double top = pos.getY() + (HEIGHT + 0.35) / 16;
        Vec3 eye = look.eye(), along = look.along();
        if (eye.y <= top || along.y >= 0) {
            return null;
        }
        Vec3 at = eye.add(along.scale((eye.y - top) / -along.y));
        return LineMixerBlock.panel(facing, at.subtract(Vec3.atLowerCornerOf(pos)));
    }

    /** The channel whose strip this client's player points at on the mixer at {@code mixer}; -1 for none. */
    static int channelAimedAt(BlockPos mixer) {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = mc.level.getBlockState(mixer);
        if (!state.is(AudioFeature.LINE_MIXER.get())) {
            return -1;
        }
        boolean extension = LineMixerBlock.unitsToTheRight(mc.level, mixer, state) > 0;
        Vec3 at = aim(mixer, state.getValue(LineMixerBlock.FACING), ControlDrag.look(mixer));
        int channel = at == null ? -1 : Mth.floor((at.x - across(extension, 0) + 1.5f) / STRIP);
        return channel >= 0 && channel < LineMixerBlock.channels(extension) && at.z >= 0 && at.z <= 16 ? channel : -1;
    }

    private static boolean grab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !ControlDrag.works(mc.player)
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        if (!state.is(AudioFeature.LINE_MIXER.get())) {
            return false;
        }
        BlockPos pos = LineMixerBlock.root(hit.getBlockPos(), state);
        if (!(mc.level.getBlockEntity(pos) instanceof LineMixerBlock.Entity mixer)) {
            return false;
        }
        Vec3 at = aim(pos, state.getValue(LineMixerBlock.FACING), ControlDrag.look(pos));
        if (at == null || at.z < HIGH - 1.2 || at.z > LOW + 1.2) {
            return false;
        }
        boolean extension = mixer.extension();
        for (int fader = 0; fader <= MASTER; fader++) {
            boolean has = fader == MASTER ? !extension : fader < LineMixerBlock.channels(extension);
            if (has && Math.abs(at.x - across(extension, fader)) <= 1.3) {
                String label = fader == MASTER ? "MASTER" : "CH" + (mixer.firstChannel() + fader + 1);
                ControlDrag.hold(new Fader(pos.immutable(), fader, label, mixer.fader(fader)));
                return true;
            }
        }
        return false;
    }

    /** A fader this client's player holds: where they have it, which its mixer is told as it moves. */
    private static final class Fader implements ControlDrag.Held {
        private final BlockPos pos;
        private final int fader;
        private final String label;
        private float position;

        Fader(BlockPos pos, int fader, String label, float position) {
            this.pos = pos;
            this.fader = fader;
            this.label = label;
            this.position = position;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }

        @Override
        public void drag(ControlDrag.Look look) {
            Minecraft mc = Minecraft.getInstance();
            BlockState state = mc.level.getBlockState(pos);
            if (!state.is(AudioFeature.LINE_MIXER.get())) {
                return;
            }
            Vec3 at = aim(pos, state.getValue(LineMixerBlock.FACING), look);
            float moved = at == null ? position
                    : Math.round(Mth.clamp((LOW - at.z) / (LOW - HIGH), 0, 1) * LineMixerBlock.STEPS) / (float) LineMixerBlock.STEPS;
            if (moved != position) {
                position = moved;
                // ponytail: one packet a step as it is dragged, 20 a second at most; send every few ticks if that is too many.
                PacketDistributor.sendToServer(new LineMixerBlock.SetFader(pos, fader, position));
            }
            mc.player.displayClientMessage(Component.literal(label + "  " + LineMixerBlock.percent(position)), true);
        }

        @Override
        public void release(boolean gone) {
        }
    }

    /** Where fader {@code fader} of {@code mixer} is drawn: where this player holds it, if they do, a tick ahead of the server. */
    private static float position(LineMixerBlock.Entity mixer, int fader) {
        return ControlDrag.held() instanceof Fader held && held.fader == fader && held.pos.equals(mixer.getBlockPos())
                ? held.position : mixer.fader(fader);
    }

    /** What a mixer's meters show (see Spectrum#level), falling back slowly from what they last did. */
    private static final class Meters {
        final float[] channels = new float[EXTENSION_CHANNELS];
        /** Whether each channel's source plays here. */
        final boolean[] playing = new boolean[EXTENSION_CHANNELS];
        /** The master's, of its whole row. */
        float left, right;
        private long at;

        Meters update(LineMixerBlock.Entity mixer, boolean extension) {
            long now = Util.getMillis();
            float fall = Math.min(1, (now - at) / 1000f) * 1.6f;
            at = now;
            for (int channel = 0; channel < LineMixerBlock.channels(extension); channel++) {
                double[] heard = heard(mixer, channel);
                playing[channel] = heard != null;
                channels[channel] = Math.max(heard == null ? 0 : Spectrum.level(Math.max(heard[0], heard[1])),
                        channels[channel] - fall);
            }
            if (!extension) {
                double master = LineMixerBlock.gain(position(mixer, MASTER)), leftPower = 0, rightPower = 0;
                List<LineMixerBlock.Entity> row = mixer.row();
                for (int unit = 0; unit < row.size(); unit++) {
                    for (int channel = 0; channel < LineMixerBlock.channels(unit < row.size() - 1); channel++) {
                        double[] heard = heard(row.get(unit), channel);
                        leftPower += heard == null ? 0 : heard[0] * heard[0];
                        rightPower += heard == null ? 0 : heard[1] * heard[1];
                    }
                }
                left = Math.max(Spectrum.level(Math.sqrt(leftPower) * master), left - fall);
                right = Math.max(Spectrum.level(Math.sqrt(rightPower) * master), right - fall);
            }
            return this;
        }

        /** How loud {@code channel} of {@code mixer} passes each side of its source on (1 is full scale); null if it doesn't play here. */
        @Nullable
        private static double[] heard(LineMixerBlock.Entity mixer, int channel) {
            BlockPos source = mixer.input(channel);
            Spectrum.Frame heard = source == null ? null : Spectrum.heard(source);
            if (heard == null) {
                return null;
            }
            double gain = LineMixerBlock.gain(position(mixer, channel));
            return new double[]{Spectrum.amplitude(heard.left()) * gain, Spectrum.amplitude(heard.right()) * gain};
        }
    }

    @Override
    public void render(LineMixerBlock.Entity mixer, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean near = camera.distanceToSqr(SableCompat.toRenderWorld(mixer.getLevel(), mixer.getBlockPos(),
                Vec3.atCenterOf(mixer.getBlockPos()))) < TEXT_RANGE * TEXT_RANGE;
        boolean extension = mixer.extension();
        draw(pose, buffers, light, mixer.getBlockState().getValue(LineMixerBlock.FACING), mixer, extension,
                mixer.firstChannel(), METERS.computeIfAbsent(mixer, key -> new Meters()).update(mixer, extension), near);
    }

    /** Both its blocks: the right one is drawn from here too. */
    @Override
    public AABB getRenderBoundingBox(LineMixerBlock.Entity mixer) {
        BlockPos pos = mixer.getBlockPos();
        return new AABB(pos).minmax(new AABB(pos.relative(LineMixerBlock.right(mixer.getBlockState()))));
    }

    /**
     * The mixer in its left block at the pose's origin, its front towards {@code facing}: an {@code extension} or the
     * one with the master, its channels numbered from {@code first} (from 0), its faders where {@code mixer} has them
     * (all down for null, an item), its meters as {@code meters} has them.
     */
    private static void draw(PoseStack pose, MultiBufferSource buffers, int light, Direction facing,
                             @Nullable LineMixerBlock.Entity mixer, boolean extension, int first, Meters meters, boolean text) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        pose.translate(-8, 0, -8);
        VertexConsumer solid = new QuadOutput(buffers, Mesh.solid());
        VertexConsumer lit = new QuadOutput(buffers, QuadOutput.FLAT);

        for (float x : new float[]{1, 29.5f}) {
            Mesh.box(pose, solid, x, 0, 1, x + 1.5f, 0.3f, 2.2f, 0x0C0C0D, light);
            Mesh.box(pose, solid, x, 0, 13.8f, x + 1.5f, 0.3f, 15, 0x0C0C0D, light);
        }
        Mesh.box(pose, solid, 0.3f, 0.3f, 0.3f, 31.7f, HEIGHT - 0.4f, 15.7f, BODY, light);
        Mesh.box(pose, solid, 0, HEIGHT - 0.4f, 0, 32, HEIGHT, 16, PANEL, light);

        int channels = LineMixerBlock.channels(extension);
        for (int channel = 0; channel < channels; channel++) {
            float x = across(extension, channel), left = x - 1.5f;
            frame(pose, solid, left + 0.2f, 0.7f, left + STRIP - 0.2f, 15.3f, light);
            BlockPos source = mixer == null ? null : mixer.input(channel);
            Mesh.glow(pose, lit, x - 0.3f, 3, x + 0.3f, 3.6f, HEIGHT + 0.01f,
                    source == null ? UNLIT : meters.playing[channel] ? GREEN : AMBER);
            meter(pose, lit, x + 1.3f, x + 1.9f, meters.channels[channel]);
            fader(pose, solid, x, mixer == null ? 0 : position(mixer, channel), PRINT, text, light);
            if (text) {
                write(pose, buffers, String.valueOf(first + channel + 1), x, 1.3f, 1.1f, light);
            }
        }
        if (!extension) {
            frame(pose, solid, MASTER_LEFT + 0.2f, 0.7f, MASTER_RIGHT - 0.2f, 15.3f, light);
            meter(pose, lit, MASTER_LEFT + 1.5f, MASTER_LEFT + 2.2f, meters.left);
            meter(pose, lit, MASTER_LEFT + 2.6f, MASTER_LEFT + 3.3f, meters.right);
            fader(pose, solid, MASTER_FADER, mixer == null ? 0 : position(mixer, MASTER), RED, text, light);
            if (text) {
                write(pose, buffers, "MASTER", (MASTER_LEFT + MASTER_RIGHT) / 2, 1.3f, 1.1f, light);
                write(pose, buffers, "L", MASTER_LEFT + 1.85f, 3.2f, 0.9f, light);
                write(pose, buffers, "R", MASTER_LEFT + 2.95f, 3.2f, 0.9f, light);
            }
        }
        pose.popPose();
    }

    /** A fader across the panel at {@code x}: its slot, its scale (from near), and its knob at {@code position} (0 to 1). */
    private static void fader(PoseStack pose, VertexConsumer solid, float x, float position, int line, boolean scale, int light) {
        flat(pose, solid, x - 0.15f, HIGH - 0.6f, x + 0.15f, LOW + 0.6f, HEIGHT + 0.004f, SLOT, light);
        if (scale) {
            for (int mark = 0; mark <= 10; mark++) {
                float z = LOW - mark * (LOW - HIGH) / 10;
                flat(pose, solid, x - (mark % 5 == 0 ? 1.15f : 0.95f), z - 0.03f, x - 0.45f, z + 0.03f, HEIGHT + 0.004f,
                        PRINT, light);
            }
        }
        float z = LOW - position * (LOW - HIGH);
        Mesh.box(pose, solid, x - 0.8f, HEIGHT, z - 0.55f, x + 0.8f, HEIGHT + 0.7f, z + 0.55f, KNOB, light);
        flat(pose, solid, x - 0.8f, z - 0.07f, x + 0.8f, z + 0.07f, HEIGHT + 0.704f, line, light);
    }

    /** A meter's lamps between {@code x0} and {@code x1}, the lowest at the front: lit up to {@code level} (0 to 1). */
    private static void meter(PoseStack pose, VertexConsumer lit, float x0, float x1, float level) {
        int shown = Math.round(level * LAMPS);
        for (int lamp = 0; lamp < LAMPS; lamp++) {
            float front = LOW - lamp * LAMP_PITCH;
            int color = lamp >= shown ? UNLIT : lamp >= LAMPS - 2 ? RED : lamp >= LAMPS - 4 ? AMBER : GREEN;
            Mesh.glow(pose, lit, x0, front - LAMP_LENGTH, x1, front, HEIGHT + 0.01f, color);
        }
    }

    /** The printed outline of a section of the panel. */
    private static void frame(PoseStack pose, VertexConsumer solid, float x0, float z0, float x1, float z1, int light) {
        float line = 0.07f, y = HEIGHT + 0.003f;
        flat(pose, solid, x0, z0, x1, z0 + line, y, PRINT, light);
        flat(pose, solid, x0, z1 - line, x1, z1, y, PRINT, light);
        flat(pose, solid, x0, z0, x0 + line, z1, y, PRINT, light);
        flat(pose, solid, x1 - line, z0, x1, z1, y, PRINT, light);
    }

    /** A flat rectangle lying {@code y} high, lit as the panel is: what is printed on it. */
    private static void flat(PoseStack pose, VertexConsumer solid, float x0, float z0, float x1, float z1, float y,
                             int color, int light) {
        Mesh.quad(pose, solid, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, 0, 1, 0, color, light);
    }

    /** {@code line} lying on the panel, read from the front: its top edge at {@code z}, centred on {@code x}, {@code height} tall. */
    private static void write(PoseStack pose, MultiBufferSource buffers, String line, float x, float z, float height,
                              int light) {
        Font font = Minecraft.getInstance().font;
        float scale = height / 8;
        pose.pushPose();
        pose.translate(x - font.width(line) * scale / 2, HEIGHT + 0.005f, z);
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.scale(scale, -scale, scale);
        font.drawInBatch(line, 0, 0, 0xFF000000 | PRINT, false, pose.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }
}
