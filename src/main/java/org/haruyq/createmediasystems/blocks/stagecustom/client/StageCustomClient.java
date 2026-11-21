package org.haruyq.createmediasystems.blocks.stagecustom.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock.Entity.CymbalStrike;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock.Entity.Strike;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomFeature;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomMidi;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

public final class StageCustomClient implements BlockEntityRenderer<StageCustomBlock.Entity> {
    private static final ModelResourceLocation BODY = part("kit");
    private static final ModelResourceLocation WIRES = part("snare_wires");
    private static final ModelResourceLocation STICK = part("stick");
    private static final ResourceLocation SKINS = CreateMediaSystems.id("block/stage_custom/heads");
    private static final Drum[] DRUMS = Drum.values();
    private static final int SEGMENTS = 48;
    private static final float[] SINE = trig(true), COSINE = trig(false);
    private static final Map<StageCustomBlock.Entity, Motion> MOTIONS = new WeakHashMap<>();

    private static final KeyMapping MIDI = new KeyMapping("key.create_media_systems.stage_custom_midi",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.create_media_systems");

    private enum Skin {
        CLEAR(true), BLACK(false), COATED(false), KICK_CLEAR(true);

        final boolean transparent;

        Skin(boolean transparent) {
            this.transparent = transparent;
        }
    }

    private StageCustomClient() {}

    public static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(StageCustomFeature.ENTITY.get(), context -> new StageCustomClient()));
        bus.addListener((ModelEvent.RegisterAdditional event) -> List.of(BODY, WIRES, STICK).forEach(event::register));
        StageCustomCompleteClient.register(bus);
        StageCustomAudio.register();
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(MIDI));
        NeoForge.EVENT_BUS.addListener(StageCustomClient::midiKey);
        WorldEvents.onLevelChanged(MOTIONS::clear);
    }

    private static void midiKey(ClientTickEvent.Post event) {
        if (!MIDI.consumeClick()) return;
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        var pos = hit.getBlockPos();
        var state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof StageCustomBlock)) return;
        var kit = StageCustomMidi.reachable(mc.player, StageCustomBlock.root(pos, state));
        if (kit != null) mc.setScreen(new StageCustomMidiScreen(kit));
    }

    static ModelResourceLocation part(String name) {
        return ModelResourceLocation.standalone(CreateMediaSystems.id("block/stage_custom/" + name));
    }

    @Override
    public AABB getRenderBoundingBox(StageCustomBlock.Entity kit) {
        return new AABB(kit.getBlockPos()).inflate(StageCustomBlock.isComplete(kit.getBlockState()) ? 2 : 1);
    }

    @Override
    public void render(StageCustomBlock.Entity kit, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (kit.getLevel() == null) return;
        StageCustomAudio.prepare();
        var motion = MOTIONS.computeIfAbsent(kit, Motion::new);
        kit.drainStrikes().forEach(motion::strike);
        kit.drainCymbalStrikes().forEach(motion::strike);
        long now = System.nanoTime();
        int above = LevelRenderer.getLightColor(kit.getLevel(), kit.getBlockPos().above());
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-kit.getBlockState().getValue(StageCustomBlock.FACING).toYRot()));
        float scale = StageCustomBlock.scale(kit.getBlockState());
        pose.scale(scale, scale, scale);
        pose.translate(-.5, 0, -.5);
        baked(BODY, pose, buffers, above, overlay);
        if (motion.complete != null) {
            StageCustomCompleteClient.render(kit, motion.complete, motion.drums[Drum.BASS.ordinal()], now, pose, buffers, above, overlay);
        }
        renderWires(motion, now, pose, buffers, above, overlay);
        for (var strike : motion.sticks) renderStick(strike, now, pose, buffers, above, overlay);
        var skins = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(SKINS);
        for (Drum drum : DRUMS) renderHeads(drum, motion.drums[drum.ordinal()], now, pose, buffers, skins, above, overlay);
        pose.popPose();
    }

    static void baked(ModelResourceLocation model, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var mc = Minecraft.getInstance();
        mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()),
                null, mc.getModelManager().getModel(model), 1, 1, 1, light, overlay, ModelData.EMPTY, null);
    }

    private static void renderWires(Motion motion, long now, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var snare = motion.drums[Drum.SNARE.ordinal()];
        double age = seconds(snare, now);
        double buzz = age < .17 ? snare.strength() * .0022 * Math.sin(age * 120) * Math.exp(-age / .048) : 0;
        var normal = StageCustomLayout.shell(Drum.SNARE).normal();
        pose.pushPose();
        pose.translate(normal.x * buzz, normal.y * buzz, normal.z * buzz);
        baked(WIRES, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static void renderStick(Stick strike, long now, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        double age = strike == null ? Double.POSITIVE_INFINITY : Math.max(0, (now - strike.time()) / 1e9);
        if (age >= .225) return;
        double rebound = (1 - Math.exp(-age * 33)) * (.095 + .06 * strike.strength());
        Vec3 tip = strike.contact().add(strike.normal().scale(rebound + .004));
        double side = strike.hand() == 0 ? -.165 : .165;
        Vec3 grip = new Vec3(tip.x + side, strike.contact().y + .24, strike.contact().z + .29);
        Vec3 shaft = grip.subtract(tip).normalize();
        pose.pushPose();
        pose.translate(.5 + tip.x, tip.y, .5 + tip.z);
        pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) shaft.x, (float) shaft.y, (float) shaft.z)));
        baked(STICK, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static void renderHeads(Drum drum, Strike strike, long now, PoseStack pose, MultiBufferSource buffers,
                                    TextureAtlasSprite skins, int light, int overlay) {
        var shell = StageCustomLayout.shell(drum);
        pose.pushPose();
        pose.translate(.5 + shell.x(), shell.y(), .5 + shell.z());
        pose.mulPose(Axis.XP.rotationDegrees((float) shell.tilt()));
        float radius = (float) shell.headRadius();
        float depth = (float) shell.depthMetres();
        Skin batter = switch (drum) {
            case BASS -> Skin.KICK_CLEAR;
            case SNARE -> Skin.COATED;
            default -> Skin.CLEAR;
        };
        Skin resonant = drum == Drum.BASS ? Skin.BLACK : Skin.CLEAR;
        double age = seconds(strike, now);
        float movement = motion(drum, strike, age);
        disk(buffers, pose, skins, batter, radius, 0, movement, strike, light, overlay);
        disk(buffers, pose, skins, resonant, radius, -depth, -movement * .37f, strike, light, overlay);
        pose.popPose();
    }

    private static float motion(Drum drum, Strike strike, double age) {
        if (age >= .36) return 0;
        double size = drum == Drum.BASS ? .012 : .009;
        double restitution = .63 * Math.exp(-age / .020) + .37 * Math.exp(-age / .084) * Math.cos(age * 69);
        return (float) (-size * strike.strength() * restitution);
    }

    private static double seconds(Strike strike, long now) {
        return strike == null ? Double.POSITIVE_INFINITY : Math.max(0, (now - strike.time()) / 1e9);
    }

    private static void disk(MultiBufferSource buffers, PoseStack pose, TextureAtlasSprite skins, Skin skin, float radius,
                             float height, float motion, Strike strike, int light, int overlay) {
        // Keep clear heads on the same deferred translucent sheet as other block models. The opaque shell and the
        // opposite head have to write depth before the batter film can reveal them through its highlights.
        var out = buffers.getBuffer(skin.transparent ? Sheets.translucentCullBlockSheet() : Sheets.cutoutBlockSheet());
        int rings = Math.abs(motion) > .00005 ? 5 : 1;
        float hitU = strike == null ? 0 : strike.u(), hitV = strike == null ? 0 : strike.v();
        for (int face = -1; face <= 1; face += 2) {
            for (int ring = 0; ring < rings; ring++) {
                float inner = ring / (float) rings, outer = (ring + 1) / (float) rings;
                for (int i = 0; i < SEGMENTS; i++) {
                    int a = face == 1 ? i + 1 : i, b = face == 1 ? i : i + 1;
                    skinVertex(pose, out, skins, skin, radius, height, motion, hitU, hitV, inner, a, face, light, overlay);
                    skinVertex(pose, out, skins, skin, radius, height, motion, hitU, hitV, outer, a, face, light, overlay);
                    skinVertex(pose, out, skins, skin, radius, height, motion, hitU, hitV, outer, b, face, light, overlay);
                    skinVertex(pose, out, skins, skin, radius, height, motion, hitU, hitV, inner, b, face, light, overlay);
                }
            }
        }
    }

    private static void skinVertex(PoseStack pose, VertexConsumer out, TextureAtlasSprite skins, Skin skin, float radius,
                                   float height, float motion, float hitU, float hitV, float distance, int angle, int face,
                                   int light, int overlay) {
        float u = distance * COSINE[angle], v = distance * SINE[angle];
        float x = u - hitU, z = v - hitV;
        float flex = (1 - distance * distance) * (.38f + .62f / (1 + 4 * (x * x + z * z)));
        float textureX = (skin.ordinal() % 2) * .5f + .25f + (skin == Skin.BLACK ? -u : u) * .248f;
        float textureY = (skin.ordinal() / 2) * .5f + .25f + v * .248f;
        if (skin == Skin.BLACK && face == 1) textureY = .40f;
        out.addVertex(pose.last(), u * radius, height + motion * flex, v * radius)
                .setColor(255, 255, 255, 255).setUv(skins.getU(textureX), skins.getV(textureY)).setOverlay(overlay)
                .setLight(light).setNormal(pose.last(), 0, face, 0);
    }

    private static float[] trig(boolean sine) {
        float[] values = new float[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / SEGMENTS;
            values[i] = (float) (sine ? Math.sin(angle) : Math.cos(angle));
        }
        return values;
    }

    private record Stick(Vec3 contact, Vec3 normal, float strength, int hand, long time) {}

    private static final class Motion {
        private final Strike[] drums = new Strike[DRUMS.length];
        private final Stick[] sticks = new Stick[2];
        private final StageCustomCompleteClient.Motion complete;

        Motion(StageCustomBlock.Entity kit) {
            complete = StageCustomBlock.isComplete(kit.getBlockState()) ? new StageCustomCompleteClient.Motion(kit.hatOpen()) : null;
        }

        void strike(Strike impact) {
            drums[impact.drum().ordinal()] = impact;
            if (impact.drum() != Drum.BASS) {
                var shell = StageCustomLayout.shell(impact.drum());
                Vec3 point = shell.point(impact.u() * shell.headRadius(), impact.v() * shell.headRadius(), 0);
                sticks[impact.hand()] = new Stick(point, shell.normal(), impact.strength(), impact.hand(), impact.time());
            }
        }

        void strike(CymbalStrike impact) {
            if (complete == null) return;
            complete.latest[impact.cymbal().ordinal()] = impact;
            var cymbal = StageCustomCymbals.plate(impact.cymbal());
            double u = impact.u() * cymbal.radius(), v = impact.v() * cymbal.radius();
            double lift = impact.voice() == Voice.HAT_OPEN ? StageCustomCymbals.openGap() : 0;
            Vec3 point = cymbal.point(u, v, cymbal.profile(Math.hypot(u, v)) + lift);
            sticks[impact.hand()] = new Stick(point, cymbal.normal(), impact.strength(), impact.hand(), impact.time());
        }
    }
}
