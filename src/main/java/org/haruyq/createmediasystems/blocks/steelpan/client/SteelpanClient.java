package org.haruyq.createmediasystems.blocks.steelpan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanBlock;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanFeature;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets.Target;
import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.jetbrains.annotations.Nullable;

public final class SteelpanClient implements SteelpanPackets.Client {
    private static final Map<SteelpanBlock.Entity, SteelpanState> STATES = new WeakHashMap<>();

    private SteelpanClient() {}

    /** Access to the visual timeline for render-thread score and preview code. */
    public static SteelpanState state(SteelpanBlock.Entity pan) {
        return STATES.computeIfAbsent(pan, ignored -> new SteelpanState());
    }

    public static void register(IEventBus bus) {
        SteelpanPackets.client(new SteelpanClient());
        bus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) SteelpanModel::reload));
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(SteelpanFeature.ENTITY.get(), context -> new Renderer()));
        bus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, ItemRenderer::new,
                SteelpanFeature.ITEM.get()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> SteelpanAudio.tick());
        WorldEvents.onLevelChanged(() -> {
            STATES.clear();
            SteelpanAudio.clear();
        });
    }

    @Override
    public void open(Target target) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && (mc.screen == null || mc.screen instanceof SteelpanScreen)
                && SteelpanPackets.canPlay(mc.player, target)) {
            SteelpanAudio.prepare();
            mc.setScreen(new SteelpanScreen(target));
        }
    }

    @Override
    public void strike(SteelpanPackets.Strike strike) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var pan = instrument(mc.level, strike.target());
        if (pan == null && strike.target().origin(mc.level, 1) == null) return;
        long now = System.nanoTime();
        long impact = pan == null ? now + SteelpanState.PREPARE
                : state(pan).schedule(strike.midi(), strike.velocity() / 127f, strike.left() ? 0 : 1, now);
        SteelpanAudio.strike(strike.target(), strike.midi(), strike.velocity(), impact);
        if (mc.screen instanceof SteelpanScreen screen) screen.strike(strike, impact);
    }

    @Nullable
    private static SteelpanBlock.Entity instrument(ClientLevel level, Target target) {
        if (!target.mounted()) {
            return level.isLoaded(target.pos()) && level.getBlockEntity(target.pos()) instanceof SteelpanBlock.Entity pan ? pan : null;
        }
        if (!(level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier)
                || carrier.getContraption() == null || carrier.getContraption().disassembled) return null;
        return carrier.getContraption().getBlockEntityClientSide(target.pos()) instanceof SteelpanBlock.Entity pan ? pan : null;
    }

    private static final class Renderer implements BlockEntityRenderer<SteelpanBlock.Entity> {
        @Override
        public void render(SteelpanBlock.Entity entity, float partial, PoseStack pose,
                           MultiBufferSource buffers, int light, int overlay) {
            SteelpanAudio.prepare();
            pose.pushPose();
            pose.translate(.5, 0, .5);
            pose.mulPose(Axis.YP.rotationDegrees(-entity.getBlockState().getValue(SteelpanBlock.FACING).toYRot()));
            SteelpanModel.render(pose, buffers, light, overlay, STATES.get(entity), System.nanoTime());
            pose.popPose();
        }

        @Override public AABB getRenderBoundingBox(SteelpanBlock.Entity entity) {
            return new AABB(entity.getBlockPos()).inflate(.3);
        }
    }

    private static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        ItemRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
            pose.pushPose();
            pose.translate(.5, 0, .5);
            SteelpanModel.render(pose, buffers, light, overlay, null, System.nanoTime());
            pose.popPose();
        }
    }
}
