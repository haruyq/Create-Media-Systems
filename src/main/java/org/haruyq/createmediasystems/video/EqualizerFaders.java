package org.haruyq.createmediasystems.video;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.hifi.HifiStackBlock;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.playback.Tone;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * An equalizer's three faders, side by side on its top and running from its front (-12 dB) to its back (+12 dB): from
 * the left as one faces it, Low, Mid and Hi (on a second equalizer BASS, Low-Mid and Hi-Mid), each knob in its band's
 * colour. Point at one, hold right-click and look along it: its knob follows the crosshair, its gain is shown, and it
 * is heard here at once and set for everyone when let go. Sneak to use the top as a block instead.
 */
public final class EqualizerFaders implements BlockEntityRenderer<EqualizerBlock.Entity> {
    private static final ModelResourceLocation KNOB = ModelResourceLocation.standalone(CreateMediaSystems.id("block/equalizer_knob"));
    /** Sixteenths between the faders. */
    private static final float SPACING = 5;
    /** The fader in the middle of the top, the others either side of it. */
    private static final int MIDDLE = (EqualizerBlock.FADERS - 1) / 2;
    /** How far a knob goes either side of the middle of the top (sixteenths). */
    private static final float TRAVEL = 4.5f;

    /** The equalizer whose fader is held, and which one. */
    @Nullable
    private static BlockPos held;
    private static int fader;

    EqualizerFaders(BlockEntityRendererProvider.Context context) {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CreateMediaSystems.EQUALIZER_ENTITY.get(), EqualizerFaders::new));
        modBus.addListener((ModelEvent.RegisterAdditional event) -> event.register(KNOB));
        // Every Blu-ray player's video playing here plays through the equalizers touching it, and what a hi-fi
        // stack plays through its own controls.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                NativeAudio.equalize(source -> level.getBlockEntity(source) instanceof HifiStackBlock.Entity stack
                        ? stack.tone()
                        : level.getBlockState(source).is(CreateMediaSystems.VIDEO_PLAYER.get())
                        ? new Tone(EqualizerBlock.bands(level, source), 1) : Tone.FLAT);
            }
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
            if (event.isUseItem() && (held != null || grab())) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> drag());
    }

    @Override
    public void render(EqualizerBlock.Entity equalizer, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        Direction back = equalizer.getBlockState().getValue(EqualizerBlock.FACING).getOpposite();
        Direction right = back.getClockWise();
        // Lit by what is above, not by the inside of the block.
        int above = LevelRenderer.getLightColor(equalizer.getLevel(), equalizer.getBlockPos().above());
        BakedModel knob = Minecraft.getInstance().getModelManager().getModel(KNOB);
        VertexConsumer consumer = buffers.getBuffer(Sheets.solidBlockSheet());
        for (int i = 0; i < EqualizerBlock.FADERS; i++) {
            float across = (i - MIDDLE) * SPACING / 16;
            float along = equalizer.gain(i) / EqualizerBlock.MAX_GAIN * TRAVEL / 16;
            int color = equalizer.band(i).color;
            poseStack.pushPose();
            poseStack.translate(right.getStepX() * across + back.getStepX() * along, 0,
                    right.getStepZ() * across + back.getStepZ() * along);
            if (back.getAxis() == Direction.Axis.X) {
                // The knob is the same turned half way round, so a quarter turn either way lays it across.
                poseStack.translate(0.5, 0, 0.5);
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
                poseStack.translate(-0.5, 0, -0.5);
            }
            Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(poseStack.last(), consumer, null,
                    knob, (color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f, above,
                    overlay, ModelData.EMPTY, null);
            poseStack.popPose();
        }
    }

    private static boolean grab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player.isShiftKeyDown() || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof EqualizerBlock.Entity equalizer)) {
            return false;
        }
        // Worked out from the look, not the hit, so it holds on a moving, turning sub-level too.
        Vec3 at = aim(equalizer);
        if (at == null || Math.abs(at.x) > 8 || Math.abs(at.z) > 8) {
            return false;
        }
        held = hit.getBlockPos();
        fader = Mth.clamp((int) Math.round(at.x / SPACING) + MIDDLE, 0, EqualizerBlock.FADERS - 1);
        return true;
    }

    /** Moves the held fader's knob to where the crosshair crosses the top; sends it once right-click is let go. */
    private static void drag() {
        Minecraft mc = Minecraft.getInstance();
        if (held == null) {
            return;
        }
        if (mc.player == null || !(mc.level.getBlockEntity(held) instanceof EqualizerBlock.Entity equalizer)) {
            held = null;
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            PacketDistributor.sendToServer(new EqualizerBlock.SetEqualizer(held, fader, equalizer.gain(fader)));
            held = null;
            return;
        }
        Vec3 at = aim(equalizer);
        if (at != null) {
            equalizer.set(fader, (float) (at.z / TRAVEL * EqualizerBlock.MAX_GAIN));
        }
        EqualizerBlock.Band band = equalizer.band(fader);
        mc.player.displayClientMessage(Component.literal(band.label + "  " + EqualizerBlock.db(equalizer.gain(fader)))
                .withColor(band.color), true);
    }

    @Nullable
    private static Vec3 aim(EqualizerBlock.Entity equalizer) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = EqualizerBlock.aim(mc.level, equalizer.getBlockPos(), mc.player.getEyePosition(),
                mc.player.getViewVector(1));
        return at == null ? null : onTop(equalizer, at);
    }

    /**
     * Where the block-space point {@code at} is over the equalizer's top: x sixteenths right of its middle as one
     * faces it, z behind.
     */
    private static Vec3 onTop(EqualizerBlock.Entity equalizer, Vec3 at) {
        Direction back = equalizer.getBlockState().getValue(EqualizerBlock.FACING).getOpposite();
        Direction right = back.getClockWise();
        Vec3 offset = at.subtract(Vec3.atCenterOf(equalizer.getBlockPos())).scale(16);
        return new Vec3(offset.x * right.getStepX() + offset.z * right.getStepZ(), 0,
                offset.x * back.getStepX() + offset.z * back.getStepZ());
    }
}
