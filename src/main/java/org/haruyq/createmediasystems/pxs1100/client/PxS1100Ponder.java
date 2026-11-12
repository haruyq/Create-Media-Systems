package org.haruyq.createmediasystems.pxs1100.client;

import java.util.ArrayList;
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
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.power.PowerFeature;
import org.haruyq.createmediasystems.pxs1100.KeyboardBlock;
import org.haruyq.createmediasystems.pxs1100.KeyboardBlockEntity;
import org.haruyq.createmediasystems.pxs1100.KeyboardKeys;
import org.haruyq.createmediasystems.pxs1100.KeyboardLayout;
import org.haruyq.createmediasystems.pxs1100.KeyboardMidi;
import org.haruyq.createmediasystems.pxs1100.KeyboardPackets.Frame;
import org.haruyq.createmediasystems.pxs1100.KeyboardPackets.Routing;
import org.haruyq.createmediasystems.pxs1100.KeyboardSettings;
import org.haruyq.createmediasystems.pxs1100.PxS1100Feature;
import org.haruyq.createmediasystems.stream.Streams.Channel;
import org.jetbrains.annotations.Nullable;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

final class PxS1100Ponder {
    private static final KeyboardLayout MODEL = KeyboardLayout.PX;
    private static final Map<PonderScene, Demo> DEMOS = new WeakHashMap<>();
    private static final int[][] CHORDS = {{48, 55, 64}, {45, 52, 60}, {43, 50, 62}, {48, 55, 64}};
    private static final int[][] MELODY = {{67, 72, 76, 74}, {69, 72, 77, 76}, {67, 71, 74, 71}, {67, 69, 72, 76}};
    private static final int[] ACCENTS = {96, 75, 89, 78};

    private static PonderScene active;
    private static KeyboardAudio audio;
    private static int time, revision, still;

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
        builder.addInstruction(ignored -> demo.reset());
        var scene = plate(builder, "px_s1100", "Performing with the Hayacio PX-S1100WE");
        var root = util.grid().at(2, 1, 2);
        var state = PxS1100Feature.BLOCK.get().defaultBlockState();
        var bass = KeyboardBlock.bass(root, state);
        var keys = KeyboardBlock.point(root, state, new Vec3(1, .72, .43));
        var power = KeyboardBlock.point(root, state, MODEL.power);
        var dcIn = KeyboardBlock.point(root, state, MODEL.dcIn);
        var usb = KeyboardBlock.point(root, state, MODEL.usbMidi);
        var lineLeft = KeyboardBlock.point(root, state, MODEL.lineLeft);
        var lineRight = KeyboardBlock.point(root, state, MODEL.lineRight);

        scene.world().setBlocks(util.select().fromTo(0, 0, 1, 4, 0, 3), Blocks.DARK_OAK_PLANKS.defaultBlockState(), false);
        scene.world().setBlock(root, state, false);
        scene.world().setBlock(bass, state.setValue(KeyboardBlock.PART, KeyboardBlock.Part.BASS), false);
        scene.world().showSection(util.select().fromTo(root, bass), Direction.DOWN);
        scene.special().movePointOfInterest(keys);
        scene.idle(16);

        scene.rotateCameraY(135);
        scene.idle(24);
        scene.overlay().showControls(dcIn, Pointing.DOWN, 55).rightClick().withItem(new ItemStack(PowerFeature.BATTERY.get()));
        say(scene, 80, dcIn, "Feed Forge Energy into the rear of the bass side, or use a charged battery.");
        say(scene, 75, usb, PonderPalette.BLUE, "Connect a MIDI keyboard to your computer. Select the device in Settings.");
        scene.rotateCameraY(-135);
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
        phrase(scene, demo, 2, List.of(
                KeyboardBlock.point(root, state, MODEL.speakers.get(0)),
                KeyboardBlock.point(root, state, MODEL.speakers.get(1))));

        var left = util.grid().at(4, 1, 3);
        var right = util.grid().at(1, 1, 3);
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
        scene.addInstruction(ignored -> {
            demo.external = true;
            demo.settings.main = new KeyboardSettings.Tone(0, 4);
            demo.settings.chorus = 22;
            demo.apply(List.of());
        });
        say(scene, 85, keys, PonderPalette.GREEN, "Set Left and Right for stereo. Use Mix for one speaker. Linked speakers take over the sound.");
        scene.rotateCameraY(-22);
        scene.idle(8);
        phrase(scene, demo, CHORDS.length, List.of(SpeakerBlock.mouth(left, Direction.NORTH),
                SpeakerBlock.mouth(right, Direction.NORTH)));
        scene.idle(12);
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

    private static void phrase(CreateSceneBuilder scene, Demo demo, int bars, List<Vec3> mouths) {
        for (int bar = 0; bar < bars; bar++) {
            for (int beat = 0; beat < ACCENTS.length; beat++) {
                var down = new ArrayList<KeyboardMidi>();
                if (beat == 0) {
                    down.add(KeyboardMidi.cc(KeyboardMidi.SUSTAIN, 103));
                    for (int note : CHORDS[bar]) down.add(KeyboardMidi.note(note, 67));
                }
                int melody = MELODY[bar][beat];
                down.add(KeyboardMidi.note(melody, ACCENTS[beat]));
                scene.addInstruction(ignored -> demo.apply(down));
                if (beat % 2 == 0) {
                    for (var mouth : mouths) notes(scene, mouth.add(0, .11, 0), 4);
                }
                scene.idle(7);
                var up = new ArrayList<KeyboardMidi>();
                up.add(KeyboardMidi.note(melody, 0));
                if (beat == ACCENTS.length - 1) {
                    for (int note : CHORDS[bar]) up.add(KeyboardMidi.note(note, 0));
                    up.add(KeyboardMidi.cc(KeyboardMidi.SUSTAIN, 0));
                }
                scene.addInstruction(ignored -> demo.apply(up));
                scene.idle(3);
            }
        }
    }

    static void tick(Minecraft mc) {
        PonderScene scene = mc.screen instanceof PonderUI ponder ? ponder.getActiveScene() : null;
        Demo demo = scene == null ? null : DEMOS.get(scene);
        if (demo == null) {
            close();
            active = null;
            return;
        }
        if (!demo.view.getBoolean("Powered") || scene.isFinished()) {
            close();
            return;
        }
        int now = scene.getCurrentTime();
        if (active == scene && now == time) {
            if (++still >= 3) close();
            return;
        }
        boolean reset = audio == null || active != scene || now != time + 1;
        active = scene;
        time = now;
        still = 0;
        var camera = mc.gameRenderer.getMainCamera();
        var ear = camera.getPosition();
        var pan = Vec3.directionFromRotation(0, camera.getYRot() - 90).scale(demo.external ? 3.4 : .9);
        var frame = new Frame(ear.add(pan), ear.subtract(pan));
        if (reset || demo.revision != revision) {
            var settings = KeyboardSettings.load(demo.settings.save());
            if (reset) {
                close();
                audio = new KeyboardAudio(settings, frame);
            }
            audio.update(settings, reset, reset ? List.of() : demo.events, demo.keys::snapshot);
            revision = demo.revision;
        }
        audio.tick(mc, frame, Routing.INTERNAL);
    }

    private static void close() {
        if (audio != null) audio.close();
        audio = null;
    }

    static final class Demo {
        final CompoundTag view = new CompoundTag();
        KeyboardKeys keys;
        KeyboardSettings settings;
        List<KeyboardMidi> events = List.of();
        int revision;
        boolean external;

        void reset() {
            view.putBoolean("Powered", false);
            view.putInt("Energy", 100_000);
            view.remove("Flash");
            settings = new KeyboardSettings();
            settings.volume = 82;
            settings.polyphony = 64;
            keys = new KeyboardKeys();
            external = false;
            apply(List.of());
        }

        void apply(List<KeyboardMidi> events) {
            events.forEach(keys::apply);
            this.events = events;
            revision++;
        }
    }
}
