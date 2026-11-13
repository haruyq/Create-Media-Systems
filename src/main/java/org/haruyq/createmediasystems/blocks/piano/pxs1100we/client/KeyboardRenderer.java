package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.CompositeModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.piano.client.KeyMotion;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlockEntity;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.PxS1100Feature;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardKeys;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.jetbrains.annotations.Nullable;

final class KeyboardRenderer implements BlockEntityRenderer<KeyboardBlockEntity> {
    private static final KeyboardLayout MODEL = KeyboardLayout.PX;
    private static final KeyboardKeys REST = new KeyboardKeys();
    private static final List<String> LEDS = List.of("power", "function", "sound_mode", "metronome", "play", "rec",
            "grand_piano", "elec_piano");
    private static final RenderType LAYER = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    private static final float BLACK_KEY_TILT = -7;
    private static final Map<String, ModelResourceLocation> MODELS = new HashMap<>();
    private BakedModel staticKeys;
    private List<List<BakedQuad>> restingQuads = List.of();

    static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(PxS1100Feature.ENTITY.get(), context -> new KeyboardRenderer()));
        bus.addListener((ModelEvent.RegisterAdditional event) -> {
            var names = new HashSet<>(List.of("stand", "body", "body_tabletop", "full", "full_tabletop",
                    "keys_static", "volume_knob", "power_button", "music_stand", "pedal"));
            MODEL.keys.forEach(key -> names.add(key.model()));
            LEDS.forEach(led -> names.add("led_" + led));
            for (String name : names) {
                var model = MODELS.computeIfAbsent(name,
                        part -> ModelResourceLocation.standalone(CreateMediaSystems.id("block/piano/pxs1100we/" + part)));
                event.register(model);
            }
        });
    }

    @Override public void render(KeyboardBlockEntity keyboard, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var state = keyboard.state();
        var demo = PxS1100Ponder.demo(keyboard);
        var instrument = demo == null ? PxS1100Client.instrument(keyboard.target()) : null;
        var view = demo != null ? demo.view : instrument == null ? keyboard.clientView() : instrument.view;
        var settings = demo != null ? demo.settings
                : instrument == null ? KeyboardSettings.load(view.getCompound("Settings")) : instrument.settings;
        var keys = instrument == null ? REST : instrument.keys;
        var demoKeys = demo == null ? null : demo.keys();
        var motion = instrument == null ? null : instrument.motion;
        long renderTime = System.nanoTime();
        boolean stand = state.getValue(KeyboardBlock.STAND), rack = state.getValue(KeyboardBlock.RACK);
        boolean powered = view.getBoolean("Powered");
        long now = keyboard.getLevel() == null ? 0 : keyboard.getLevel().getGameTime();
        var mc = Minecraft.getInstance();
        double distance = instrument != null && instrument.frame != null
                ? instrument.frame.centre().distanceToSqr(mc.gameRenderer.getMainCamera().getPosition())
                : keyboard.target().mounted() || keyboard.getLevel() != mc.level ? 0
                : org.haruyq.createmediasystems.SableCompat.toRenderWorld(mc.level, keyboard.getBlockPos(),
                        keyboard.getBlockPos().getCenter()).distanceToSqr(mc.gameRenderer.getMainCamera().getPosition());
        var out = buffers.getBuffer(LAYER);
        pose.pushPose();
        var right = KeyboardBlock.right(state);
        var up = KeyboardBlock.up(state);
        var back = state.getValue(KeyboardBlock.FACING).getOpposite();
        pose.translate(.5, .5, .5);
        pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(
                right.getStepX(), right.getStepY(), right.getStepZ(),
                up.getStepX(), up.getStepY(), up.getStepZ(),
                back.getStepX(), back.getStepY(), back.getStepZ())));
        pose.translate(-.5, -.5, -.5);
        if (state.getValue(KeyboardBlock.FULL_SIZE)) {
            var origin = KeyboardBlock.modelToLocal(state, Vec3.ZERO);
            pose.translate(origin.x, origin.y, origin.z);
            pose.scale(MODEL.fullScale, MODEL.fullScale, MODEL.fullScale);
        }
        if (rack && distance > 24 * 24 && (motion == null || !motion.moving(renderTime))) {
            draw(stand ? "full" : "full_tabletop", pose, out, light, overlay);
            pose.popPose();
            return;
        }
        if (stand) {
            draw("stand", pose, out, light, overlay);
            pose.pushPose();
            pose.translate(KeyboardLayout.PEDAL.x, KeyboardLayout.PEDAL.y, KeyboardLayout.PEDAL.z);
            int pedal = demoKeys == null
                    ? Math.max(keys.pedalAmount(KeyboardMidi.SUSTAIN),
                            Math.max(keys.pedalAmount(KeyboardMidi.SOSTENUTO), keys.pedalAmount(KeyboardMidi.SOFT)))
                    : Math.max(demoKeys.pedal(PianoEvent.DAMPER),
                            Math.max(demoKeys.pedal(PianoEvent.SOSTENUTO), demoKeys.pedal(PianoEvent.SOFT)));
            if (pedal > 0) {
                pose.translate(0, 0, .025);
                pose.mulPose(Axis.XP.rotationDegrees(-9 * pedal / 127f));
                pose.translate(0, 0, -.025);
            }
            draw("pedal", pose, out, light, overlay);
            pose.popPose();
        }
        draw(stand ? "body" : "body_tabletop", pose, out, light, overlay);
        if (!stand) pose.translate(0, MODEL.tabletop, 0);
        if (rack) draw("music_stand", pose, out, light, overlay);
        renderKeys(motion, demo, renderTime, pose, out, light, overlay);
        pose.pushPose();
        pose.translate(MODEL.knob.x, MODEL.knob.y, MODEL.knob.z);
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(settings.volume / 127f, 150, -150)));
        draw("volume_knob", pose, out, light, overlay);
        pose.popPose();
        pose.pushPose();
        pose.translate(MODEL.power.x, MODEL.power.y + (now < view.getLong("Flash") ? MODEL.powerTravel : 0), MODEL.power.z);
        draw("power_button", pose, out, light, overlay);
        pose.popPose();
        if (powered) {
            pose.translate(0, .001, 0);
            for (String led : LEDS) {
                boolean on = switch (led) {
                    case "power" -> view.getInt("Energy") >= 12000 || (now / 6) % 2 == 0;
                    case "function" -> view.getBoolean("Browser");
                    case "sound_mode" -> settings.reverb > 0 || settings.chorus > 0;
                    case "metronome" -> view.getBoolean("Metro");
                    case "play" -> demo == null ? view.getBoolean("Playing") : demo.playing();
                    case "rec" -> view.getBoolean("Recording");
                    case "grand_piano" -> settings.main.bank() == 0 && settings.main.program() == 0;
                    case "elec_piano" -> settings.main.bank() == 0 && settings.main.program() == 4;
                    default -> false;
                };
                if (on) draw("led_" + led, pose, out, LightTexture.FULL_BRIGHT, overlay);
            }
        }
        pose.popPose();
    }

    private void renderKeys(@Nullable KeyMotion motion, @Nullable PxS1100Ponder.Demo demo, long now,
            PoseStack pose, VertexConsumer out, int light, int overlay) {
        if (demo == null ? motion == null || !motion.moving(now) : !demo.movingKeys()) {
            draw("keys_static", pose, out, light, overlay);
            return;
        }
        cacheRestingQuads();
        for (int i = 0; i < MODEL.keys.size(); i++) {
            var key = MODEL.keys.get(i);
            float amount = demo == null ? motion.travel(key.note(), now) : demo.travel(key.note());
            if (amount == 0 && !restingQuads.isEmpty()) {
                for (var quad : restingQuads.get(i)) {
                    out.putBulkData(pose.last(), quad, 1, 1, 1, 1, light, overlay);
                }
                continue;
            }
            pose.pushPose();
            pose.translate(key.pivot().x, key.pivot().y, key.pivot().z);
            if (amount != 0) {
                float tilt = key.black() ? BLACK_KEY_TILT : MODEL.tilt;
                pose.mulPose(Axis.XP.rotationDegrees(tilt * amount));
            }
            draw(key.model(), pose, out, light, overlay);
            pose.popPose();
        }
    }

    private void cacheRestingQuads() {
        var model = model("keys_static");
        if (model == staticKeys) return;
        staticKeys = model;
        restingQuads = List.of();
        if (!(model instanceof CompositeModel.Baked parts)) return;
        var white = parts.getPart("solid");
        var black = parts.getPart("black_keys");
        if (white == null || black == null) return;
        var slices = new ArrayList<List<BakedQuad>>(MODEL.keys.size());
        var whites = MODEL.keys.stream().filter(key -> !key.black()).toList();
        var blacks = MODEL.keys.stream().filter(KeyboardLayout.Key::black).toList();
        var byNote = new HashMap<Integer, List<BakedQuad>>();
        indexKeys(white, whites, byNote);
        indexKeys(black, blacks, byNote);
        for (var key : MODEL.keys) {
            var quads = byNote.get(key.note());
            int expected = model(key.model()).getQuads(null, null, RandomSource.create(42), ModelData.EMPTY, null).size();
            if (quads.size() != expected) return;
            slices.add(List.copyOf(quads));
        }
        restingQuads = List.copyOf(slices);
    }

    private static void indexKeys(BakedModel part, List<KeyboardLayout.Key> keys, Map<Integer, List<BakedQuad>> byNote) {
        keys.forEach(key -> byNote.put(key.note(), new ArrayList<>()));
        var quads = part.getQuads(null, null, RandomSource.create(42), ModelData.EMPTY, null);
        int stride = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        for (var quad : quads) {
            var vertices = quad.getVertices();
            float x = 0;
            for (int at = 0; at < vertices.length; at += stride) x += Float.intBitsToFloat(vertices[at]);
            x /= vertices.length / stride;
            var nearest = keys.getFirst();
            for (var key : keys) {
                if (Math.abs(x - key.pivot().x) < Math.abs(x - nearest.pivot().x)) nearest = key;
            }
            byNote.get(nearest.note()).add(quad);
        }
    }

    private static BakedModel model(String name) {
        return Minecraft.getInstance().getModelManager().getModel(MODELS.get(name));
    }

    private static void draw(String name, PoseStack pose, VertexConsumer out, int light, int overlay) {
        var mc = Minecraft.getInstance();
        mc.getItemRenderer().renderModelLists(model(name), ItemStack.EMPTY, light, overlay, pose, out);
    }

    @Override public AABB getRenderBoundingBox(KeyboardBlockEntity keyboard) {
        var pos = keyboard.getBlockPos();
        var state = keyboard.state();
        var bounds = new AABB(pos);
        for (var part : KeyboardBlock.parts(state)) bounds = bounds.minmax(new AABB(part.at(pos, state)));
        return bounds.inflate(.15);
    }

}
