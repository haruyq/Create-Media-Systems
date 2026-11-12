package org.haruyq.createmediasystems.keyboard;

import java.util.Arrays;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.Streams;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class KeyboardGameTests {
    private static final BlockPos KEYBOARD = new BlockPos(1, 2, 1);

    private static MidiKeyboardBlock.Entity keyboard(GameTestHelper helper) {
        helper.setBlock(KEYBOARD, KeyboardFeature.KEYBOARD.get());
        return helper.getBlockEntity(KEYBOARD);
    }

    /** A player of this test's own, standing at the keyboard. */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(Vec3.atCenterOf(helper.absolutePos(KEYBOARD.north())));
        player.setShiftKeyDown(false);
        return player;
    }

    private static KeyboardPackets.Played played(MidiKeyboardBlock.Entity keyboard, int message) {
        return new KeyboardPackets.Played(keyboard.getBlockPos(), message);
    }

    @GameTest(template = "empty")
    public static void onePlayerAtATimePlaysItAndAloneWorksIt(GameTestHelper helper) {
        MidiKeyboardBlock.Entity keyboard = keyboard(helper);
        ServerPlayer first = player(helper, "keyboard_first"), second = player(helper, "keyboard_second");
        keyboard.sit(first);
        keyboard.sit(second);
        helper.assertTrue(keyboard.performer().filter(first.getUUID()::equals).isPresent(), "the first to sit down plays it");

        Panel panel = new Panel();
        panel.octave = 2;
        keyboard.setPanel(second, panel.bytes());
        helper.assertTrue(keyboard.panel().octave == 0, "nobody else sets its panel");
        keyboard.setPanel(first, panel.bytes());
        helper.assertTrue(keyboard.panel().octave == 2, "its player does");

        int program = Controller.message(Controller.PROGRAM, 0, 19, 0), note = Controller.message(Controller.NOTE_ON, 0, 60, 100);
        KeyboardPackets.relay(second, played(keyboard, program));
        KeyboardPackets.relay(first, played(keyboard, note));
        KeyboardPackets.relay(first, played(keyboard, Controller.message(Controller.CONTROL, 0, 91, 80)));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(Arrays.equals(keyboard.setup(), new int[]{Controller.message(Controller.CONTROL, 0, 91, 80)}),
                    "what its player set its sound to is kept, not their notes nor what others send: " + Arrays.toString(keyboard.setup()));
            keyboard.leave(second);
            helper.assertTrue(keyboard.performer().isPresent(), "nobody else gets them up");
            keyboard.leave(first);
            keyboard.sit(second);
            helper.assertTrue(keyboard.performer().filter(second.getUUID()::equals).isPresent(), "once they got up the next sits down");
            KeyboardPackets.relay(first, played(keyboard, program));
            KeyboardPackets.relay(second, played(keyboard, program));
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(keyboard.setup().length == 2 && keyboard.setup()[1] == program, "the programs come after the controllers");
                // Walked off: the keyboard is free again at its next relay.
                second.setPos(second.position().add(0, 0, -20));
                helper.runAfterDelay(Streams.RELAY_TICKS + 1, () -> {
                    helper.assertTrue(keyboard.performer().isEmpty(), "a player who walks off has got up");
                    helper.assertTrue(KeyboardPackets.session(second.getUUID()) == null, "and plays nothing any more");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void itIsHeardFromTheSpeakersLinkedToIt(GameTestHelper helper) {
        MidiKeyboardBlock.Entity keyboard = keyboard(helper);
        BlockPos at = KEYBOARD.above(2);
        helper.setBlock(at, AudioFeature.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.EAST));
        SpeakerBlock.Entity speaker = helper.getBlockEntity(at);
        speaker.configure(helper.getLevel(), keyboard.getBlockPos(), 0.5f, Streams.Channel.LEFT);
        helper.assertTrue(keyboard.outputs().size() == 1, "a speaker linked to it is where it sounds from");
        MidiKeyboardBlock.Output output = keyboard.outputs().get(0);
        helper.assertTrue(output.pos().equals(speaker.getBlockPos()) && output.channel() == Streams.Channel.LEFT
                && output.volume() == 0.5f && output.facing().filter(Direction.EAST::equals).isPresent(), "as the speaker is set: " + output);
        helper.runAfterDelay(3 * Streams.RELAY_TICKS, () -> {
            helper.assertTrue(keyboard.outputs().size() == 1, "for as long as it is linked");
            speaker.configure(helper.getLevel(), null, 0.5f, Streams.Channel.LEFT);
            helper.runAfterDelay(4 * Streams.RELAY_TICKS, () -> {
                helper.assertTrue(keyboard.outputs().isEmpty(), "and no longer once it is not");
                helper.succeed();
            });
        });
    }
}
