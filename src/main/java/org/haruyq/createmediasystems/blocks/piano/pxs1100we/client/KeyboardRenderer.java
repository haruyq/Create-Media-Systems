package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.CompositeModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlockEntity;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.PxS1100Feature;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardKeys;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

final class KeyboardRenderer implements BlockEntityRenderer<KeyboardBlockEntity> {
    private static final KeyboardLayout MODEL = KeyboardLayout.PX;
    private static final KeyboardKeys REST = new KeyboardKeys();
    private static final List<String> LEDS = List.of("power", "function", "sound_mode", "metronome", "play", "rec",
            "grand_piano", "elec_piano");
    private static final RenderType LAYER = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    private static final Map<String, ModelResourceLocation> MODELS = new java.util.HashMap<>();
    private final Map<KeyboardBlockEntity, Motion> motions = new WeakHashMap<>();
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
        var keys = demo != null ? demo.keys : instrument == null ? REST : instrument.keys;
        var motion = motions.computeIfAbsent(keyboard, ignored -> new Motion());
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
        if (rack && distance > 24 * 24) {
            draw(stand ? "full" : "full_tabletop", pose, out, light, overlay);
            pose.popPose();
            return;
        }
        if (stand) {
            draw("stand", pose, out, light, overlay);
            pose.pushPose();
            pose.translate(KeyboardLayout.PEDAL.x, KeyboardLayout.PEDAL.y, KeyboardLayout.PEDAL.z);
            int pedal = Math.max(keys.pedalAmount(KeyboardMidi.SUSTAIN),
                    Math.max(keys.pedalAmount(KeyboardMidi.SOSTENUTO), keys.pedalAmount(KeyboardMidi.SOFT)));
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
        renderKeys(motion, keys, pose, out, light, overlay);
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
                    case "play" -> view.getBoolean("Playing");
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

    private void renderKeys(Motion motion, KeyboardKeys keys, PoseStack pose, VertexConsumer out, int light, int overlay) {
        if (!motion.update(keys)) {
            draw("keys_static", pose, out, light, overlay);
            return;
        }
        cacheRestingQuads();
        for (int i = 0; i < MODEL.keys.size(); i++) {
            float amount = motion.amount[i];
            if (amount == 0 && !restingQuads.isEmpty()) {
                for (var quad : restingQuads.get(i)) {
                    out.putBulkData(pose.last(), quad, 1, 1, 1, 1, light, overlay);
                }
                continue;
            }
            var key = MODEL.keys.get(i);
            pose.pushPose();
            pose.translate(key.pivot().x, key.pivot().y, key.pivot().z);
            if (amount != 0) pose.mulPose(Axis.XP.rotationDegrees(MODEL.tilt * amount));
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
        var random = RandomSource.create(42);
        var groups = List.of(white.getQuads(null, null, random, ModelData.EMPTY, null),
                black.getQuads(null, null, random, ModelData.EMPTY, null));
        var slices = new ArrayList<List<BakedQuad>>(MODEL.keys.size());
        int[] offsets = {0, 0};
        for (var key : MODEL.keys) {
            int group = key.black() ? 1 : 0;
            var quads = groups.get(group);
            int start = offsets[group];
            int end = start + model(key.model()).getQuads(null, null, random, ModelData.EMPTY, null).size();
            if (end > quads.size()) return;
            slices.add(quads.subList(start, end));
            offsets[group] = end;
        }
        if (offsets[0] == groups.get(0).size() && offsets[1] == groups.get(1).size()) {
            restingQuads = List.copyOf(slices);
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
        return new AABB(keyboard.getBlockPos()).minmax(new AABB(KeyboardBlock.bass(keyboard.getBlockPos(), keyboard.state()))).inflate(.15);
    }

    private static final class Motion {
        final float[] amount = new float[88];
        private long last = System.nanoTime() - 16_000_000L;

        boolean update(KeyboardKeys keys) {
            long now = System.nanoTime();
            float elapsed = (now - last) * 1e-9f;
            last = now;
            boolean moving = false;
            for (int i = 0; i < amount.length; i++) {
                boolean pressed = keys.pressed(KeyboardLayout.FIRST + i);
                amount[i] = Mth.approach(amount[i], pressed ? 1 : 0, elapsed / (pressed ? .04f : .07f));
                moving |= amount[i] != 0;
            }
            return moving;
        }
    }
}
