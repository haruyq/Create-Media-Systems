package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.RotateSceneInstruction;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;
import org.haruyq.createmediasystems.items.power.PowerFeature;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlockEntity;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Frame;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Routing;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.PxS1100Feature;
import org.haruyq.createmediasystems.stream.Streams.Channel;
import org.jetbrains.annotations.Nullable;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

final class PxS1100Ponder {
    private static final KeyboardLayout MODEL = KeyboardLayout.PX;
    private static final Map<PonderScene, Demo> DEMOS = new WeakHashMap<>();
    private static final int AUDIO_TAIL_TICKS = 18;
    private static final int AUDIO_END_PADDING_TICKS = 12;
    private record Shot(float scale, float offset, float pitch, float yaw) {}

    private static final Shot STAGE = new Shot(1.23f, -.35f, -35, 145);
    private static final Shot KEYS = new Shot(10.25f, -.70f - .72f * (MODEL.fullScale - 1), -49, 174);
    private static final float KEYS_SCREEN_FRACTION = .78f;

    private static PonderScene active;
    private static KeyboardAudio audio;
    private static int time, still;
    private static boolean muted;

    private PxS1100Ponder() {}

    static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(PxS1100Feature.BLOCK.getId())
                .addStoryBoard(Create.asResource("redstone_link"), PxS1100Ponder::studio);
    }

    @Nullable
    static Demo demo(KeyboardBlockEntity keyboard) {
        return keyboard.getLevel() instanceof PonderLevel level ? DEMOS.get(level.scene) : null;
    }

    private static void studio(SceneBuilder builder, SceneBuildingUtil util) {
        var demo = new Demo();
        DEMOS.put(builder.getScene(), demo);
        builder.addInstruction(ponder -> {
            demo.reset();
            ponder.builder().scaleSceneView(STAGE.scale());
            ponder.builder().setSceneOffsetY(STAGE.offset());
        });
        var scene = plate(builder, "px_s1100", "Performing with the Hayacio PX-S1100WE");
        scene.scaleSceneView(STAGE.scale());
        scene.setSceneOffsetY(STAGE.offset());
        var root = util.grid().at(2, 1, 2);
        var state = PxS1100Feature.BLOCK.get().defaultBlockState().setValue(KeyboardBlock.FULL_SIZE, true);
        var keys = KeyboardBlock.point(root, state, new Vec3(1, .72, .43));
        var power = KeyboardBlock.point(root, state, MODEL.power);
        var dcIn = KeyboardBlock.point(root, state, MODEL.dcIn);
        var usb = KeyboardBlock.point(root, state, MODEL.usbMidi);
        var lineLeft = KeyboardBlock.point(root, state, MODEL.lineLeft);
        var lineRight = KeyboardBlock.point(root, state, MODEL.lineRight);

        scene.world().setBlocks(util.select().fromTo(0, 0, 0, 4, 0, 4), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(1, 0, 1, 4, 0, 3), Blocks.DARK_OAK_PLANKS.defaultBlockState(), false);
        var rug = util.select().fromTo(1, 1, 1, 3, 1, 1);
        scene.world().setBlocks(rug, Blocks.RED_CARPET.defaultBlockState(), false);
        scene.world().showSection(rug, Direction.DOWN);
        for (var part : KeyboardBlock.parts(state)) {
            scene.world().setBlock(part.at(root, state), state.setValue(KeyboardBlock.PART, part), false);
        }
        scene.world().showSection(util.select().fromTo(root.offset(-1, 0, 0), root.offset(1, 1, 0)), Direction.DOWN);
        scene.special().movePointOfInterest(keys);
        scene.idle(16);

        scene.rotateCameraY(180);
        scene.idle(24);
        scene.overlay().showControls(dcIn, Pointing.DOWN, 55).rightClick().withItem(new ItemStack(PowerFeature.BATTERY.get()));
        say(scene, 80, dcIn, "Feed Forge Energy into the rear of the bass side, or use a charged battery.");
        say(scene, 75, usb, PonderPalette.BLUE, "Connect a MIDI keyboard to your computer. Select the device in Settings.");
        scene.rotateCameraY(-180);
        scene.idle(20);

        scene.overlay().showControls(power, Pointing.DOWN, 28).rightClick();
        scene.addInstruction(ignored -> {
            demo.view.putLong("Flash", Long.MAX_VALUE);
            demo.view.putBoolean("Powered", true);
        });
        scene.idle(4);
        scene.addInstruction(ignored -> demo.view.remove("Flash"));
        scene.effects().indicateSuccess(root);
        scene.overlay().showControls(keys, Pointing.DOWN, 28).rightClick();
        say(scene, 70, keys, "Right-click to play with the mouse, computer keyboard, or MIDI. Space works the damper.");
        scene.addInstruction(ignored -> demo.keys.apply(PianoEvent.control(PianoEvent.DAMPER, 103)));
        scene.idle(10);
        scene.addInstruction(ignored -> demo.keys.apply(PianoEvent.control(PianoEvent.DAMPER, 0)));

        var left = util.grid().at(4, 1, 3);
        var right = util.grid().at(0, 1, 3);
        var speaker = AudioFeature.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
        var quill = new ItemStack(AudioFeature.RANGE_QUILL.get());
        scene.world().setBlock(left, speaker, false);
        scene.world().setBlock(right, speaker, false);
        scene.world().showSection(util.select().position(left), Direction.WEST);
        scene.idle(8);
        scene.world().showSection(util.select().position(right), Direction.EAST);
        scene.idle(14);
        scene.overlay().showControls(lineLeft, Pointing.DOWN, 35).rightClick().withItem(quill);
        say(scene, 80, lineLeft, "Pick the piano with the Jukebox Range Quill, then right-click each Speaker to link it.");
        connect(scene, util, root, left, lineLeft, Channel.LEFT, PonderPalette.GREEN, quill, "Left");
        connect(scene, util, root, right, lineRight, Channel.RIGHT, PonderPalette.BLUE, quill, "Right");
        scene.addInstruction(ignored -> demo.external = true);
        say(scene, 85, keys, PonderPalette.GREEN, "Set Left and Right for stereo. Use Mix for one speaker. Linked speakers take over the sound.");
        scene.overlay().showText(75).attachKeyFrame().pointAt(keys).placeNearTarget()
                .text("U.N. Owen Was Her? (MIDI © chishige 2016)");
        playScore(scene, demo);
        scene.idle(AUDIO_TAIL_TICKS);
        scene.addInstruction(ignored -> demo.view.putBoolean("Powered", false));
        scene.markAsFinished();
    }

    private static void connect(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos root, BlockPos speaker,
            Vec3 jack, Channel channel, PonderPalette color, ItemStack quill, String name) {
        var mouth = SpeakerBlock.mouth(speaker, Direction.NORTH);
        scene.overlay().showControls(util.vector().topOf(speaker), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.world().modifyBlockEntityNBT(util.select().position(speaker), SpeakerBlock.Entity.class, tag -> {
            tag.putLong("Source", root.asLong());
            tag.putInt("Channel", channel.ordinal());
        });
        scene.overlay().showLine(color, jack, mouth, 45);
        scene.overlay().showText(38).colored(color).pointAt(mouth).placeNearTarget().text(name);
        scene.idle(26);
    }

    private static void zoom(CreateSceneBuilder scene, Shot from, Shot to, int ticks) {
        scene.addInstruction(new RotateSceneInstruction(to.pitch(), to.yaw(), false));
        scene.addInstruction(new TickingInstruction(false, ticks) {
            @Override public void tick(PonderScene ponder) {
                super.tick(ponder);
                float progress = 1 - remainingTicks / (float) totalTicks;
                float smooth = progress * progress * (3 - 2 * progress);
                var camera = ponder.builder();
                var window = Minecraft.getInstance().getWindow();
                float fitted = Math.min(KEYS.scale() / MODEL.fullScale, KEYS_SCREEN_FRACTION * window.getGuiScaledWidth()
                        / (30 * (float) MODEL.body.getXsize() * MODEL.fullScale));
                float fromScale = from == KEYS ? Math.max(STAGE.scale(), fitted) : from.scale();
                float toScale = to == KEYS ? Math.max(STAGE.scale(), fitted) : to.scale();
                camera.scaleSceneView(Mth.lerp(smooth, fromScale, toScale));
                camera.setSceneOffsetY(Mth.lerp(smooth, from.offset(), to.offset()));
            }
        });
    }

    private static void playScore(CreateSceneBuilder scene, Demo demo) {
        scene.idle(12);
        scene.addInstruction(ponder -> demo.start = ponder.getCurrentTime());
        zoom(scene, STAGE, KEYS, 24);
        scene.idle(demo.score.duration() + AUDIO_END_PADDING_TICKS);
        zoom(scene, KEYS, STAGE, AUDIO_TAIL_TICKS);
    }

    static void tick(Minecraft mc) {
        PonderScene scene = mc.screen instanceof PonderUI ponder ? ponder.getActiveScene() : null;
        Demo demo = scene == null ? null : DEMOS.get(scene);
        if (demo == null || !demo.view.getBoolean("Powered") || scene.isFinished() || demo.start < 0) {
            close();
            active = null;
            return;
        }
        int now = scene.getCurrentTime();
        var options = mc.options;
        boolean silent = options.getSoundSourceVolume(SoundSource.RECORDS) == 0 || options.getSoundSourceVolume(SoundSource.MASTER) == 0;
        boolean sameScene = active == scene;
        if (sameScene && now == time && silent == muted) {
            if (++still >= 3) {
                demo.keys();
                close();
            }
            return;
        }
        boolean jumped = !sameScene || now != time + 1;
        boolean reset = jumped || silent != muted || !silent && audio == null;
        boolean resume = sameScene && !jumped && !muted && still >= 3;
        active = scene;
        time = now;
        still = 0;
        muted = silent;
        if (silent) {
            close();
            int frame = (now - demo.start) * PonderScore.FRAMES_PER_TICK;
            if (reset) demo.seek(frame);
            else demo.advance(frame);
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        var ear = camera.getPosition();
        var pan = Vec3.directionFromRotation(0, camera.getYRot() - 90).scale(demo.external ? 3.4 : .9);
        var frame = new Frame(ear.add(pan), ear.subtract(pan));
        if (reset) {
            close();
            int startFrame = resume ? demo.visibleFrame + 1 : (now - demo.start) * PonderScore.FRAMES_PER_TICK;
            demo.seek(startFrame);
            audio = new KeyboardAudio(demo.settings, frame, demo.score, startFrame);
        }
        audio.tick(mc, frame, Routing.INTERNAL);
    }

    private static void close() {
        if (audio != null) audio.close();
        audio = null;
    }

    static final class Demo {
        private static final int ATTACK_FRAMES = KeyboardSynth.RATE / 100;
        private static final int RELEASE_FRAMES = KeyboardSynth.RATE * 30 / 1000;
        final CompoundTag view = new CompoundTag();
        final PonderScore score = PonderScore.load();
        PianoState keys;
        KeyboardSettings settings;
        PonderScore.Cursor cursor;
        int start, visibleFrame;
        boolean external;
        private final int[] lastChange = new int[PianoEvent.KEYS];

        Demo() { reset(); }

        void reset() {
            view.putBoolean("Powered", false);
            view.putInt("Energy", 100_000);
            view.remove("Flash");
            settings = new KeyboardSettings();
            settings.volume = 82;
            settings.polyphony = 64;
            start = -1;
            seek(-1);
            external = false;
        }

        void seek(int frame) {
            keys = new PianoState();
            cursor = score.cursor();
            visibleFrame = -1;
            Arrays.fill(lastChange, -RELEASE_FRAMES - 1);
            advance(frame);
        }

        void advance(int frame) {
            if (frame <= visibleFrame) return;
            cursor.advance(frame, this::apply);
            visibleFrame = frame;
        }

        private void apply(PonderScore.Cue cue) {
            for (var event : cue.events()) {
                int note = event.number();
                if (event.kind() == PianoEvent.Kind.CONTROL) {
                    keys.apply(event);
                    if (note == PianoEvent.ALL_SOUND_OFF || note == PianoEvent.ALL_NOTES_OFF) {
                        Arrays.fill(lastChange, cue.frame() - RELEASE_FRAMES);
                    }
                    continue;
                }
                boolean down = keys.pressed(note);
                keys.apply(event);
                if (event.kind() == PianoEvent.Kind.ON || keys.pressed(note) != down) {
                    lastChange[note - KeyboardLayout.FIRST] = cue.frame();
                }
            }
        }

        float travel(int note) {
            int elapsed = visibleFrame - lastChange[note - KeyboardLayout.FIRST];
            if (keys.pressed(note)) return Math.min(1, (elapsed + 1) / (float) ATTACK_FRAMES);
            return Math.max(0, 1 - elapsed / (float) RELEASE_FRAMES);
        }

        boolean movingKeys() {
            for (int note = KeyboardLayout.FIRST; note <= KeyboardLayout.LAST; note++) {
                if (travel(note) > 0) return true;
            }
            return false;
        }

        boolean playing() {
            return start >= 0 && visibleFrame >= 0 && visibleFrame < score.duration() * PonderScore.FRAMES_PER_TICK;
        }

        PianoState keys() {
            if (active != null && DEMOS.get(active) == this && audio != null) {
                advance(audio.audibleFrame(System.nanoTime()));
            }
            return keys;
        }
    }
}
