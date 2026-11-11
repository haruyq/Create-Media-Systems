package org.haruyq.createmediasystems.disc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock.Drive;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * This computer's real disc drive, in the drive emulator its player uses (see DriveEmulatorBlock). A disc in the real
 * drive while that emulator's screen is open is read and shared (see DiscClient), and what the emulator is beside
 * plays it; from then on, screen open or not, taking it out stops it. Also every emulator's sound here. Game thread
 * only, but for looking at the drive.
 */
public final class DriveClient {
    /** A disc in the real drive: its root, its volume label, and its kind. */
    record Disc(Path root, String label, DriveEmulatorBlock.Disc kind) {
    }

    /** The emulator whose screen is open or whose disc this drive's is; null for none. */
    @Nullable
    private static BlockPos block;
    private static ResourceKey<Level> dimension;
    private static boolean screen;
    /** The disc read or shared there, which of DiscClient's loads it is, and whether it is shared yet. */
    @Nullable
    private static Path loaded;
    private static int load;
    private static boolean ready;
    /** Ejected with the screen's button, the disc still in the real drive: open until that comes out, or the button again. */
    private static boolean ejected;
    /** The disc in the real drive as last looked; null until it has been, for this emulator. */
    @Nullable
    private static volatile Optional<Disc> found;
    /** Whether there was a drive to look at. */
    private static volatile boolean drive;
    private static Thread looking = Thread.ofVirtual().unstarted(() -> {});
    private static int ticks;
    /** The turning discs heard here, by their emulator. */
    private static final Map<BlockPos, Spin> SPINS = new HashMap<>();

    private DriveClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            block = null;
            loaded = null;
            ready = false;
            screen = false;
            SPINS.clear();
        });
    }

    /** The screen of the emulator at {@code pos} opened: a disc in the real drive now goes in it. */
    static void opened(BlockPos pos) {
        if (loaded == null && !pos.equals(block)) {
            block = pos.immutable();
            dimension = Minecraft.getInstance().level.dimension();
            ejected = false;
            found = null;
            // Looks at once: a disc already in the real drive goes in as the screen opens.
            ticks = 0;
        }
        screen = pos.equals(block);
    }

    static void closed() {
        screen = false;
    }

    /** Whether the screen of the emulator at {@code pos} has a button: this drive's disc is in it, or ejected from it. */
    static boolean ejectable(BlockPos pos) {
        return pos.equals(block) && (loaded != null || ejected);
    }

    /** What the screen of {@code emulator} says under its open drive: what to do, or why nothing happens. */
    static String hint(DriveEmulatorBlock.Entity emulator) {
        Optional<Disc> disc = found;
        return !emulator.getBlockPos().equals(block) ? "elsewhere" : disc == null ? "looking" : !drive ? "no_drive"
                : disc.isPresent() && !emulator.feeds(disc.get().kind()) ? disc.get().kind().audio() ? "no_reader" : "no_player"
                : ejected ? "ejected" : "insert";
    }

    /** The screen's button: takes the disc out of the emulator, though not out of the real drive, or puts it back. */
    static void eject() {
        if (loaded != null) {
            unload(true);
        } else {
            ejected = false;
        }
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (block == null || mc.level == null || mc.player == null) {
            return;
        }
        // Null out of sight or in another dimension, where it keeps this drive's disc.
        boolean here = mc.level.dimension() == dimension;
        DriveEmulatorBlock.Entity emulator = here && mc.level.getBlockEntity(block) instanceof DriveEmulatorBlock.Entity it ? it : null;
        if (here && emulator == null && mc.level.isLoaded(block)) {
            // Broken, the disc in it.
            if (loaded != null) {
                unload(false);
            }
            block = null;
            return;
        }
        boolean second = ticks++ % 20 == 0;
        if (second && !looking.isAlive()) {
            looking = Thread.ofVirtual().start(() -> found = look());
        }
        if (second && ready) {
            viewers();
        }
        Optional<Disc> disc = found;
        if (disc == null) {
            return;
        }
        // Another player's disc is in it: theirs went in first.
        boolean taken = emulator != null && emulator.host() != null && !emulator.host().equals(mc.player.getUUID());
        if (loaded != null) {
            // Out of the real drive, or unloaded with /cms disc.
            if (disc.isEmpty() || !disc.get().root().equals(loaded) || !DiscClient.current(load) || taken) {
                unload(disc.isPresent() && !taken);
            }
        } else if (disc.isPresent() && screen && !ejected && emulator != null && !taken && emulator.feeds(disc.get().kind())) {
            insert(disc.get());
        }
        if (disc.isEmpty()) {
            ejected = false;
        }
        if (loaded == null && !screen) {
            block = null;
        }
    }

    private static void insert(Disc disc) {
        BlockPos emulator = block;
        // ponytail: always the title CMS picks (-1: a DVD's first, a Blu-ray's longest); a chooser on the screen if discs need another.
        int started = DiscClient.load(disc.root().toString(), -1, new DiscClient.Reader() {
            @Override
            public void downloading() {
                send(emulator, Drive.DOWNLOADING, disc.label(), disc.kind());
            }

            @Override
            public boolean loaded(DiscUrl url, DiscSource source) {
                ready = true;
                send(emulator, Drive.READY, url.toString(), disc.kind());
                return true;
            }

            @Override
            public void failed(String message) {
                send(emulator, Drive.FAILED, message, disc.kind());
            }
        });
        // 0: the one before is still being stopped; again in a moment.
        if (started != 0) {
            loaded = disc.root();
            load = started;
            send(emulator, Drive.READING, disc.label(), disc.kind());
        }
    }

    private static void unload(boolean eject) {
        if (DiscClient.current(load)) {
            DiscClient.clear();
        }
        loaded = null;
        ready = false;
        ejected = eject;
        // ponytail: the server takes the place in this player's dimension, so an emulator in another one is told only
        // once this player is back at it (see tick); send the dimension too if that wait matters.
        send(block, Drive.OPEN, "", DriveEmulatorBlock.Disc.BLU_RAY);
    }

    /** Tells the emulator how each viewer's direct connection does (see DriveEmulatorBlock.Viewer), asking each for its round trip. */
    private static void viewers() {
        Transport transport = P2pClient.current();
        if (transport == null || !transport.host()) {
            return;
        }
        transport.measure();
        List<DriveEmulatorBlock.Viewer> viewers = transport.links().stream().limit(64)
                .map(link -> new DriveEmulatorBlock.Viewer(link.peer(), link.open() && link.measured(),
                        (int) Math.max(0, link.rttNanos() / 1_000_000), (int) (link.bps() / 1000))).toList();
        PacketDistributor.sendToServer(new DriveEmulatorBlock.Viewers(block, viewers));
    }

    /** Every tick of every emulator here: its disc is heard while it turns, and one that wrongly has this player's is told. */
    public static void tick(DriveEmulatorBlock.Entity emulator) {
        var mc = Minecraft.getInstance();
        BlockPos pos = emulator.getBlockPos();
        if (emulator.spinning()) {
            Spin spin = SPINS.get(pos);
            if (spin == null || spin.isStopped()) {
                spin = new Spin(pos.immutable());
                SPINS.put(spin.pos, spin);
                mc.getSoundManager().play(spin);
            }
        }
        // It says this player's disc is in it, yet this drive isn't in it: its player left and came back, or the game
        // stopped with the disc in.
        if (emulator.getLevel().getGameTime() % 20 == 0 && mc.player != null && mc.player.getUUID().equals(emulator.host())
                && !(loaded != null && pos.equals(block))) {
            send(pos, Drive.OPEN, "", DriveEmulatorBlock.Disc.BLU_RAY);
        }
    }

    private static void send(BlockPos emulator, Drive state, String text, DriveEmulatorBlock.Disc kind) {
        PacketDistributor.sendToServer(new DriveEmulatorBlock.Update(emulator, state, text == null ? "" : text, kind));
    }

    /**
     * The disc in the real drive: the first of this computer's optical drives with a Blu-ray, DVD or audio CD in it,
     * or the one drive or folder cms-client.toml's discDrive names. Off the game thread: a drive spinning up takes seconds.
     */
    private static Optional<Disc> look() {
        List<Path> roots = new ArrayList<>();
        try {
            String configured = ClientConfig.DISC_DRIVE.get().trim();
            if (!configured.isEmpty()) {
                roots.add(Path.of(configured));
            } else if (Platform.isWindows()) {
                for (File root : File.listRoots()) {
                    if (Kernel32.INSTANCE.GetDriveType(root.getPath()) == WinBase.DRIVE_CDROM) {
                        roots.add(root.toPath());
                    }
                }
            }
        } catch (RuntimeException | LinkageError unknown) {
            // No drive it can tell of.
        }
        drive = !roots.isEmpty();
        for (Path root : roots) {
            DriveEmulatorBlock.Disc kind = Files.isRegularFile(root.resolve("BDMV/index.bdmv")) ? DriveEmulatorBlock.Disc.BLU_RAY
                    : Files.isRegularFile(root.resolve("VIDEO_TS/VIDEO_TS.IFO")) ? DriveEmulatorBlock.Disc.DVD
                    : CdAudio.find(root) != null ? DriveEmulatorBlock.Disc.CD : null;
            if (kind != null) {
                String label = "";
                try {
                    label = root.getFileName() == null ? Files.getFileStore(root).name() : root.getFileName().toString();
                } catch (IOException unlabelled) {
                    // Nameless.
                }
                return Optional.of(new Disc(root, label == null ? "" : label, kind));
            }
        }
        return Optional.empty();
    }

    /**
     * A disc turning in the emulator at {@code pos}: one loop, which rises in pitch and loudness as the disc spins up
     * and falls as it spins down, then ends.
     */
    private static final class Spin extends AbstractTickableSoundInstance {
        /** How loud a disc at speed is, and how many ticks it takes to get there and to stop. Tuning knobs. */
        private static final float VOLUME = 0.3f, UP_TICKS = 50, DOWN_TICKS = 35;

        private final BlockPos pos;
        private float speed;

        Spin(BlockPos pos) {
            super(CreateMediaSystems.DRIVE_SPIN.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.pos = pos;
            looping = true;
            delay = 0;
            x = pos.getX() + 0.5;
            y = pos.getY() + 0.5;
            z = pos.getZ() + 0.5;
            volume = 0;
            pitch = 0.5f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            var level = Minecraft.getInstance().level;
            boolean turns = level != null && level.getBlockEntity(pos) instanceof DriveEmulatorBlock.Entity emulator
                    && emulator.spinning();
            speed = Mth.approach(speed, turns ? 1 : 0, 1 / (turns ? UP_TICKS : DOWN_TICKS));
            if (speed <= 0 && !turns) {
                stop();
                return;
            }
            volume = VOLUME * speed;
            pitch = 0.5f + 0.5f * speed;
        }
    }
}
