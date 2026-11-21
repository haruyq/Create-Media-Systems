package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.haruyq.createmediasystems.client.ArmPoses;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.instrument.InstrumentFeature;
import org.haruyq.createmediasystems.items.instrument.InstrumentItem.TwoHanded;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.joml.Vector3f;

public final class InstrumentClient {
    private static final Map<Player, Playback> PLAYBACK = new WeakHashMap<>();
    private static final Deque<PlayerDraw> DRAWS = new ArrayDeque<>();
    private static final Map<PlayerSkin.Model, GuitarBody> GUITAR_BODIES = new EnumMap<>(PlayerSkin.Model.class);

    private record Held(InteractionHand hand, ItemStack stack, TwoHanded item) {}
    private record PartState(ModelPart part, boolean skipDraw) {
        PartState(ModelPart part) { this(part, part.skipDraw); part.skipDraw = true; }
        void restore() { part.skipDraw = skipDraw; }
    }
    private record PlayerDraw(Player player, ItemStack custom, PartState[] arms) {}
    private static final class Playback {
        Item item;
        InstrumentAnimation external;
    }

    private InstrumentClient() {}

    /** Connect a held instrument to a caller's own performance on the client render thread. */
    public static InstrumentAnimation performance(Player player, InstrumentKind kind) {
        var held = held(player);
        if (held == null || held.item.kind.model != kind) throw new IllegalArgumentException("Player is not holding that instrument");
        var playback = playback(player, held);
        if (playback.external == null) playback.external = new InstrumentAnimation(kind);
        return playback.external;
    }

    public static void endPerformance(Player player) { PLAYBACK.remove(player); }

    public static void register(IEventBus bus) {
        bus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) InstrumentModel::reload));
        bus.addListener((EntityRenderersEvent.AddLayers event) -> {
            for (var skin : event.getSkins()) {
                PlayerRenderer renderer = event.getSkin(skin);
                if (renderer != null) renderer.addLayer(new HeldInstrumentLayer(renderer));
                GUITAR_BODIES.put(skin, new GuitarBody(event.getContext(), skin == PlayerSkin.Model.SLIM));
            }
        });
        bus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new StandaloneRenderer();
                return renderer;
            }
        }, InstrumentFeature.ITEMS.stream().map(e -> (Item) e.get()).toArray(Item[]::new)));

        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, InstrumentClient::beforePlayer);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, InstrumentClient::afterPlayer);
        NeoForge.EVENT_BUS.addListener(InstrumentClient::firstPerson);
        NeoForge.EVENT_BUS.addListener(InstrumentClient::wornGuitar);
        WorldEvents.onLevelChanged(() -> {
            PLAYBACK.clear();
            restorePlayerDraws();
        });
        WorldEvents.onFrameEnded(() -> {
            PLAYBACK.keySet().removeIf(player -> player.isRemoved() || held(player) == null);
            restorePlayerDraws();
        });
        ArmPoses.add((model, player, yaw, pitch) -> {
            var held = held(player);
            if (held == null || splitArms(player) || !visible(player)) return;
            var rig = InstrumentModel.rig(held.item.kind.model);
            if (rig == null) return;
            var frame = frame(player, held);
            var mount = InstrumentPlayerPose.create(held.item.kind.model, rig, frame, model.body, model.head);
            pointArm(model.leftArm, mount.leftGrip());
            pointArm(model.rightArm, mount.rightGrip());
        });
    }

    private static void pointArm(ModelPart arm, Vector3f target) {
        var direction = new Vector3f(target).sub(arm.x / 16, arm.y / 16, arm.z / 16).normalize();
        arm.yRot = 0;
        arm.xRot = (float)Math.asin(Math.clamp(direction.z,-1,1));
        arm.zRot = (float)Math.atan2(-direction.x,direction.y);
    }

    private static boolean visible(Player player) {
        return !player.isInvisible() && !player.isSpectator() && !player.isVisuallySwimming()
                && !player.isFallFlying() && !player.isSleeping();
    }

    private static boolean splitArms(Player player) {
        // Armor's own layer still needs its normal arm parts and skin pose.
        return !(player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem);
    }

    private static Held held(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof TwoHanded instrument && instrument.canPlay(player, hand)) {
                return new Held(hand, stack, instrument);
            }
        }
        return null;
    }

    private static Playback playback(Player player, Held held) {
        var playback = PLAYBACK.computeIfAbsent(player, p -> new Playback());
        if (playback.item != held.item) {
            playback.item = held.item;
            playback.external = null;
        }
        return playback;
    }

    private static InstrumentAnimation.Frame frame(Player player, Held held) {
        long now = System.nanoTime();
        var playback = PLAYBACK.get(player);
        if (playback != null && playback.item == held.item && playback.external != null) return playback.external.sample(now);
        return WearableClient.frame(player, held.item.kind.model, now);
    }

    private static void beforePlayer(RenderPlayerEvent.Pre event) {
        var player = event.getEntity();
        var held = held(player);
        var model = event.getRenderer().getModel();
        if (held == null || !visible(player) || InstrumentModel.rig(held.item.kind.model) == null) {
            DRAWS.push(new PlayerDraw(player, ItemStack.EMPTY, null));
            return;
        }
        PartState[] arms = splitArms(player) ? new PartState[]{
                new PartState(model.leftArm), new PartState(model.rightArm),
                new PartState(model.leftSleeve), new PartState(model.rightSleeve)} : null;
        DRAWS.push(new PlayerDraw(player, held.stack, arms));
    }

    private static void afterPlayer(RenderPlayerEvent.Post event) {
        var scope = DRAWS.peek();
        if (scope != null && scope.player == event.getEntity()) {
            DRAWS.pop();
            if (scope.arms != null) for (PartState arm : scope.arms) arm.restore();
        }
    }

    private static void restorePlayerDraws() {
        // Another renderer can cancel its Pre event after our listener has run.
        while (!DRAWS.isEmpty()) {
            var scope = DRAWS.pop();
            if (scope.arms != null) for (PartState arm : scope.arms) arm.restore();
        }
    }

    private static final class HeldInstrumentLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        HeldInstrumentLayer(PlayerRenderer renderer) { super(renderer); }

        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                     float walk, float amount, float partial, float age, float yaw, float pitch) {
            var held = held(player);
            if (held == null || !visible(player)) return;
            drawWorn(pose, buffers, light, player, held, getParentModel(), splitArms(player));
        }
    }

    private static void drawWorn(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                 Held held, PlayerModel<AbstractClientPlayer> model, boolean arms) {
        var kind = held.item.kind.model;
        var rig = InstrumentModel.rig(kind);
        if (rig == null) return;
        var frame = frame(player, held);
        var mount = InstrumentPlayerPose.create(kind, rig, frame, model.body, model.head);
        drawMounted(pose, buffers, light, kind, held.item.modelTexture, rig, frame, mount, true);
        if (arms) drawArms(pose, buffers, light, kind, mount, player, model, false);
    }

    private static void wornGuitar(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var camera = event.getCamera();
        if (player == null || camera.getEntity() != player || camera.isDetached() || mc.options.hideGui
                || !player.isAlive() || !visible(player)) return;
        var held = held(player);
        if (held == null || held.item.kind.model != InstrumentKind.STRATOCASTER || IrisCompat.shadowPass()) return;
        var model = GUITAR_BODIES.get(player.getSkin().model());
        if (model == null) return;

        float partial = camera.getPartialTickTime();
        float bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);

        // The world camera supplies pitch, FOV and depth. Position the model from the player's feet,
        // with the same render offset, scale and model origin vanilla uses for the other players.
        var dispatcher = mc.getEntityRenderDispatcher();
        var offset = dispatcher.getRenderer(player).getRenderOffset(player, partial);
        var origin = player.getPosition(partial).subtract(camera.getPosition()).add(offset);
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(origin.x, origin.y, origin.z);
        pose.mulPose(Axis.YP.rotationDegrees(180 - bodyYaw));
        float scale = player.getScale() * .9375f;
        pose.scale(-scale, -scale, scale);
        // The vanilla camera sits on the neck axis. Put the shoulders three skin pixels behind
        // the eyes so looking down sights past the collar and along the fretboard.
        pose.translate(0, -1.501f, 3f / 16);
        int light = dispatcher.getPackedLightCoords(player, partial);
        var buffers = mc.renderBuffers().bufferSource();
        boolean bentArms = splitArms(player);
        model.draw(pose, buffers, light, player, bentArms, player.walkAnimation.position(partial),
                player.walkAnimation.speed(partial), partial, player.tickCount + partial,
                Mth.wrapDegrees(player.getViewYRot(partial) - bodyYaw), player.getViewXRot(partial));
        drawWorn(pose, buffers, light, player, held, model, bentArms);
        pose.popPose();
    }

    private static void firstPerson(RenderHandEvent event) {
        var player = Minecraft.getInstance().player;
        if (player == null || !visible(player)) return;
        var held = held(player);
        // A shoulder-slung guitar is already in the world pass, including during a hotbar change.
        boolean puttingAwayGuitar = event.getItemStack().getItem() instanceof TwoHanded previous
                && previous.kind.model == InstrumentKind.STRATOCASTER && !player.getItemInHand(event.getHand()).is(previous);
        if (puttingAwayGuitar || held != null && held.item.kind.model == InstrumentKind.STRATOCASTER) {
            event.setCanceled(true);
            return;
        }
        if (held == null || InstrumentModel.rig(held.item.kind.model) == null) return;
        if (event.getHand() != held.hand) {
            if (event.getItemStack().isEmpty()) event.setCanceled(true);
            return;
        }
        if (!event.getItemStack().is(held.item)) return;
        event.setCanceled(true);
        var kind = held.item.kind.model;
        var pose = event.getPoseStack();
        var buffers = event.getMultiBufferSource();
        var rig = InstrumentModel.rig(kind);
        var frame = frame(player, held);
        var mount = InstrumentPlayerPose.firstPerson(kind, rig, frame);
        pose.pushPose();
        pose.translate(-.070, -.090 - .19 * event.getEquipProgress(), -.68);
        pose.mulPose(Axis.ZP.rotationDegrees(180));
        drawMounted(pose,buffers,event.getPackedLight(),kind,held.item.modelTexture,rig,frame,mount,false);
        drawArms(pose,buffers,event.getPackedLight(),kind,mount,player,null,true);
        pose.popPose();
    }

    private static void drawArms(PoseStack pose, MultiBufferSource buffers, int light, InstrumentKind kind,
                                 InstrumentPlayerPose.Mount mount, AbstractClientPlayer player,
                                 PlayerModel<?> model, boolean firstPerson) {
        var skin = player.getSkin();
        InstrumentPlayerArms.render(pose,buffers,light,skin.texture(),kind,mount,
                skin.model() == PlayerSkin.Model.SLIM, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE),
                player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), model == null ? null : model.leftArm,
                model == null ? null : model.rightArm, firstPerson);
    }

    static void drawMounted(PoseStack pose, MultiBufferSource buffers, int light, InstrumentKind kind, String finish,
                            InstrumentModel.Rig rig, InstrumentAnimation.Frame frame, InstrumentPlayerPose.Mount mount,
                            boolean showStrap) {
        if (showStrap && kind == InstrumentKind.STRATOCASTER) strap(pose,buffers,light,rig,mount);
        pose.pushPose();
        pose.mulPose(mount.transform());
        InstrumentModel.render(pose,buffers,light,OverlayTexture.NO_OVERLAY,kind,finish,frame);
        pose.popPose();
    }

    private static void strap(PoseStack pose, MultiBufferSource buffers, int light, InstrumentModel.Rig rig,
                              InstrumentPlayerPose.Mount mount) {
        var pins = rig.strap_pins();
        Vector3f[] points = {
            mount.at(pins[1]), bodyPoint(mount,.226f,.245f,-.164f), bodyPoint(mount,.238f,.024f,-.145f),
            bodyPoint(mount,.235f,-.018f,-.052f), bodyPoint(mount,.232f,-.016f,.081f), bodyPoint(mount,.213f,.050f,.145f),
            bodyPoint(mount,.035f,.318f,.151f), bodyPoint(mount,-.237f,.57f,.149f),
            bodyPoint(mount,-.327f,.675f,.044f), mount.at(pins[0])
        };
        var out = buffers.getBuffer(Mesh.solid());
        Vector3f[] sides = new Vector3f[points.length];
        for (int i = 0; i < points.length; i++) {
            var delta = new Vector3f(points[Math.min(i+1,points.length-1)]).sub(points[Math.max(0,i-1)]);
            sides[i] = new Vector3f(1,0,0).fma(-delta.x/delta.lengthSquared(),delta).normalize(.023f);
        }
        for (int i = 1; i < points.length; i++) {
            var a = points[i-1]; var b = points[i];
            var delta = new Vector3f(b).sub(a);
            var n = delta.cross(sides[i], new Vector3f()).normalize();
            var c0 = new Vector3f(a).sub(sides[i-1]); var c1 = new Vector3f(b).sub(sides[i]);
            var c2 = new Vector3f(b).add(sides[i]); var c3 = new Vector3f(a).add(sides[i-1]);
            Mesh.quad(pose,out,c0.x,c0.y,c0.z,c1.x,c1.y,c1.z,c2.x,c2.y,c2.z,c3.x,c3.y,c3.z,
                    n.x,n.y,n.z, Mesh.Tile.DARK,light,OverlayTexture.NO_OVERLAY);
        }
    }

    private static Vector3f bodyPoint(InstrumentPlayerPose.Mount mount, float x, float y, float z) {
        return mount.body().transformPosition(new Vector3f(x,y,z));
    }

    private static final class StandaloneRenderer extends BlockEntityWithoutLevelRenderer {
        StandaloneRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                           MultiBufferSource buffers, int light, int overlay) {
            if (!(stack.getItem() instanceof TwoHanded item)) return;
            var scope = DRAWS.peek();
            boolean thirdPerson = context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            if (thirdPerson && scope != null && stack == scope.custom) return;
            pose.pushPose();
            pose.translate(.5,.5,.5);
            InstrumentModel.centerItem(pose,item.kind.model);
            InstrumentModel.render(pose,buffers,light,overlay,item.kind.model,item.modelTexture,InstrumentAnimation.rest());
            pose.popPose();
        }
    }
}
