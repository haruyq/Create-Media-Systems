package org.haruyq.createmediasystems.items.walkman.nws310.client;

import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanFeature;
import org.haruyq.createmediasystems.items.walkman.nws310.LoudmanItem;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class LoudmanClient {
    private LoudmanClient() {
    }

    public static void register(IEventBus modBus) {
        LoudmanItem.controls = hand -> Minecraft.getInstance().setScreen(new LoudmanScreen(hand));
        modBus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, () -> {
            Minecraft mc = Minecraft.getInstance();
            return new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                @Override
                public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                        MultiBufferSource buffers, int light, int overlay) {
                    if (stack.getItem() instanceof LoudmanItem loudman) {
                        pose.pushPose();
                        pose.translate(.5, .5, .5);
                        LoudmanMesh.draw(pose, buffers, light, loudman.color, LoudmanItem.tuning(stack));
                        pose.popPose();
                    }
                }
            };
        }, LoudmanFeature.all().stream().map(item -> (Item) item.get()).toArray(Item[]::new)));
    }
}
