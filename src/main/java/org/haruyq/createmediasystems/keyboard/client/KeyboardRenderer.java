package org.haruyq.createmediasystems.keyboard.client;

import static org.haruyq.createmediasystems.keyboard.Layout.DEPTH;
import static org.haruyq.createmediasystems.keyboard.Layout.HEIGHT;
import static org.haruyq.createmediasystems.keyboard.Layout.KEYS_BACK;
import static org.haruyq.createmediasystems.keyboard.Layout.KEYS_FRONT;
import static org.haruyq.createmediasystems.keyboard.Layout.KEYS_LEFT;
import static org.haruyq.createmediasystems.keyboard.Layout.KEYS_RIGHT;
import static org.haruyq.createmediasystems.keyboard.Layout.WIDTH;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.QuadOutput;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.keyboard.KeyboardFeature;
import org.haruyq.createmediasystems.keyboard.Layout;
import org.haruyq.createmediasystems.keyboard.Layout.Control;
import org.haruyq.createmediasystems.keyboard.MidiKeyboardBlock;
import org.haruyq.createmediasystems.keyboard.Panel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Draws the keyboard: its body, its 88 keys (those held pressed down), and its pod with every pad, knob, button and
 * light as its panel has them. Everything is in the {@link Layout}'s sixteenths, the body turned to its facing first.
 */
public final class KeyboardRenderer implements BlockEntityRenderer<MidiKeyboardBlock.Entity> {
    /** How near (blocks) the camera is for the lettering to be drawn. */
    private static final double TEXT_RANGE = 10;
    private static final float DECK_BOTTOM = 2.4f, WHITE_TOP = 3.05f, BLACK_TOP = 3.5f;
    private static final float WHITE = (KEYS_RIGHT - KEYS_LEFT) / Layout.WHITE_KEYS;
    private static final int UNLIT = 0x2B2B30, LAMP = 0xFFE7B8;
    private static final Panel NEW = new Panel();

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                KeyboardFeature.KEYBOARD_ENTITY.get(), context -> new KeyboardRenderer()));
        modBus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    Minecraft mc = Minecraft.getInstance();
                    renderer = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                        @Override
                        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                                 MultiBufferSource buffers, int light, int overlay) {
                            draw(pose, buffers, light, Direction.SOUTH, new PanelLook(NEW, 0, null), 0, 0, 8192, 0, false);
                        }
                    };
                }
                return renderer;
            }
        }, KeyboardFeature.KEYBOARD_ITEM.get()));
    }

    @Override
    public void render(MidiKeyboardBlock.Entity keyboard, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean near = camera.distanceToSqr(SableCompat.toRenderWorld(keyboard.getLevel(), keyboard.getBlockPos(),
                Vec3.atCenterOf(keyboard.getBlockPos()))) < TEXT_RANGE * TEXT_RANGE;
        draw(pose, buffers, light, keyboard.facing(), KeyboardClient.look(keyboard), keyboard.keysDown.get(0),
                keyboard.keysDown.get(1), keyboard.bend, keyboard.modulation, near);
    }

    @Override
    public AABB getRenderBoundingBox(MidiKeyboardBlock.Entity keyboard) {
        return MidiKeyboardBlock.body(keyboard.getBlockPos(), keyboard.facing()).inflate(0.1);
    }

    /**
     * The keyboard in its block at the pose's origin: with the keys of {@code keysLow} and {@code keysHigh} (bits by
     * note) down, the lever at {@code bend} (0..16383) and {@code modulation} (0..127).
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, int light, Direction facing, PanelLook look,
                     long keysLow, long keysHigh, int bend, int modulation, boolean text) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        pose.translate(-WIDTH / 2, 0, -DEPTH / 2);
        VertexConsumer solid = new QuadOutput(buffers, Mesh.solid());
        VertexConsumer lit = new QuadOutput(buffers, QuadOutput.FLAT);

        for (float x : new float[]{1, WIDTH - 2.5f}) {
            Mesh.box(pose, solid, x, 0, 1, x + 1.5f, 0.3f, 2.2f, 0x0C0C0D, light);
            Mesh.box(pose, solid, x, 0, DEPTH - 2.2f, x + 1.5f, 0.3f, DEPTH - 1, 0x0C0C0D, light);
        }
        Mesh.box(pose, solid, 0, 0.3f, 0, WIDTH, DECK_BOTTOM, DEPTH, PanelLook.BODY, light);
        Mesh.box(pose, solid, 0, DECK_BOTTOM, 0, WIDTH, HEIGHT, KEYS_BACK, PanelLook.DECK, light);
        Mesh.box(pose, solid, 0, DECK_BOTTOM, KEYS_BACK, KEYS_LEFT, HEIGHT, DEPTH, PanelLook.DECK, light);
        Mesh.box(pose, solid, KEYS_RIGHT, DECK_BOTTOM, KEYS_BACK, WIDTH, HEIGHT, DEPTH, PanelLook.DECK, light);
        Mesh.box(pose, solid, KEYS_LEFT, DECK_BOTTOM, KEYS_FRONT, KEYS_RIGHT, 2.75f, DEPTH, PanelLook.BODY, light);
        Mesh.box(pose, solid, KEYS_LEFT, DECK_BOTTOM, KEYS_BACK, KEYS_RIGHT, WHITE_TOP + 0.08f, KEYS_BACK + 0.12f, PanelLook.FELT, light);

        float length = KEYS_FRONT - 0.05f - (KEYS_BACK + 0.12f);
        for (int note = Layout.LOWEST; note <= Layout.HIGHEST; note++) {
            boolean black = Layout.black(note);
            boolean down = ((note < 64 ? keysLow : keysHigh) >> note & 1) != 0;
            float left = KEYS_LEFT + Layout.left(note) * WHITE + (black ? 0 : 0.04f);
            float right = left + Layout.width(note) * WHITE - (black ? 0 : 0.08f);
            float top = black ? BLACK_TOP : WHITE_TOP, bottom = black ? WHITE_TOP - 0.2f : DECK_BOTTOM + 0.05f;
            float reach = black ? length * Layout.BLACK_LENGTH : length;
            // One held lies in the shade of those beside it.
            int color = PanelLook.dim(black ? PanelLook.BLACK_KEY : PanelLook.WHITE_KEY, down ? 0.82f : 1);
            // A key turns about its back edge; one held is down at its front.
            pose.pushPose();
            pose.translate(0, top, KEYS_BACK + 0.12f);
            if (down) {
                pose.mulPose(Axis.XP.rotationDegrees(black ? 4.5f : 2.8f));
            }
            Mesh.box(pose, solid, left, bottom - top, 0, right, 0, reach, color, light);
            pose.popPose();
        }

        for (Control control : Control.values()) {
            float x0 = podX(control.left), x1 = podX(control.right), z0 = podZ(control.top), z1 = podZ(control.bottom);
            switch (control.kind) {
                case BUTTON -> {
                    Mesh.box(pose, solid, x0, HEIGHT, z0, x1, HEIGHT + 0.1f, z1, PanelLook.BUTTON, light);
                    if (look.lit(control)) {
                        glow(pose, lit, x0, z0, x1, z1, HEIGHT + 0.11f, LAMP);
                    }
                }
                case PAD -> {
                    int pad = control.index();
                    Mesh.box(pose, solid, x0, HEIGHT, z0, x1, HEIGHT + 0.16f, z1, PanelLook.BUTTON, light);
                    glow(pose, lit, x0 + 0.03f, z0 + 0.03f, x1 - 0.03f, z1 - 0.03f, HEIGHT + 0.17f, !look.padGlows(pad) ? UNLIT
                            : PanelLook.dim(look.padColor(pad), look.padLit(pad) ? 1 : 0.4f));
                }
                case KNOB -> {
                    int knob = control.index();
                    float value = look.panel().knob[knob] / 127f, radius = (x1 - x0) / 2;
                    glow(pose, lit, x0 - 0.05f, z0 - 0.05f, x1 + 0.05f, z1 + 0.05f, HEIGHT + 0.01f,
                            PanelLook.dim(look.knobColor(knob), 0.35f + 0.65f * value));
                    pose.pushPose();
                    pose.translate((x0 + x1) / 2, HEIGHT, (z0 + z1) / 2);
                    pose.mulPose(Axis.YP.rotationDegrees(-(value - 0.5f) * 270));
                    float side = radius * 0.72f;
                    Mesh.box(pose, solid, -side, 0, -side, side, 0.6f, side, PanelLook.KNOB, light);
                    glow(pose, lit, -0.035f, -side, 0.035f, -side * 0.25f, 0.61f, PanelLook.WHITE);
                    pose.mulPose(Axis.YP.rotationDegrees(45));
                    Mesh.box(pose, solid, -side, 0, -side, side, 0.58f, side, PanelLook.KNOB, light);
                    pose.popPose();
                }
                case LEVER -> {
                    glow(pose, lit, x0, z0, x1, z1, HEIGHT + 0.01f, 0x0A0A0B);
                    pose.pushPose();
                    pose.translate((x0 + x1) / 2, HEIGHT - 0.2f, (z0 + z1) / 2);
                    pose.mulPose(Axis.ZP.rotationDegrees(-(bend - 8192) / 8192f * 28));
                    pose.mulPose(Axis.XP.rotationDegrees(-modulation / 127f * 28));
                    Mesh.box(pose, solid, -0.3f, 0, -0.42f, 0.3f, 1, 0.42f, 0x151517, light);
                    pose.popPose();
                }
            }
        }
        for (int light8 = 0; light8 < 8; light8++) {
            float x = podX(0.4f + light8 * 0.045f);
            int color = look.bankLight(light8);
            glow(pose, lit, x, podZ(0.048f), x + 0.12f, podZ(0.062f), HEIGHT + 0.01f, color < 0 ? UNLIT : color);
        }
        for (int curve = 0; curve < Panel.CURVES; curve++) {
            float x = podX(0.64f + curve * 0.052f);
            glow(pose, lit, x, podZ(0.79f), x + 0.12f, podZ(0.804f), HEIGHT + 0.01f,
                    look.panel().curve == curve ? PanelLook.GREEN : UNLIT);
        }
        if (text) {
            write(pose, buffers, Component.literal("Koland").withStyle(ChatFormatting.BOLD), 38.4f, 0.75f, 0.85f, light);
            write(pose, buffers, Component.literal("A-88MKII"), 43.7f, 0.98f, 0.5f, light);
        }
        pose.popPose();
    }

    private static float podX(float across) {
        return Layout.POD_LEFT + across * (Layout.POD_RIGHT - Layout.POD_LEFT);
    }

    private static float podZ(float down) {
        return Layout.POD_BACK + down * (Layout.POD_FRONT - Layout.POD_BACK);
    }

    /** A flat rectangle lying {@code y} high, as bright as it is whatever the light. */
    private static void glow(PoseStack pose, VertexConsumer lit, float x0, float z0, float x1, float z1, float y, int color) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        lit.addVertex(pose.last(), x0, y, z0).setColor(r, g, b, 255);
        lit.addVertex(pose.last(), x0, y, z1).setColor(r, g, b, 255);
        lit.addVertex(pose.last(), x1, y, z1).setColor(r, g, b, 255);
        lit.addVertex(pose.last(), x1, y, z0).setColor(r, g, b, 255);
    }

    /** {@code line} lying on the deck, read from the front: its top left corner at ({@code x}, {@code z}), {@code height} tall. */
    private static void write(PoseStack pose, MultiBufferSource buffers, Component line, float x, float z, float height,
                              int light) {
        float scale = height / 8;
        pose.pushPose();
        pose.translate(x, HEIGHT + 0.005f, z);
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.scale(scale, -scale, scale);
        Minecraft.getInstance().font.drawInBatch(line, 0, 0, 0xFF000000 | PanelLook.LEGEND, false, pose.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }
}
