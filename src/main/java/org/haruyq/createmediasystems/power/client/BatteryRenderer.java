package org.haruyq.createmediasystems.power.client;

import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.power.PowerFeature;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class BatteryRenderer extends BlockEntityWithoutLevelRenderer {
    private static final int SHELL = 0x23252A;

    private BatteryRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static final ClientFeature CLIENT = (IEventBus modBus) -> modBus.addListener(
            (RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
                private BatteryRenderer renderer;
                @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (renderer == null) renderer = new BatteryRenderer();
                    return renderer;
                }
            }, PowerFeature.BATTERY.get()));

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack p, MultiBufferSource buffers, int light, int overlay) {
        p.pushPose();
        p.translate(.5, .16, .5);
        p.scale(2.8f, 2.8f, 2.8f);
        var v = buffers.getBuffer(Mesh.solid());
        Mesh.box(p, v, -.065f, 0, -.042f, .065f, .18f, .042f, SHELL, light);
        Mesh.box(p, v, -.067f, .018f, -.044f, .067f, .025f, .044f, 0xF25628, light);
        Mesh.box(p, v, -.049f, .178f, -.02f, .049f, .19f, .02f, 0x6F747A, light);
        Mesh.box(p, v, -.033f, .065f, -.045f, .033f, .126f, -.043f, 0xD0D5D9, light);
        p.popPose();
    }
}
