package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;

/** The performer's own body below the camera, with the same skin and armor as the third-person model. */
final class InstrumentBody extends PlayerModel<AbstractClientPlayer> implements RenderLayerParent<AbstractClientPlayer, InstrumentBody> {
    private final HumanoidArmorLayer<AbstractClientPlayer, InstrumentBody, HumanoidArmorModel<AbstractClientPlayer>> armor;

    InstrumentBody(EntityRendererProvider.Context context, boolean slim) {
        super(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
        head.visible = hat.visible = false;
        armor = new HumanoidArmorLayer<>(this,
                belowNeckArmor(context, slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR),
                belowNeckArmor(context, slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR),
                context.getModelManager());
    }

    private static HumanoidArmorModel<AbstractClientPlayer> belowNeckArmor(EntityRendererProvider.Context context, ModelLayerLocation layer) {
        var model = new HumanoidArmorModel<AbstractClientPlayer>(context.bakeLayer(layer));
        model.head.skipDraw = model.hat.skipDraw = true;
        return model;
    }

    @Override public InstrumentBody getModel() { return this; }
    @Override public ResourceLocation getTextureLocation(AbstractClientPlayer player) { return player.getSkin().texture(); }

    void draw(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, boolean bentArms,
              float walk, float amount, float partial, float age, float yaw, float pitch) {
        young = player.isBaby();
        crouching = player.isCrouching();
        riding = player.isPassenger() && player.getVehicle().shouldRiderSit();
        attackTime = player.getAttackAnim(partial);
        prepareMobModel(player, walk, amount, partial);
        setupAnim(player, walk, amount, age, yaw, pitch);
        leftArm.visible = rightArm.visible = !bentArms;
        leftSleeve.visible = !bentArms && player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);
        rightSleeve.visible = !bentArms && player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
        jacket.visible = player.isModelPartShown(PlayerModelPart.JACKET);
        leftPants.visible = player.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG);
        rightPants.visible = player.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG);
        renderToBuffer(pose, buffers.getBuffer(renderType(getTextureLocation(player))), light, OverlayTexture.NO_OVERLAY);
        armor.render(pose, buffers, light, player, walk, amount, partial, age, yaw, pitch);
    }
}
