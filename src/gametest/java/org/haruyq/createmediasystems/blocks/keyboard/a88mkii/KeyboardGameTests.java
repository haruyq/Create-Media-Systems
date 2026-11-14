package org.haruyq.createmediasystems.blocks.keyboard.a88mkii;

import java.util.Map;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.Streams;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
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

    /** Represent a logged-in performer to the relay without a network login (which needs Sable's handshake). */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(Vec3.atCenterOf(helper.absolutePos(KEYBOARD.north())));
        player.setShiftKeyDown(false);
        var players = onlinePlayers(helper);
        players.put(player.getUUID(), player);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { leave(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { leave(); }

            private void leave() {
                players.remove(player.getUUID(), player);
            }
        });
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> onlinePlayers(GameTestHelper helper) {
        try {
            var field = PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            return (Map<UUID, ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to register GameTest players for the keyboard relay", e);
        }
    }

    private static KeyboardPackets.Played played(MidiKeyboardBlock.Entity keyboard, KeyboardPackets.Session session, int message) {
        return new KeyboardPackets.Played(keyboard.getBlockPos(), session.part, message);
    }

    @GameTest(template = "empty")
    public static void playersShareTheKeyboardAndLeaveIndependently(GameTestHelper helper) {
        MidiKeyboardBlock.Entity keyboard = keyboard(helper);
        ServerPlayer first = player(helper, "keyboard_first"), second = player(helper, "keyboard_second");
        keyboard.sit(first);
        keyboard.sit(second);
        var firstPart = KeyboardPackets.session(first.getUUID());
        var secondPart = KeyboardPackets.session(second.getUUID());
        helper.assertTrue(keyboard.performers().size() == 2 && firstPart != null && secondPart != null
                && firstPart.part != secondPart.part, "two players can sit down with distinct parts");

        Panel panel = new Panel();
        panel.octave = 2;
        keyboard.setPanel(second, panel.bytes());
        helper.assertTrue(keyboard.panel().octave == 2, "either performer can work the panel");

        int note = Controller.message(Controller.NOTE_ON, 0, 60, 100);
        KeyboardPackets.relay(first, played(keyboard, firstPart, note));
        KeyboardPackets.relay(second, played(keyboard, secondPart, note));
        helper.assertTrue(firstPart.held.containsValue(note) && secondPart.held.containsValue(note), "both can play middle C");
        KeyboardPackets.relay(first, played(keyboard, secondPart, Controller.message(Controller.NOTE_OFF, 0, 60, 0)));
        helper.assertTrue(secondPart.held.containsValue(note), "an input cannot release another performer's note");
        keyboard.leave(first);
        helper.assertTrue(KeyboardPackets.session(first.getUUID()) == null && secondPart.held.containsValue(note)
                && keyboard.performers().contains(second.getUUID()), "a performer leaving keeps the other part");
        second.setPos(second.position().add(0, 0, -20));
        helper.runAfterDelay(Streams.RELAY_TICKS + 1, () -> {
            helper.assertTrue(keyboard.performers().isEmpty() && KeyboardPackets.session(second.getUUID()) == null,
                    "the last performer walked away");
            helper.succeed();
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
