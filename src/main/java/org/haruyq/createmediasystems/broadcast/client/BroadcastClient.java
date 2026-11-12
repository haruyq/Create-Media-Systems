package org.haruyq.createmediasystems.broadcast.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.haruyq.createmediasystems.broadcast.BroadcastFeature;
import org.haruyq.createmediasystems.broadcast.BroadcastTowerBlock;

public final class BroadcastClient {
    private BroadcastClient() {}

    public static void register(IEventBus bus) {
        MovingBroadcastAudio.register();
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(BroadcastFeature.TOWER_ENTITY.get(), context -> new TowerRenderer()));
        bus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    var mc = Minecraft.getInstance();
                    renderer = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                        @Override
                        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                MultiBufferSource buffers, int light, int overlay) {
                            if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof BroadcastTowerBlock head) {
                                BroadcastTowerMesh.draw(head.profile(), pose, buffers, light, overlay);
                            }
                        }
                    };
                }
                return renderer;
            }
        }, BroadcastFeature.HORN_ITEM.get(), BroadcastFeature.DIGITAL_HORN_ITEM.get(), BroadcastFeature.ARRAY_ITEM.get()));
    }

    private static final class TowerRenderer implements BlockEntityRenderer<BroadcastTowerBlock.Tower> {
        @Override
        public void render(BroadcastTowerBlock.Tower tower, float partial, PoseStack pose, MultiBufferSource buffers,
                int light, int overlay) {
            BroadcastTowerMesh.draw(tower.profile(), pose, buffers, light, overlay);
        }

        @Override
        public AABB getRenderBoundingBox(BroadcastTowerBlock.Tower tower) {
            return new AABB(tower.getBlockPos()).inflate(.45, .5, .45);
        }
    }
}
