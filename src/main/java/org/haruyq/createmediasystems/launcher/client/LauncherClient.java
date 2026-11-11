package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.client.PointLights;
import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.client.mesh.CellLight;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.launcher.LauncherTimeline;
import org.haruyq.createmediasystems.launcher.DiscLauncherReloadPacket;
import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.LauncherMagazine;
import org.haruyq.createmediasystems.launcher.LauncherMechanics;
import org.haruyq.createmediasystems.launcher.CellLighting;
import org.haruyq.createmediasystems.launcher.DiscLauncherTrigger;
import org.haruyq.createmediasystems.launcher.LauncherFeedback;
import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.playback.EmitterAudioManager;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Holds only client-side render hooks. Common/server classes never load client code. */
public final class LauncherClient {
    private LauncherClient() {}
    public static final ModConfigSpec.EnumValue<LauncherDamageDisplay.Mode> DAMAGE_NUMBERS = ClientConfig.BUILDER
            .comment("Weapon damage numbers: STACKING combines recent hits on the same target, FLOATING shows individual hits, BOTH shows both, OFF hides numbers.")
            .defineEnum("launcherDamageNumbers", LauncherDamageDisplay.Mode.STACKING);
    public static final ModConfigSpec.EnumValue<LauncherDamageDisplay.Crosshair> CROSSHAIR_FEEDBACK = ClientConfig.BUILDER
            .comment("X_WITH_SHIELD shows a confirmed-hit X plus Minecraft absorption shield feedback; X shows only the X; OFF hides both.")
            .defineEnum("launcherCrosshairFeedback", LauncherDamageDisplay.Crosshair.X_WITH_SHIELD);
    private static final LauncherGunMotion MOTION=new LauncherGunMotion();
    private static final LauncherDamageDisplay DAMAGE=new LauncherDamageDisplay();
    public static LauncherDamageDisplay damageDisplay(){return DAMAGE;}
    private static boolean triggerDown;
    private static Object currentPlayer;
    private static long hitAt,lastShot=Long.MIN_VALUE;
    private static boolean headHit,killed;
    private static float hitDamage,cameraKick;
    private static int confirmedShots,confirmedHits,confirmedHeadshots;
    /** Optional focus provider for non-mouse input integrations; the default uses normal game focus. */
    private static java.util.function.BooleanSupplier additionalInputActive=()->false;
    /** Lets {@code active} count as input focus besides the game window's (a harness driving the client). */
    public static void additionalInput(java.util.function.BooleanSupplier active){additionalInputActive=active;}
    /** What the local player's launcher has confirmed so far, read-only, for diagnostics. */
    public record Diagnostics(int confirmedShots,int confirmedHits,int confirmedHeadshots,float hitDamage,boolean reticleDrawn) {}
    public static Diagnostics diagnostics(){return new Diagnostics(confirmedShots,confirmedHits,confirmedHeadshots,hitDamage,LauncherHud.reticleDrawn);}
    public static float aim(float partial){return MOTION.aim(partial);}
    public static float kick(float partial){return MOTION.kick(partial);}
    public static float hitAge(){return (System.nanoTime()-hitAt)/1_000_000_000f;}
    public static boolean headHit(){return headHit;}
    public static boolean killed(){return killed;}
    private static final KeyMapping RELOAD_KEY = new KeyMapping(
            "key.create_media_systems.disc_launcher_reload",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.create_media_systems");

    public static void register(IEventBus modBus) {
        modBus.addListener(LauncherClient::registerExtensions);
        modBus.addListener(LauncherClient::registerRenderers);
        modBus.addListener(LauncherClient::registerKeys);
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(LauncherFeature.DISC_CELL_MENU.get(), DiscCellScreen::new));
        NeoForge.EVENT_BUS.addListener(LauncherClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(LauncherClient::supportHand);
        NeoForge.EVENT_BUS.addListener(LauncherClient::input);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ComputeFovModifierEvent event)->{
            if(event.getPlayer()==Minecraft.getInstance().player && event.getPlayer().getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get()))
                event.setNewFovModifier(event.getNewFovModifier()*(1-.18f*aim(1)));
        });
        LauncherFeedback.shotListener=shot->{
            var mc=Minecraft.getInstance();if(mc.player==null || shot.tick()==lastShot)return;
            lastShot=shot.tick();confirmedShots++;MOTION.shot();
            float add=.42f-.24f*aim(1);cameraKick+=add;mc.player.setXRot(Math.max(-90,mc.player.getXRot()-add));
        };
        LauncherFeedback.hitListener=hit->{
            hitAt=System.nanoTime();headHit=hit.headshot();killed=hit.killed();hitDamage=hit.damage();
            DAMAGE.hit(new LauncherDamageDisplay.Entry(hit.target(),hit.health(),hit.shield(),hit.headshot(),hit.killed(),hit.shieldBroken(),hit.x(),hit.y(),hit.z(),hitAt/1_000_000_000.0));
            confirmedHits++;if(headHit)confirmedHeadshots++;
            var mc=Minecraft.getInstance();
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvent.createVariableRangeEvent(CreateMediaSystems.id(headHit?"disc_headshot":"disc_hit")),1,.45f));
        };
        LauncherHud.register(modBus);
        PointLights.add(new CellLights());
        EmitterAudioManager.add(DiscEmitter::near);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(RELOAD_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(mc.level!=null)for(var entity:mc.level.entitiesForRendering())if(entity instanceof DiscProjectile disc)disc.refreshAttachedPose();
        if(currentPlayer!=player){currentPlayer=player;triggerDown=false;lastShot=Long.MIN_VALUE;MOTION.reset();DAMAGE.reset();cameraKick=0;}
        boolean holding=player!=null && player.getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get()) && !player.isSpectator() && player.isAlive();
        boolean active=holding && mc.screen==null && mc.getOverlay()==null
                && (mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed() || additionalInputActive.getAsBoolean());
        boolean wantsAim=active && mc.options.keyUse.isDown() && !LauncherMagazine.reloading(player.getMainHandItem());
        if(holding) {
            if(wantsAim && !player.isUsingItem() && mc.gameMode!=null)mc.gameMode.useItem(player,InteractionHand.MAIN_HAND);
            if(!wantsAim && player.isUsingItem() && player.getUseItem().is(LauncherFeature.DISC_LAUNCHER.get()) && mc.gameMode!=null)
                mc.gameMode.releaseUsingItem(player);
            if(wantsAim)player.setSprinting(false);
        }
        MOTION.tick(wantsAim);
        if(player!=null && cameraKick>.0001f) {
            float returned=cameraKick*.30f;cameraKick-=returned;player.setXRot(Math.min(90,player.getXRot()+returned));
        }
        boolean fire=active && mc.options.keyAttack.isDown();
        if(player!=null && mc.getConnection()!=null && (fire || triggerDown))
            PacketDistributor.sendToServer(new DiscLauncherTrigger.Input(fire,player.getYRot(),player.getXRot()));
        triggerDown=fire;
        while (RELOAD_KEY.consumeClick()) {
            if (active) {
                PacketDistributor.sendToServer(DiscLauncherReloadPacket.INSTANCE);
            }
        }
    }

    private static void input(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        var mc=Minecraft.getInstance();
        if(mc.player!=null && mc.player.getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get())
                && (event.isAttack() || event.isUseItem())) {event.setCanceled(true);event.setSwingHand(false);}
    }

    public static LauncherTimeline.Frame frame(ItemStack stack, Level level, float partialTick) {
        return LauncherTimeline.sample(stack, level, partialTick);
    }

    /** The gun's animation now, and the cell it shows: where on its path, its colour and glow. */
    public record CellVisual(ItemStack stack, LauncherTimeline.Frame motion, LauncherMechanics.CellPose pose, int color, float strength) {}

    public static CellVisual cellVisual(ItemStack gun, Level level, float partial) {
        float age = LauncherTimeline.reloadAge(gun, level, partial);
        var motion = LauncherTimeline.sample(age, LauncherTimeline.shotAge(gun, level, partial));
        var path = LauncherMechanics.cellPose(age, motion);
        ItemStack cell = path.incoming() ? LauncherMagazine.ejecting(gun) ? ItemStack.EMPTY : LauncherMagazine.pending(gun)
                : LauncherMagazine.cell(gun);
        if (!(cell.getItem() instanceof DiscCellItem item)) return new CellVisual(cell, motion, path, 0x82949E, 0);
        return new CellVisual(cell, motion, path, item.tier.color, CellLighting.strength(ChargeableItem.charge(cell)));
    }

    private static void registerExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private DiscCellRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new DiscCellRenderer();
                return renderer;
            }
        }, LauncherFeature.DISC_CELLS.stream().map(entry -> entry.get()).toArray(DiscCellItem[]::new));
        event.registerItem(new IClientItemExtensions() {
            private LauncherRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new LauncherRenderer();
                return renderer;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm,
                                                    ItemStack held, float partial, float equip, float swing) {
                weaponPose(pose, frame(held, player.level(), partial), arm == HumanoidArm.RIGHT ? 1f : -1f, equip, bob(player, partial));
                return true;
            }
        }, LauncherFeature.DISC_LAUNCHER.get());
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LauncherFeature.DISC_PROJECTILE.get(), DiscProjectileRenderer::new);
        event.registerBlockEntityRenderer(LauncherFeature.DISC_CELL_CHARGER_ENTITY.get(), DiscCellChargerRenderer::new);
    }

    /** The gun's idle sway in first person. */
    static float bob(net.minecraft.world.entity.player.Player player, float partial) {
        return (float) Math.sin((player.tickCount + partial) * 0.055f) * 0.0025f;
    }

    /** The first-person gun model's space, in its 1/16 units' frame: the hand pose, then ItemRenderer's centring. */
    static void gunSpace(PoseStack pose, LauncherTimeline.Frame f, float hand, float bob) {
        weaponPose(pose, f, hand, 0, bob);
        pose.translate(-.5f, -.5f, -.5f);
    }

    /** The cell's light on whatever is drawn in {@link #gunSpace}. */
    private static CellLight spill(PoseStack pose, CellVisual visual, float strength) {
        var path = visual.pose();
        return new CellLight(pose.last().pose(), path.x() / 16, path.y() / 16, path.z() / 16, 18f / 16, visual.color(),
                visual.strength() * strength);
    }

    static void weaponPose(PoseStack pose, LauncherTimeline.Frame f, float hand, float equip, float bob) {
        float partial=Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        var p=LauncherGunPose.sample(f,hand,aim(partial),kick(partial),bob);
        // Deliberately independent of vanilla equip/attack height: energy sync cannot stow a firearm.
        pose.translate(p.x(),p.y(),p.z());
        pose.mulPose(Axis.YP.rotationDegrees(p.yaw()));pose.mulPose(Axis.XP.rotationDegrees(p.pitch()));
        pose.mulPose(Axis.ZP.rotationDegrees(p.roll()));pose.scale(p.scale(),p.scale(),p.scale());
    }

    /** The player's actual skinned support hand removes the old cell and feeds the escrowed one into the breech. */
    private static void supportHand(RenderHandEvent event) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;
        ItemStack launcher = player.getMainHandItem();
        if (!launcher.is(LauncherFeature.DISC_LAUNCHER.get())) return;
        if(event.getHand()==InteractionHand.MAIN_HAND){gripHand(event,launcher,true);return;}
        event.setCanceled(true);
        if(!LauncherMagazine.reloading(launcher)){gripHand(event,launcher,false);return;}
        float age = LauncherTimeline.reloadAge(launcher, player.level(), event.getPartialTick());
        if (age < LauncherTimeline.HAND_IN.start() || age >= LauncherTimeline.HAND_OUT.end()
                || LauncherMagazine.ejecting(launcher) && age >= LauncherTimeline.INCOMING_AT) return;
        float hand = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        var visual = cellVisual(launcher, player.level(), event.getPartialTick());
        if (visual.stack().isEmpty()) return;
        var motion = visual.motion();
        var pose = event.getPoseStack();
        var buffers = event.getMultiBufferSource();
        pose.pushPose();
        // ItemRenderer centers the weapon after the hand transform; use the same origin for the cell and hand.
        gunSpace(pose, motion, hand, 0);
        var path = visual.pose();
        CellLight spill = spill(pose, visual, 1.8f);
        float withdraw = 1 - motion.supportHand();
        pose.translate(-hand * withdraw * .4f, -withdraw * .3f, withdraw * .15f);
        // Only the receiver draws the cell, at the same scale throughout. The palm supports its bottom cap.
        pose.translate(path.x() / 16, (path.y() + LauncherMechanics.CELL_BOTTOM - .25f) / 16, path.z() / 16);
        pose.mulPose(Axis.YP.rotationDegrees(hand * 10));
        pose.scale(.85f, .85f, .85f);
        pose.translate(hand > 0 ? -.375f : .375f, -.75f, 0);
        var renderer = (net.minecraft.client.renderer.entity.player.PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        if (hand > 0) renderer.renderLeftHand(pose, spill.wrap(buffers), event.getPackedLight(), player);
        else renderer.renderRightHand(pose, spill.wrap(buffers), event.getPackedLight(), player);
        pose.popPose();
    }

    private static void gripHand(RenderHandEvent event,ItemStack launcher,boolean primary) {
        var mc=Minecraft.getInstance();var player=mc.player;
        float hand=player.getMainArm()==HumanoidArm.RIGHT?1:-1;
        var pose=event.getPoseStack();pose.pushPose();
        var visual=cellVisual(launcher,player.level(),event.getPartialTick());
        gunSpace(pose,visual.motion(),hand,0);
        var spill=spill(pose,visual,1.4f);
        pose.translate(.5f,(primary?4:7)/16f,(primary?17.4f:4.8f)/16f);
        pose.mulPose(Axis.YP.rotationDegrees(hand*(primary?-12:15)));
        pose.scale(.82f,.82f,.82f);
        boolean right=(player.getMainArm()==HumanoidArm.RIGHT)==primary;
        pose.translate(right?-.375f:.375f,-.75f,0);
        var renderer=(net.minecraft.client.renderer.entity.player.PlayerRenderer)mc.getEntityRenderDispatcher().getRenderer(player);
        if(right)renderer.renderRightHand(pose,spill.wrap(event.getMultiBufferSource()),event.getPackedLight(),player);
        else renderer.renderLeftHand(pose,spill.wrap(event.getMultiBufferSource()),event.getPackedLight(),player);
        pose.popPose();
    }
}
