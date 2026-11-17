package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.Arrays;
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
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
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

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

final class PxS1100Ponder {
    private static final KeyboardLayout MODEL = KeyboardLayout.PX;
    private static final Map<PonderScene, Demo> DEMOS = new WeakHashMap<>();
    /** Ticks the last notes ring on after the piece. */
    private static final int AUDIO_TAIL_TICKS = 40;
    /** How close the performance comes: the keys over this much of the window's width, but no closer than this. */
    private static final float KEYS_SCREEN_FRACTION = .92f, MAX_ZOOM = 12;
    private static final BlockState STATE = PxS1100Feature.BLOCK.get().defaultBlockState().setValue(KeyboardBlock.FULL_SIZE, true);

    private static PonderScene active;
    private static KeyboardAudio audio;
    private static int time, still;
    private static boolean muted;

    private PxS1100Ponder() {}

    static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(PxS1100Feature.BLOCK.getId(), PxS1100Feature.ADAPTER_ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), PxS1100Ponder::studio)
                .addStoryBoard(Create.asResource("redstone_link"), PxS1100Ponder::performance);
    }

    @Nullable
    static Demo demo(KeyboardBlockEntity keyboard) {
        return keyboard.getLevel() instanceof PonderLevel level ? DEMOS.get(level.scene) : null;
    }

    /** The keyboard on its stand in the middle of the plate, its front to the camera, shown; its root. */
    private static BlockPos keyboard(CreateSceneBuilder scene, SceneBuildingUtil util) {
        BlockPos root = util.grid().at(2, 1, 2);
        for (KeyboardBlock.Part part : KeyboardBlock.parts(STATE)) {
            scene.world().setBlock(part.at(root, STATE), STATE.setValue(KeyboardBlock.PART, part), false);
        }
        scene.world().showSection(util.select().fromTo(root.offset(-1, 0, 0), root.offset(1, 1, 0)), Direction.DOWN);
        scene.idle(15);
        return root;
    }

    private static void studio(SceneBuilder builder, SceneBuildingUtil util) {
        Demo demo = new Demo();
        DEMOS.put(builder.getScene(), demo);
        builder.addInstruction(ponder -> demo.reset());
        CreateSceneBuilder scene = plate(builder, "px_s1100", "The Digital Piano");
        BlockPos root = keyboard(scene, util);
        Vec3 keys = KeyboardBlock.point(root, STATE, new Vec3(1, .72, .43));
        Vec3 power = KeyboardBlock.point(root, STATE, MODEL.power);
        Vec3 dcIn = KeyboardBlock.point(root, STATE, MODEL.dcIn);
        Vec3 usb = KeyboardBlock.point(root, STATE, MODEL.usbMidi);

        scene.rotateCameraY(180);
        scene.idle(20);
        scene.overlay().showControls(dcIn, Pointing.DOWN, 50).rightClick().withItem(new ItemStack(PowerFeature.BATTERY.get()));
        say(scene, 100, dcIn, "It needs power: feed Forge Energy into the back of its bass side, or right-click it with a charged Battery.");

        BlockPos adapter = KeyboardBlock.Part.BASS.at(root, STATE).relative(STATE.getValue(KeyboardBlock.FACING).getOpposite());
        scene.world().setBlock(adapter, PxS1100Feature.ADAPTER.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, STATE.getValue(KeyboardBlock.FACING)), false);
        scene.world().showSection(util.select().position(adapter), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(adapter), Pointing.DOWN, 50).rightClick().withItem(new ItemStack(Items.COAL));
        say(scene, 130, util.vector().topOf(adapter), PonderPalette.GREEN, "Or set an AC Adapter on the floor right behind it. The adapter runs on a shaft turning below it, on Forge Energy, or on furnace fuel right-clicked into it.");
        say(scene, 100, usb, "A real MIDI keyboard plugged into the computer plays it too: pick the device in the Settings of its screen.");
        scene.rotateCameraY(-180);
        scene.idle(20);

        scene.overlay().showControls(power, Pointing.DOWN, 30).rightClick();
        scene.addInstruction(ignored -> {
            demo.view.putLong("Flash", Long.MAX_VALUE);
            demo.view.putBoolean("Powered", true);
        });
        scene.idle(4);
        scene.addInstruction(ignored -> demo.view.remove("Flash"));
        scene.effects().indicateSuccess(root);
        scene.overlay().showControls(keys, Pointing.DOWN, 30).rightClick();
        say(scene, 110, keys, "Turn it on with its power button, then right-click it to play with the mouse or the computer's keyboard. Space works the damper.");
        scene.addInstruction(ignored -> demo.keys.apply(PianoEvent.control(PianoEvent.DAMPER, 103)));
        scene.idle(10);
        scene.addInstruction(ignored -> demo.keys.apply(PianoEvent.control(PianoEvent.DAMPER, 0)));

        BlockPos left = util.grid().at(4, 1, 3);
        BlockPos right = util.grid().at(0, 1, 3);
        BlockState speaker = AudioFeature.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
        ItemStack quill = new ItemStack(AudioFeature.RANGE_QUILL.get());
        scene.world().setBlock(left, speaker, false);
        scene.world().setBlock(right, speaker, false);
        scene.world().showSection(util.select().position(left), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(right), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(keys, Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, root,
                new AABB(root.offset(-1, 0, 0)).minmax(new AABB(root.offset(1, 1, 0))), 120);
        scene.idle(35);
        link(scene, util, root, left, Channel.LEFT, quill);
        link(scene, util, root, right, Channel.RIGHT, quill);
        say(scene, 110, util.vector().centerOf(left), "To play it through speakers, pick it with a Range Quill, then right-click each speaker: set one to the left channel and one to the right.");

        Vec3 leftMouth = SpeakerBlock.mouth(left, Direction.NORTH);
        Vec3 rightMouth = SpeakerBlock.mouth(right, Direction.NORTH);
        notes(scene, leftMouth, 80);
        notes(scene, rightMouth, 80);
        scene.overlay().showText(40).pointAt(leftMouth).placeNearTarget().text("Left");
        scene.overlay().showText(40).pointAt(rightMouth).placeNearTarget().text("Right");
        scene.idle(50);
        say(scene, 100, keys, PonderPalette.GREEN, "While speakers are linked, the sound comes from them instead of the piano. Leave one on Mix to play it all from one speaker.");
        scene.addInstruction(ignored -> demo.view.putBoolean("Powered", false));
    }

    private static void link(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos root, BlockPos speaker, Channel channel,
            ItemStack quill) {
        scene.overlay().showControls(util.vector().topOf(speaker), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.world().modifyBlockEntityNBT(util.select().position(speaker), SpeakerBlock.Entity.class, tag -> {
            tag.putLong("Source", root.asLong());
            tag.putInt("Channel", channel.ordinal());
        });
        scene.idle(30);
    }

    /**
     * A scene of its own, so the long piece leaves the other scene's timeline alone: the piano seen from where its
     * player sits, close enough that its keys fill most of the window's width, playing the piece to its end.
     */
    private static void performance(SceneBuilder builder, SceneBuildingUtil util) {
        Demo demo = new Demo();
        DEMOS.put(builder.getScene(), demo);
        builder.addInstruction(ponder -> {
            demo.reset();
            demo.view.putBoolean("Powered", true);
            float width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            ponder.builder().scaleSceneView(Math.max(1, Math.min(MAX_ZOOM / MODEL.fullScale,
                    KEYS_SCREEN_FRACTION * width / (30 * (float) MODEL.body.getXsize() * MODEL.fullScale))));
            ponder.builder().setSceneOffsetY(-.70f - .72f * (MODEL.fullScale - 1));
            ponder.getTransform().xRotation.startWithValue(-36);
            ponder.getTransform().yRotation.startWithValue(180);
        });
        CreateSceneBuilder scene = plate(builder, "px_s1100_performance", "Playing a Piece");
        BlockPos root = keyboard(scene, util);
        Vec3 keys = KeyboardBlock.point(root, STATE, new Vec3(1, .72, .43));
        scene.overlay().showText(100).attachKeyFrame().pointAt(keys).placeNearTarget()
                .text("Here is a piano piece played on it: U.N. Owen Was Her? (MIDI © chishige 2016)");
        scene.addInstruction(ponder -> demo.start = ponder.getCurrentTime());
        scene.idle(demo.score.duration() + AUDIO_TAIL_TICKS);
        scene.addInstruction(ignored -> demo.view.putBoolean("Powered", false));
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
