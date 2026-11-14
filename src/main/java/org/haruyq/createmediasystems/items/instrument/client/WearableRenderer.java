package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.haruyq.createmediasystems.client.ArmPoses;
import org.haruyq.createmediasystems.client.Worn;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.keytar.client.KeytarMesh;
import org.haruyq.createmediasystems.items.keytar.client.KeytarWear;
import org.haruyq.createmediasystems.items.melodica.client.MelodicaMesh;
import org.haruyq.createmediasystems.items.melodica.client.MelodicaWear;
import org.haruyq.createmediasystems.items.power.ChargeableItem;

final class WearableRenderer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private WearableRenderer(PlayerRenderer renderer) {
        super(renderer);
    }

    static void register(IEventBus bus) {
        ArmPoses.add(WearableRenderer::arms);
        bus.addListener((EntityRenderersEvent.AddLayers event) -> {
            for (var skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new WearableRenderer(renderer));
            }
        });
        NeoForge.EVENT_BUS.addListener(WearableRenderer::firstPerson);
        bus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new ItemRenderer();
                return renderer;
            }
        }, WearableInstruments.KEYTAR.get(), WearableInstruments.MELODICA.get()));
    }

    private static void arms(HumanoidModel<?> model, Player player, float yaw, float pitch) {
        if (player.isInvisible() || player.isSpectator() || !WearableClient.shouldDraw(player)) return;
        var view = WearableClient.view(player);
        long now = System.nanoTime();
        view.animate(player, now);
        if (view.strapped(player)) {
            KeytarWear.arms(model, view.ready, view.seconds, view.handX, view.travel(Kind.KEYTAR, view.notes.lastNote(), now));
        }
        if (view.carriesMelodica(player)) {
            MelodicaWear.arms(model, view.play, view.handX, view.travel(Kind.MELODICA, view.notes.lastNote(), now));
        }
    }

    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
            float swing, float amount, float partial, float age, float yaw, float pitch) {
        if (player.isInvisible() || player.isSpectator() || !WearableClient.shouldDraw(player)) return;
        var model = getParentModel();
        var view = WearableClient.view(player);
        long now = System.nanoTime();
        if (view.strapped(player)) {
            pose.pushPose();
            Worn.place(pose, KeytarWear.placement(model, view.ready, view.seconds));
            draw(pose, buffers, light, player, view, Kind.KEYTAR, now);
            pose.popPose();
            KeytarWear.strap(pose, buffers, light, model, view.ready, view.seconds);
        }
        if (view.carriesMelodica(player)) {
            pose.pushPose();
            Worn.place(pose, MelodicaWear.placement(model, view.play));
            draw(pose, buffers, light, player, view, Kind.MELODICA, now);
            pose.popPose();
        }
    }

    private static void firstPerson(RenderHandEvent event) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator() || !player.isAlive()) return;
        boolean keyboard = WearableInstruments.ready(player);
        boolean melodica = WearableInstruments.carriesMelodica(player);
        if (!keyboard && !melodica) {
            if (event.getItemStack().is(WearableInstruments.KEYTAR.get())) event.setCanceled(true);
            return;
        }
        var hand = keyboard || player.getMainHandItem().is(WearableInstruments.MELODICA.get())
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (event.getHand() != hand) {
            if (keyboard || event.getItemStack().isEmpty() || event.getItemStack().is(WearableInstruments.KEYTAR.get())
                    || event.getItemStack().is(WearableInstruments.MELODICA.get())) event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        var pose = event.getPoseStack();
        var view = WearableClient.view(player);
        long now = System.nanoTime();
        view.animate(player, now);
        pose.pushPose();
        if (keyboard) {
            float ready = Worn.ease(view.ready);
            pose.translate(Mth.lerp(ready, .56f, .07f), Mth.lerp(ready, -.82f, -.48f), -.91f);
            pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(ready, 49, -7)));
            pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(ready, 57, 106)));
            pose.scale(.59f, .59f, .59f);
        } else {
            float play = Worn.ease(view.play);
            pose.translate(Mth.lerp(play, -.34f, -.06f), Mth.lerp(play, -.40f, -.36f), Mth.lerp(play, -.64f, -.71f));
            pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(play, -47, -9)));
            pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(play, 64, 106)));
            pose.scale(.76f, .76f, .76f);
        }
        draw(pose, event.getMultiBufferSource(), event.getPackedLight(), player, view, keyboard ? Kind.KEYTAR : Kind.MELODICA, now);
        pose.popPose();
    }

    private static void draw(PoseStack pose, MultiBufferSource buffers, int light, Player player, WearableClient.View view,
            Kind kind, long now) {
        if (kind == Kind.KEYTAR) {
            float bend = view.notes.kind == kind ? (view.notes.bend() - InstrumentState.CENTER_BEND) / 8192f : 0;
            KeytarMesh.draw(pose, buffers, light, note -> view.travel(kind, note, now), view.powered(player), bend, view.notes.tone());
        } else {
            MelodicaMesh.draw(pose, buffers, light, note -> view.travel(kind, note, now));
        }
    }

    private static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        ItemRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                MultiBufferSource buffers, int light, int overlay) {
            pose.pushPose();
            pose.translate(.5, .5, .5);
            if (stack.is(WearableInstruments.KEYTAR.get())) {
                pose.scale(.75f, .75f, .75f);
                pose.translate(.14, 0, 0);
                KeytarMesh.draw(pose, buffers, light, note -> 0, ChargeableItem.energy(stack) > 0, 0, 0);
            } else {
                pose.scale(1.55f, 1.55f, 1.55f);
                pose.translate(.03, 0, 0);
                MelodicaMesh.draw(pose, buffers, light, note -> 0);
            }
            pose.popPose();
        }
    }
}
