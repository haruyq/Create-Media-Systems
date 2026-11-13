package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.util.UUID;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.ControlDrag;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardFeature;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.KeyboardPackets;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.MidiKeyboardBlock;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Panel;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The keyboards on a client: what they sound like (see {@link KeyboardAudio}), and this client's player at one of
 * them. Right-clicking a keyboard anywhere on its three blocks asks the server to sit down at it; once the server
 * says this player plays it, its {@link KeyboardScreen} opens and this computer's MIDI inputs play it, until the
 * screen is closed or the server says otherwise.
 */
public final class KeyboardClient implements ClientFeature {
    private static final ModConfigSpec.ConfigValue<String> MIDI_DEVICE = ClientConfig.BUILDER
            .comment("The MIDI input a keyboard block is played from while you sit at it: part of the device's name, like 'A-88'. Empty takes every MIDI input of this computer.")
            .define("midiDevice", "");

    /** The keyboard this client's player plays; null for none. */
    @Nullable
    private static Playing playing;
    /** A keyboard this player just got up from, until the server has taken note: it isn't sat down at again. */
    @Nullable
    private static BlockPos left;

    /** This client's player at a keyboard: what they play goes to the synth here and to the server, at once. */
    static final class Playing implements Controller.Out {
        final BlockPos pos;
        final Controller controller;
        private volatile boolean panelChanged;

        Playing(MidiKeyboardBlock.Entity keyboard) {
            pos = keyboard.getBlockPos();
            controller = new Controller(new Panel(keyboard.panel().bytes()), this);
        }

        @Override
        public void send(int message) {
            KeyboardAudio.played(pos, message);
            tell(new KeyboardPackets.Played(pos, message));
        }

        @Override
        public void panelChanged() {
            panelChanged = true;
        }
    }

    @Override
    public void register(IEventBus modBus) {
        KeyboardRenderer.register(modBus);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        ControlDrag.onUse(KeyboardClient::use);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(KeyboardFeature.KEYBOARD.getId())
                .addStoryBoard(Create.asResource("redstone_link"), KeyboardClient::keyboard);
    }

    private static void keyboard(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "midi_keyboard", "The MIDI Keyboard");

        BlockPos keyboard = util.grid().at(2, 2, 2);
        for (int x = 1; x <= 3; x++) {
            scene.world().setBlock(util.grid().at(x, 1, 2), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
        }
        scene.world().showSection(util.select().fromTo(1, 1, 2, 3, 1, 2), Direction.DOWN);
        scene.idle(10);
        scene.world().setBlock(keyboard, KeyboardFeature.KEYBOARD.get().defaultBlockState()
                .setValue(MidiKeyboardBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(keyboard), Direction.DOWN);
        scene.idle(15);
        Vec3 keys = util.vector().centerOf(keyboard).add(0, -0.25, 0);
        scene.overlay().showControls(keys, Pointing.DOWN, 50).rightClick();
        say(scene, 120, keys, "The keyboard is three blocks wide in one block: set it on a desk with room to either side. Right-click it to sit down and play. One player plays it at a time, until they close the screen.");
        say(scene, 120, keys, PonderPalette.GREEN, "Its keys, pads, knobs and buttons are worked with the mouse, and the keys with the computer's keyboard too. A real MIDI keyboard plugged into the computer plays it as well.");

        for (int side = -1; side <= 1; side += 2) {
            BlockPos speaker = util.grid().at(2 + 2 * side, 1, 3);
            scene.world().setBlock(speaker, AudioFeature.SPEAKER.get().defaultBlockState()
                    .setValue(SpeakerBlock.FACING, Direction.NORTH), false);
            scene.world().showSection(util.select().position(speaker), Direction.DOWN);
            int channel = (side < 0 ? Streams.Channel.RIGHT : Streams.Channel.LEFT).ordinal();
            scene.world().modifyBlockEntityNBT(util.select().position(speaker), SpeakerBlock.Entity.class, nbt -> {
                nbt.put("Source", NbtUtils.writeBlockPos(keyboard));
                nbt.putInt("Channel", channel);
            });
            scene.idle(10);
            scene.overlay().showControls(util.vector().topOf(speaker), Pointing.DOWN, 30).rightClick()
                    .withItem(new ItemStack(AudioFeature.RANGE_QUILL.get()));
            scene.idle(20);
        }
        say(scene, 130, keys, "It sounds from itself. To play it through speakers, pick it with a Range Quill, then right-click each speaker: set one to the left channel and one to the right.");
        notes(scene, keys.add(0, 0.3, 0), 60);
        say(scene, 110, keys, PonderPalette.GREEN, "Everyone nearby hears and sees what is played, as it is played. The pads pick the sound: 128 of them, in 16 banks of 8.");
        PonderPerformance.play(scene, keys);
    }

    /** The player of the keyboard at {@code pos} played {@code message}, as the server passed it on. Network thread. */
    public static void played(BlockPos pos, int message) {
        KeyboardAudio.played(pos, message);
    }

    /** How {@code keyboard}'s panel looks here: as this player has it, if they play it, a tick ahead of the server. */
    static PanelLook look(MidiKeyboardBlock.Entity keyboard) {
        Playing own = playing;
        return own != null && own.pos.equals(keyboard.getBlockPos())
                ? new PanelLook(own.controller.panel(), keyboard.padsDown, own.controller)
                : new PanelLook(keyboard.panel(), keyboard.padsDown, null);
    }

    /** Opens this computer's MIDI inputs for {@code own}, looking for them anew: one may have been plugged in since. */
    static void listen(Playing own) {
        MidiInput.open(own.controller, MIDI_DEVICE.get());
    }

    /** To the server, from any thread; nothing once the connection is gone. */
    private static void tell(CustomPacketPayload payload) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(payload);
        }
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        KeyboardAudio.tick(mc);
        PonderPerformance.tick(mc);
        UUID me = mc.player == null ? null : mc.player.getUUID();
        MidiKeyboardBlock.Entity mine = null;
        for (MidiKeyboardBlock.Entity keyboard : KeyboardAudio.near().values()) {
            if (keyboard.performer().filter(performer -> performer.equals(me)).isPresent()) {
                mine = keyboard;
            }
        }
        if (left != null && (mine == null || !mine.getBlockPos().equals(left))) {
            left = null;
        }
        if (playing != null && (mine == null || !mine.getBlockPos().equals(playing.pos))) {
            stop(mc);
        }
        if (playing == null && mine != null && !mine.getBlockPos().equals(left) && mc.screen == null) {
            playing = new Playing(mine);
            playing.controller.sendPrograms();
            listen(playing);
            mc.setScreen(new KeyboardScreen(playing));
        }
        if (playing != null && playing.panelChanged && mc.level.getGameTime() % 2 == 0) {
            sendPanel();
        }
    }

    private static void sendPanel() {
        playing.panelChanged = false;
        byte[] panel;
        synchronized (playing.controller) {
            panel = playing.controller.panel().bytes();
        }
        tell(new KeyboardPackets.SetPanel(playing.pos, panel));
    }

    /** This player's screen was closed: they get up. */
    static void closed(Playing closed) {
        if (playing == closed) {
            tell(new KeyboardPackets.Sit(closed.pos, false));
            left = closed.pos;
            stop(Minecraft.getInstance());
        }
    }

    private static void stop(Minecraft mc) {
        Playing was = playing;
        was.controller.releaseKeys();
        if (was.panelChanged) {
            sendPanel();
        }
        playing = null;
        MidiInput.close();
        if (mc.screen instanceof KeyboardScreen screen && screen.playing == was) {
            mc.setScreen(null);
        }
    }

    /**
     * A right-click: taken if the crosshair is on a keyboard's body, whichever of its three blocks that is over (the
     * game itself only finds the middle one), with nothing nearer in the way.
     */
    private static boolean use() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || !ControlDrag.works(mc.player)) {
            return false;
        }
        double nearest = mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS ? Double.MAX_VALUE
                : mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition()) + 0.01;
        MidiKeyboardBlock.Entity aimed = null;
        for (MidiKeyboardBlock.Entity keyboard : KeyboardAudio.near().values()) {
            ControlDrag.Look look = ControlDrag.look(keyboard.getBlockPos());
            Vec3 reach = look.eye().add(look.along().scale(mc.player.blockInteractionRange()));
            double distance = MidiKeyboardBlock.body(keyboard.getBlockPos(), keyboard.facing()).clip(look.eye(), reach)
                    .map(hit -> hit.distanceTo(look.eye())).orElse(Double.MAX_VALUE);
            if (distance < nearest) {
                nearest = distance;
                aimed = keyboard;
            }
        }
        if (aimed == null) {
            return false;
        }
        tell(new KeyboardPackets.Sit(aimed.getBlockPos(), true));
        return true;
    }
}
