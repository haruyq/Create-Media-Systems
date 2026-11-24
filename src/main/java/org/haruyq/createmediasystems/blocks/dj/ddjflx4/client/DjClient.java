package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.PadMode;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.ControlMidi;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.Arriving;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.Claim;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.Live;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.Touch;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.TrackPart;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block.Want;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjFeature;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjState;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.ClientFiles;
import org.haruyq.createmediasystems.client.ItemRenderers;
import org.haruyq.createmediasystems.client.MidiPort;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.playback.ScreenPlayer;
import org.haruyq.createmediasystems.playback.Spectrum;
import org.haruyq.createmediasystems.screen.ScreenFeature;
import org.haruyq.createmediasystems.screen.ScreenMonitorBlock;
import org.haruyq.createmediasystems.screen.SoundShare;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The DDJ-FLX4 on a client. For everyone: the tracks its decks have loaded, asked of the server as they are seen, and
 * where each deck is by now. For the controller's host ({@link Host}): their own DDJ-FLX4's MIDI read into how the
 * block stands, their rekordbox's decks read from its memory (see Rekordbox) and its sound sent to the listeners (see
 * SoundShare), and what others do on the block's screen sent on to rekordbox as the controller's MIDI.
 */
public final class DjClient implements ClientFeature {
    /** How long after the real controller worked a control the game leaves that control alone. */
    private static final long HARDWARE_HOLD_NANOS = 1_000_000_000L;
    /** A deck is told anew once it is this far (seconds) from where the last telling has it by now. */
    private static final double DRIFT = .04;
    /** A listener hears the sound this long after it was played (see ScreenPlayer), and sees the decks as late. */
    private static final double HEARD_LATE = ScreenPlayer.SOUND_DELAY_MICROS / 1e6;
    /** A turntable's turns a second, which a jog wheel's mark makes as its deck plays. */
    public static final double TURNS_PER_SECOND = 100 / 3.0 / 60;

    /**
     * A controller as it is drawn: how it stands, how far in (seconds) each deck is now, each deck's track if it is
     * here yet, and how loud (0 to 1) its sound is as this client has it.
     */
    public record View(DjState state, double[] seconds, DjState.Track[] tracks, float level) {
        public boolean playing(int deck) {
            return state.rate[deck] != 0;
        }

        public boolean loaded(int deck) {
            return state.track[deck] != 0;
        }
    }

    /** What this client's hosting has going, for the controller's screen. */
    public record Status(boolean hardware, boolean rekordbox, boolean sound, float level, String out, String problem) {
    }

    private static final Map<BlockPos, DjState.Track[]> TRACKS = new HashMap<>();
    private static final Map<BlockPos, Arriving[]> ARRIVING = new HashMap<>();
    private static final long[] WANTED_AT = new long[2];
    @Nullable
    private static Host hosting;

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (hosting != null) {
                hosting.tick();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (hosting != null) {
                hosting.close();
            }
            TRACKS.clear();
            ARRIVING.clear();
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(
                DjFeature.ENTITY.get(), context -> new DjRenderer()));
        modBus.addListener((RegisterClientExtensionsEvent event) -> ItemRenderers.register(event, DjRenderer.Item::new,
                DjFeature.ITEM.get()));
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new DjScreen(pos));
    }

    /** The controller as this client draws it now: its host from what it reads itself, the others from what they were told. */
    public static View view(DdjFlx4Block.Entity controller) {
        BlockPos pos = controller.getBlockPos();
        Host host = hosting != null && hosting.pos.equals(pos) ? hosting : null;
        long now = System.nanoTime();
        // A listener is shown each way it stood once the sound of that moment plays from their speakers.
        while (!controller.coming.isEmpty() && (now - controller.coming.peek().toldAt) / 1e9 >= HEARD_LATE) {
            controller.heard = controller.coming.poll();
        }
        DjState state = host != null ? host.state : controller.heard != null ? controller.heard : controller.state();
        double[] seconds = new double[2];
        DjState.Track[] tracks = new DjState.Track[2];
        for (int deck = 0; deck < 2; deck++) {
            tracks[deck] = track(pos, deck, state.track[deck]);
            seconds[deck] = host != null ? host.seconds[deck] + host.rates[deck] * (now - host.readAt[deck]) / 1e9
                    : Math.max(0, state.position[deck] + state.rate[deck] * ((now - state.toldAt) / 1e9 - HEARD_LATE));
        }
        // Its host has the sound as it is taken; a listener, as their own speakers play it. It is not told: it changes
        // all the time.
        Spectrum.Frame heard = host != null ? null : Spectrum.heard(pos);
        float level = host != null ? (host.sound == null ? 0 : host.sound.level())
                : heard == null ? 0 : Math.max(heard.left(), heard.right()) * 1.5f;
        return new View(state, seconds, tracks, Math.min(1, level));
    }

    @Nullable
    private static DjState.Track track(BlockPos pos, int deck, int id) {
        if (id == 0) {
            return null;
        }
        DjState.Track[] known = TRACKS.get(pos);
        if (known != null && known[deck] != null && known[deck].id() == id) {
            return known[deck];
        }
        long now = System.nanoTime();
        if (now - WANTED_AT[deck] > 1_000_000_000L) {
            WANTED_AT[deck] = now;
            PacketDistributor.sendToServer(new Want(pos, deck, id));
        }
        return null;
    }

    /** A part of a track came from the server. */
    public static void trackPart(TrackPart part) {
        if (!part.valid()) {
            return;
        }
        byte[] whole = ARRIVING.computeIfAbsent(part.pos(), pos -> new Arriving[]{new Arriving(), new Arriving()})[part.deck()]
                .take(part);
        DjState.Track track = whole == null ? null : DjState.Track.of(whole);
        if (track != null) {
            TRACKS.computeIfAbsent(part.pos(), pos -> new DjState.Track[2])[part.deck()] = track;
        }
    }

    /** Someone worked a control of the controller this client hosts. */
    public static void touched(Touch touch) {
        if (hosting != null && hosting.pos.equals(touch.pos())) {
            hosting.touch(touch.slot(), touch.value(), touch.turned());
        }
    }

    /** This client's player worked a control on the screen of the controller at {@code pos}. */
    static void touch(BlockPos pos, int slot, int value, boolean turned) {
        if (hosting != null && hosting.pos.equals(pos)) {
            hosting.touch(slot, value, turned);
        } else {
            PacketDistributor.sendToServer(new Touch(pos, slot, value, turned));
        }
    }

    static boolean hosting(BlockPos pos) {
        return hosting != null && hosting.pos.equals(pos);
    }

    @Nullable
    static Status status(BlockPos pos) {
        return hosting(pos) ? hosting.status() : null;
    }

    /** Makes the controller at {@code pos} this player's, in place of any they hosted before. */
    static void host(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.level.getBlockEntity(pos) instanceof DdjFlx4Block.Entity controller)) {
            return;
        }
        stop();
        Host host = new Host(pos.immutable(), mc.player.getUUID(), new DjState(controller.state().bytes()));
        hosting = host;
        MidiPort.onDevices(host::openOut);
        PacketDistributor.sendToServer(new Claim(pos, true, ""));
    }

    static void stop() {
        if (hosting != null) {
            PacketDistributor.sendToServer(new Claim(hosting.pos, false, ""));
            hosting.close();
        }
    }

    /** This client's hosting of one controller. On the game thread but where it says. */
    private static final class Host implements ControlMidi.Sink {
        final BlockPos pos;
        final UUID me;
        final DjState state;
        private final ControlMidi midi = new ControlMidi();
        /** What the real controller sent, till the next tick reads it. */
        private final ConcurrentLinkedQueue<Integer> played = new ConcurrentLinkedQueue<>();
        private final MidiPort in;
        /** When (System#nanoTime) the real controller last worked each control. */
        private final long[] hardwareAt = new long[Control.SLOTS];
        @Nullable
        private volatile MidiDevice out;
        @Nullable
        private volatile Receiver receiver;
        private volatile String outName = "";
        private volatile int rekordboxPid;
        @Nullable
        private Rekordbox rekordbox;
        /** Whether rekordbox is being looked for, off this thread, and the tick to look again at. */
        private boolean looking;
        private int lookAt;
        /** Each deck's analysis file as rekordbox names it, and its track as it is sent. */
        private final String[] anlz = {"", ""};
        private final byte[][] tracks = new byte[2][];
        /** Each deck as last read: how far in, how fast, and when (System#nanoTime). */
        final double[] seconds = new double[2];
        final float[] rates = new float[2];
        final long[] readAt = new long[2];
        private long anchoredAt;
        @Nullable
        private SoundShare sound;
        private boolean soundTried;
        private int soundPid;
        private volatile String problem = "";
        private volatile String outProblem = "";
        private byte[] sent = {};
        private int ticks;
        @Nullable
        private Object seen;

        Host(BlockPos pos, UUID me, DjState state) {
            this.pos = pos;
            this.me = me;
            this.state = state;
            Arrays.fill(hardwareAt, System.nanoTime() - HARDWARE_HOLD_NANOS);
            in = new MidiPort(null, message -> played.add(message.getStatus() | message.getData1() << 8 | message.getData2() << 16),
                    () -> {
                    }, input -> input.name().toUpperCase(Locale.ROOT).contains("DDJ-FLX4"));
            in.scan();
        }

        /** The first MIDI output whose name has the configured part in it. On the MIDI devices' thread. */
        private void openOut() {
            String named = ClientConfig.DJ_MIDI_OUT.get().trim(), wanted = named.toLowerCase(Locale.ROOT);
            if (wanted.isEmpty()) {
                return;
            }
            String why = "No MIDI output named " + named;
            for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
                if (!info.getName().toLowerCase(Locale.ROOT).contains(wanted)) {
                    continue;
                }
                try {
                    MidiDevice device = MidiSystem.getMidiDevice(info);
                    if (device.getMaxReceivers() == 0 || device instanceof Sequencer || device instanceof Synthesizer) {
                        continue;
                    }
                    device.open();
                    receiver = device.getReceiver();
                    out = device;
                    outName = info.getName();
                    if (hosting != this) {
                        device.close();
                    }
                    mapping();
                    return;
                } catch (MidiUnavailableException | RuntimeException taken) {
                    why = "MIDI output " + info.getName() + ": " + taken.getMessage();
                }
            }
            outProblem = why;
        }

        /**
         * Writes into CMS's folder the mapping rekordbox needs for the port the game's controls go out of (see
         * ControlMidi#mapping), from the one rekordbox has beside its program; its player imports it in rekordbox's
         * MIDI settings. Once both the port and rekordbox are found, whichever is last. Off the game thread.
         */
        private void mapping() {
            String port = outName;
            int pid = rekordboxPid;
            if (port.isEmpty() || pid == 0) {
                return;
            }
            try {
                Path own = Path.of(ProcessHandle.of(pid).flatMap(process -> process.info().command()).orElseThrow())
                        .resolveSibling("MidiMappings").resolve("DDJ-FLX4.midi.csv");
                Files.createDirectories(ClientFiles.DIR);
                Files.writeString(ClientFiles.DIR.resolve(port + ".midi.csv"), ControlMidi.mapping(Files.readString(own), port));
            } catch (IOException | RuntimeException none) {
                LogUtils.getLogger().info("No rekordbox mapping written for {}: {}", port, none.toString());
            }
        }

        private void send(int message) {
            Receiver to = receiver;
            if (to != null) {
                try {
                    to.send(new ShortMessage(message & 0xFF, message >> 8 & 0x7F, message >> 16 & 0x7F), -1);
                } catch (InvalidMidiDataException | IllegalStateException closed) {
                    // not a message, or the port went
                }
            }
        }

        private boolean hardware() {
            return in.status().message().equals("keyboard.midi_connected");
        }

        Status status() {
            return new Status(hardware(), rekordbox != null, sound != null, sound == null ? 0 : sound.level(), outName,
                    problem.isEmpty() || outProblem.isEmpty() ? problem + outProblem : problem + "; " + outProblem);
        }

        @Override
        public void set(int slot, int value) {
            hardwareAt[slot] = System.nanoTime();
            state.set(slot, value);
        }

        @Override
        public void turn(int slot, int steps) {
            hardwareAt[slot] = System.nanoTime();
        }

        @Override
        public void mode(int deck, PadMode mode) {
            state.padMode[deck] = mode;
        }

        /**
         * A control worked in the game: rekordbox is told as the controller would tell it, unless the real controller
         * has just worked that control itself, which then has it.
         */
        void touch(int slot, int value, boolean turned) {
            if (slot < 0 || slot >= Control.SLOTS || hardware() && System.nanoTime() - hardwareAt[slot] < HARDWARE_HOLD_NANOS) {
                return;
            }
            Control control = Control.ofSlot(slot);
            int deck = slot & 1, fx = state.value(Control.FX_CHANNEL, 0);
            // A control there is one of takes either deck's SHIFT.
            boolean shifted = state.down(Control.SHIFT, deck) || control.count() == 1 && state.down(Control.SHIFT, 1);
            if (turned) {
                ControlMidi.turn(slot, value, shifted, this::send);
                return;
            }
            if (control.kind.continuous()) {
                ControlMidi.move(slot, Math.clamp(value, 0, Control.FULL), shifted, fx, this::send);
            } else {
                ControlMidi.press(slot, value, shifted, state.padMode[deck], fx, this::send);
            }
            state.set(slot, value);
        }

        void tick() {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                close();
                return;
            }
            ticks++;
            if (mc.level.isLoaded(pos)) {
                var entity = mc.level.getBlockEntity(pos);
                // Broken, or someone else's: a claim is answered within a moment.
                if (!(entity instanceof DdjFlx4Block.Entity controller) || ticks > 40 && !me.equals(controller.host())) {
                    close();
                    return;
                }
                if (entity != seen && me.equals(controller.host())) {
                    // Loaded anew, it has forgotten the tracks: they are not saved.
                    seen = entity;
                    sent = new byte[0];
                    for (int deck = 0; deck < 2; deck++) {
                        sendTrack(deck);
                    }
                }
            }
            if (ticks % 40 == 0) {
                in.scan();
            }
            for (Integer message; (message = played.poll()) != null; ) {
                midi.read(message, this);
            }
            boolean drifted = decks();
            sound();
            state.flags = (hardware() ? DjState.HARDWARE : 0) | (rekordbox != null ? DjState.REKORDBOX : 0)
                    | (sound != null ? DjState.SOUND : 0);
            if (drifted || !Arrays.equals(state.bytes(), sent)) {
                anchoredAt = System.nanoTime();
                for (int deck = 0; deck < 2; deck++) {
                    state.position[deck] = (float) (seconds[deck] + rates[deck] * (anchoredAt - readAt[deck]) / 1e9);
                    state.rate[deck] = rates[deck];
                }
                sent = state.bytes();
                PacketDistributor.sendToServer(new Live(pos, sent));
            }
        }

        /** Reads rekordbox's decks; true if one is no longer where the listeners were last told it would be. */
        private boolean decks() {
            if (rekordbox == null) {
                if (!looking && ticks >= lookAt && Util.getPlatform() == Util.OS.WINDOWS) {
                    looking = true;
                    Path offsets = ClientFiles.DIR.resolve("rekordbox-offsets.properties");
                    Thread.ofPlatform().daemon().name("CMS rekordbox").start(() -> {
                        Rekordbox found = null;
                        try {
                            found = Rekordbox.attach(offsets);
                            if (found != null) {
                                rekordboxPid = found.pid();
                                mapping();
                            }
                        } catch (RuntimeException | LinkageError failed) {
                            LogUtils.getLogger().warn("Could not read rekordbox", failed);
                        }
                        Rekordbox attached = found;
                        Minecraft.getInstance().execute(() -> {
                            looking = false;
                            // A scan that finds nothing takes over a second: not at once again.
                            lookAt = ticks + 100;
                            if (hosting == this) {
                                rekordbox = attached;
                            } else if (attached != null) {
                                attached.close();
                            }
                        });
                    });
                }
                return false;
            }
            Rekordbox.Deck[] decks = rekordbox.read();
            if (decks == null) {
                rekordbox.close();
                rekordbox = null;
                lookAt = ticks + 60;
                Arrays.fill(rates, 0);
                Arrays.fill(state.track, 0);
                Arrays.fill(anlz, "");
                return true;
            }
            long now = System.nanoTime();
            boolean drifted = false;
            for (int deck = 0; deck < 2; deck++) {
                Rekordbox.Deck read = decks[deck];
                double elapsed = (now - readAt[deck]) / 1e9;
                double speed = elapsed > 0 && elapsed < 1 ? (read.position() - seconds[deck]) / elapsed : 0;
                float nominal = read.bpm100() > 0 && read.bpm() > 0 ? read.bpm() * 100 / read.bpm100() : 1;
                rates[deck] = DjState.rate(read.loaded(), speed, nominal, rates[deck]);
                seconds[deck] = read.position();
                readAt[deck] = now;
                state.bpm[deck] = read.loaded() ? read.bpm() : 0;
                double told = state.position[deck] + state.rate[deck] * (now - anchoredAt) / 1e9;
                drifted |= Math.abs(told - seconds[deck]) > DRIFT || Math.abs(rates[deck] - state.rate[deck]) > .002f;
                if (!read.anlz().equals(anlz[deck])) {
                    anlz[deck] = read.anlz();
                    state.track[deck] = 0;
                    tracks[deck] = null;
                    if (read.loaded()) {
                        load(deck, read);
                    }
                }
            }
            return drifted;
        }

        /** Reads {@code deck}'s track from rekordbox's analysis of it, off the game thread, and sends it. */
        private void load(int deck, Rekordbox.Deck read) {
            Thread.ofPlatform().daemon().name("CMS DJ track").start(() -> {
                Anlz.Track analysis;
                try {
                    analysis = Anlz.read(Path.of(read.anlz()));
                } catch (IOException | RuntimeException unreadable) {
                    LogUtils.getLogger().warn("Could not read rekordbox's analysis {}: {}", read.anlz(), unreadable.toString());
                    analysis = new Anlz.Track(new int[0], new short[0], new int[0]);
                }
                int hash = read.anlz().hashCode() * 31 + read.title().hashCode();
                DjState.Track track = new DjState.Track(hash == 0 ? 1 : hash, read.title(), read.artist(), read.key(),
                        read.bpm100(), read.seconds(), analysis.beats(), analysis.wave(), analysis.cues());
                byte[] bytes = track.bytes();
                Minecraft.getInstance().execute(() -> {
                    if (hosting == this && anlz[deck].equals(read.anlz())) {
                        tracks[deck] = bytes;
                        state.track[deck] = track.id();
                        TRACKS.computeIfAbsent(pos, at -> new DjState.Track[2])[deck] = DjState.Track.of(bytes);
                        sendTrack(deck);
                    }
                });
            });
        }

        private void sendTrack(int deck) {
            if (tracks[deck] != null) {
                TrackPart.split(pos, deck, state.track[deck], tracks[deck], PacketDistributor::sendToServer);
            }
        }

        /** Starts sending rekordbox's sound once there is one to take, and tells the block its link. */
        private void sound() {
            String device = ClientConfig.DJ_SOUND_DEVICE.get().trim();
            if (sound != null && (SoundShare.current() != sound
                    || device.isEmpty() && rekordbox != null && rekordbox.pid() != soundPid)) {
                // A screen share took its place, or rekordbox was started anew.
                boolean again = SoundShare.current() == sound;
                if (again) {
                    SoundShare.stopCurrent();
                }
                sound = null;
                soundTried = !again;
                PacketDistributor.sendToServer(new Claim(pos, true, ""));
            }
            if (soundTried || device.isEmpty() && rekordbox == null) {
                return;
            }
            soundTried = true;
            if (!P2pClient.connect(me)) {
                problem = "Could not host a P2P session; check /cms p2p status";
                return;
            }
            int pid = rekordbox == null ? 0 : rekordbox.pid();
            List<String> source = device.isEmpty() ? List.of("include", Integer.toString(pid)) : List.of("device", device);
            Thread.ofPlatform().daemon().name("CMS DJ sound").start(() -> {
                try {
                    SoundShare started = SoundShare.start(me, source);
                    Minecraft.getInstance().execute(() -> {
                        if (hosting == this && SoundShare.current() == started) {
                            sound = started;
                            soundPid = pid;
                            problem = "";
                            PacketDistributor.sendToServer(new Claim(pos, true, started.url.toString()));
                        } else if (SoundShare.current() == started) {
                            SoundShare.stopCurrent();
                        }
                    });
                } catch (IOException | RuntimeException failed) {
                    problem = "No sound: " + (failed.getMessage() == null ? failed : failed.getMessage());
                }
            });
        }

        void close() {
            if (hosting == this) {
                hosting = null;
            }
            in.close();
            MidiDevice port = out;
            if (port != null) {
                MidiPort.onDevices(port::close);
            }
            if (rekordbox != null) {
                rekordbox.close();
                rekordbox = null;
            }
            if (sound != null && SoundShare.current() == sound) {
                SoundShare.stopCurrent();
            }
        }
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(DjFeature.ITEM.getId()).addStoryBoard(Create.asResource("redstone_link"), DjClient::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "ddj_flx4", "The PIONEAR DJ DDJ-FLX4");
        scene.scaleSceneView(1.25f);
        BlockPos pos = util.grid().at(2, 1, 2), behind = util.grid().at(2, 1, 3);
        Vec3 top = util.vector().topOf(pos).subtract(0, .8, 0);
        scene.world().setBlock(pos, DjFeature.BLOCK.get().defaultBlockState().setValue(DdjFlx4Block.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(top, Pointing.DOWN, 60).rightClick();
        say(scene, 130, top, PonderPalette.GREEN, "Right-click it to open its screen. Press Host there and it becomes yours: it then stands as the DDJ-FLX4 plugged into your computer does, and follows the rekordbox 7 running there.");

        scene.world().setBlock(behind, ScreenFeature.MONITOR.get().defaultBlockState().setValue(ScreenMonitorBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(behind), Direction.DOWN);
        scene.idle(15);
        say(scene, 130, util.vector().centerOf(behind), "A Monitor beside it or on top of it shows the two decks as rekordbox does: their waveforms, where each is in its track, and its tempo.");

        notes(scene, top, 120);
        say(scene, 130, top, "Link speakers to it as to any player. They play what your rekordbox plays to everyone near, sent directly from your computer.");

        scene.overlay().showControls(top, Pointing.DOWN, 60).rightClick();
        say(scene, 130, top, "Anyone near can work its controls on its screen, which reaches your rekordbox through a virtual MIDI port. A control your own DDJ-FLX4 has just moved stays as that set it.");
    }
}
