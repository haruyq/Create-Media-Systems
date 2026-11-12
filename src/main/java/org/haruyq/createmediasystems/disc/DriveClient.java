package org.haruyq.createmediasystems.disc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.p2p.P2pClient;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Drive;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

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
 * drive while that emulator's screen is open goes in it: as the real drive closes on it and makes it out, and then
 * as it is read and shared (see DiscClient), and what the emulator is beside plays it; from then on, screen open or
 * not, taking it out stops it. Also the sound of the emulator whose screen is open. Game thread only, but for
 * looking at the drive.
 */
public final class DriveClient {
    /** A disc in the real drive: its root, its volume label, and its kind; null while the drive still makes it out. */
    record Disc(Path root, String label, @Nullable DriveEmulatorBlock.Disc kind) {
    }

    /** A disc the real drive has closed on and still makes out. */
    private static final Disc UNREAD = new Disc(Path.of(""), "", null);
    /** How long (ns) a drive that has made a disc out may take to show what is on it, before the disc counts as none it plays. */
    private static final long SETTLE_NANOS = 10_000_000_000L;
    /**
     * How long (ticks, and ns) a drive may take to answer before it counts as busy making a disc out: one that has just
     * closed on a disc answers nothing for the seconds that takes; any other answers in milliseconds.
     */
    private static final int BUSY_TICKS = 15;
    private static final long BUSY_NANOS = 750_000_000L;

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
    /** The emulator shows the real drive closing on a disc not made out yet (Drive.LOADING): nothing is read so far. */
    private static boolean loading;
    /** Ejected with the screen's button, the disc still in the real drive: open until that comes out, or the button again. */
    private static boolean ejected;
    /** The disc in the real drive as last looked; null until it has been, for this emulator. */
    @Nullable
    private static volatile Optional<Disc> found;
    private static volatile boolean drive;
    /** When the real drive was last seen making a disc out. The looking thread's. */
    private static long settling = System.nanoTime() - SETTLE_NANOS;
    private static Thread looking = Thread.ofVirtual().unstarted(() -> {});
    private static int ticks;
    /** How long (ticks) the look now going on has taken. */
    private static int waited;
    /** The turning disc heard here: that of the emulator whose screen is open. */
    @Nullable
    private static Spin spin;

    private DriveClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            block = null;
            loaded = null;
            ready = false;
            loading = false;
            screen = false;
        });
    }

    /** The screen of the emulator at {@code pos} opened: a disc in the real drive now goes in it. */
    static void opened(BlockPos pos) {
        if (loaded == null && !loading && !pos.equals(block)) {
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
        DriveEmulatorBlock.Disc kind = disc == null || disc.isEmpty() ? null : disc.get().kind();
        return !emulator.getBlockPos().equals(block) ? "elsewhere" : disc == null ? "looking" : !drive ? "no_drive"
                : kind != null && !emulator.feeds(kind) ? kind.kind.hasPicture() ? "no_player" : "no_reader"
                : ejected ? "ejected" : "insert";
    }

    /**
     * The screen's button: takes the disc out of the emulator and, as the real drive's own button does, out of that;
     * or puts back one still in it, which a folder's is.
     */
    static void eject() {
        if (loaded != null) {
            Path root = loaded;
            unload(true);
            // Off the game thread: a drive stops its disc before it lets go of it.
            Thread.ofVirtual().start(() -> open(root));
        } else {
            ejected = false;
        }
    }

    /**
     * The sound and subtitle streams the screen of the emulator at {@code pos} chooses from: those of this drive's
     * disc in it, once it is shared; null otherwise.
     */
    @Nullable
    static DiscSource.Tracks tracks(BlockPos pos) {
        return ready && pos.equals(block) ? DiscClient.tracks() : null;
    }

    /**
     * The screen's choice of them: the disc is shared again with those (see DiscClient#choose), and the emulator is
     * told its new link, which what plays it goes on with from where it is.
     */
    static void choose(int audio, int subtitle) {
        DiscUrl url = ready ? DiscClient.choose(audio, subtitle) : null;
        if (url != null) {
            send(block, Drive.READY, url.toString(), null);
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
        // A disc that is read stays in until its files are gone: a drive may stop to find its place again.
        boolean loadable = loaded == null;
        int tick = ticks++;
        if (looking.isAlive()) {
            waited++;
        } else {
            waited = 0;
            // Twice a second while it waits for a disc, to see the real drive close on one.
            if (tick % (loadable ? 10 : 20) == 0) {
                looking = Thread.ofVirtual().start(() -> found = look(loadable));
            }
        }
        if (tick % 20 == 0 && ready) {
            viewers();
        }
        Optional<Disc> disc = loadable && drive && waited > BUSY_TICKS ? Optional.of(UNREAD) : found;
        if (disc == null) {
            return;
        }
        // Another player's disc is in it: theirs went in first.
        boolean taken = emulator != null && emulator.host() != null && !emulator.host().equals(mc.player.getUUID());
        DriveEmulatorBlock.Disc kind = disc.isEmpty() ? null : disc.get().kind();
        if (loaded != null) {
            // Out of the real drive, or unloaded with /cms disc.
            if (kind == null || !disc.get().root().equals(loaded) || !DiscClient.current(load) || taken) {
                unload(kind != null && !taken);
            }
        } else if (disc.isPresent() && emulator != null && !taken && (loading || screen && !ejected)) {
            if (kind == null) {
                // The real drive closes on it: so does the emulator, if anything beside it could play a disc.
                if (!loading && emulator.feedsAny()) {
                    loading = true;
                    send(block, Drive.LOADING, "", null);
                }
            } else if (emulator.feeds(kind)) {
                insert(disc.get());
            } else if (loading) {
                reopen();
            }
        } else if (loading) {
            // Nothing in the real drive after all, or it opened again.
            reopen();
        }
        if (disc.isEmpty()) {
            ejected = false;
        }
        if (loaded == null && !loading && !screen) {
            block = null;
        }
    }

    /** The emulator's tray comes out again: the disc the real drive closed on is none it plays, or is gone. */
    private static void reopen() {
        loading = false;
        send(block, Drive.OPEN, "", null);
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
            loading = false;
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
        send(block, Drive.OPEN, "", null);
    }

    /** Tells the emulator how each viewer's direct connection does (see DriveEmulatorBlock.Viewer), asking each for its round trip. */
    private static void viewers() {
        Transport transport = P2pClient.session(Minecraft.getInstance().player.getUUID());
        if (transport == null || !transport.host()) {
            return;
        }
        transport.measure();
        List<DriveEmulatorBlock.Viewer> viewers = transport.links().stream().limit(64)
                .map(link -> new DriveEmulatorBlock.Viewer(link.peer(), link.open() && link.measured(),
                        (int) Math.max(0, link.rttNanos() / 1_000_000), (int) (link.bps() / 1000))).toList();
        PacketDistributor.sendToServer(new DriveEmulatorBlock.Viewers(block, viewers));
    }

    /**
     * Every tick of every emulator here: its disc is heard while it turns and its screen is open, and one that wrongly
     * has this player's is told.
     */
    public static void tick(DriveEmulatorBlock.Entity emulator) {
        var mc = Minecraft.getInstance();
        BlockPos pos = emulator.getBlockPos();
        if (emulator.spinning() && DriveScreen.shows(pos) && (spin == null || spin.isStopped() || !spin.pos.equals(pos))) {
            spin = new Spin(pos.immutable());
            mc.getSoundManager().play(spin);
        }
        // It says this player's disc is in it, yet this drive isn't in it: its player left and came back, or the game
        // stopped with the disc in.
        if (emulator.getLevel().getGameTime() % 20 == 0 && mc.player != null && mc.player.getUUID().equals(emulator.host())
                && !((loaded != null || loading) && pos.equals(block))) {
            send(pos, Drive.OPEN, "", null);
        }
    }

    /** Tells the server its real drive is in {@code state}, with a disc of {@code kind} (null while that is not known). */
    private static void send(BlockPos emulator, Drive state, String text, @Nullable DriveEmulatorBlock.Disc kind) {
        PacketDistributor.sendToServer(new DriveEmulatorBlock.Update(emulator, state, text == null ? "" : text, kind));
    }

    /**
     * The disc in the real drive: the first of this computer's optical drives with a Blu-ray, DVD or audio CD in it,
     * or the one drive or folder cms-client.toml's discDrive names; of no kind yet (if {@code loadable}) in a drive
     * that has closed on it and still makes it out. Off the game thread: a drive spinning up takes seconds.
     */
    private static Optional<Disc> look(boolean loadable) {
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
            } else if (Platform.isLinux()) {
                List<String> devices;
                try (var dev = Files.list(Path.of("/dev"))) {
                    devices = dev.map(device -> device.getFileName().toString()).sorted().toList();
                }
                roots.addAll(linuxDrives(Files.readAllLines(Path.of("/proc/mounts")), devices));
            } else if (Platform.isMac()) {
                // macOS mounts every disc under /Volumes (an audio CD as AIFF files); what is on one tells its kind.
                try (var volumes = Files.list(Path.of("/Volumes"))) {
                    volumes.filter(volume -> !Files.isSymbolicLink(volume)).sorted().forEach(roots::add);
                }
            }
        } catch (IOException | RuntimeException | LinkageError unknown) {
            // No drive it can tell of.
        }
        drive = !roots.isEmpty();
        for (Path root : roots) {
            long asked = System.nanoTime();
            int sense = sense(root);
            if (sense == NO_DISC) {
                continue;
            }
            if (sense == BECOMING_READY || System.nanoTime() - asked > BUSY_NANOS) {
                settling = System.nanoTime();
            }
            if (sense == BECOMING_READY && loadable) {
                return Optional.of(UNREAD);
            }
            DiscKind found = DiscSource.kind(root);
            DriveEmulatorBlock.Disc kind = found == null ? null : DriveEmulatorBlock.Disc.of(found);
            if (kind != null) {
                String label = "";
                try {
                    label = root.getFileName() == null ? Files.getFileStore(root).name() : root.getFileName().toString();
                } catch (IOException unlabelled) {
                    // Nameless.
                }
                return Optional.of(new Disc(root, label == null ? "" : label, kind));
            }
            if (loadable && sense == READY && System.nanoTime() - settling < SETTLE_NANOS) {
                // Made out a moment ago: Windows has yet to show what is on it.
                return Optional.of(UNREAD);
            }
        }
        return Optional.empty();
    }

    /** What a drive answers when asked whether it is ready; UNKNOWN for a folder, or a drive that cannot be asked. */
    private static final int UNKNOWN = 0, READY = 1, BECOMING_READY = 2, NO_DISC = 3;
    private static final int IOCTL_SCSI_PASS_THROUGH = 0x4D004, SENSE_AT = 56, IOCTL_STORAGE_EJECT_MEDIA = 0x2D4808;

    /** The optical drive at {@code root}, to ask or tell it something; null for a folder, or a drive that cannot be. */
    @Nullable
    private static WinNT.HANDLE device(Path root) {
        if (!Platform.isWindows() || root.getFileName() != null
                || Kernel32.INSTANCE.GetDriveType(root.toString()) != WinBase.DRIVE_CDROM) {
            return null;
        }
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile("\\\\.\\" + root.toString().substring(0, 2),
                WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null,
                WinNT.OPEN_EXISTING, 0, null);
        return WinBase.INVALID_HANDLE_VALUE.equals(handle) ? null : handle;
    }

    /** Opens the tray of the real drive at {@code root}; a folder's disc stays where it is. */
    private static void open(Path root) {
        try {
            if (Platform.isLinux()) {
                String device = linuxDevice(root, Files.readAllLines(Path.of("/proc/mounts")));
                if (device != null) {
                    // eject (util-linux) unmounts the disc first; the kernel's own eject refuses a mounted one.
                    run("eject", device);
                }
                return;
            }
            if (Platform.isMac()) {
                if (Path.of("/Volumes").equals(root.toAbsolutePath().getParent())
                        && run("diskutil", "info", root.toString()).contains("Optical")) {
                    run("diskutil", "eject", root.toString());
                }
                return;
            }
            WinNT.HANDLE handle = device(root);
            if (handle != null) {
                try {
                    Kernel32.INSTANCE.DeviceIoControl(handle, IOCTL_STORAGE_EJECT_MEDIA, null, 0, null, 0,
                            new IntByReference(), null);
                } finally {
                    Kernel32.INSTANCE.CloseHandle(handle);
                }
            }
        } catch (IOException | RuntimeException | LinkageError unknown) {
            // No drive it can tell.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What {@code command} prints, once it is done (at most 30 s). */
    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] said = process.getInputStream().readNBytes(64 * 1024);
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return new String(said, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Linux's optical drives, from its mounts ({@code /proc/mounts}' lines) and {@code /dev}'s names: where each disc
     * in one is mounted, then each drive itself ({@code /dev/srN}), as an audio CD has nothing to mount.
     */
    static List<Path> linuxDrives(List<String> mounts, List<String> devices) {
        List<Path> roots = new ArrayList<>();
        for (String line : mounts) {
            String[] fields = line.split(" ");
            if (fields.length > 2 && (fields[0].matches("/dev/sr[0-9]+") || fields[2].equals("iso9660") || fields[2].equals("udf"))) {
                roots.add(Path.of(unescape(fields[1])));
            }
        }
        for (String device : devices) {
            if (device.matches("sr[0-9]+")) {
                roots.add(Path.of("/dev", device));
            }
        }
        return roots;
    }

    /** The optical drive ({@code /dev/srN}) {@code root} is, or has its disc mounted at; null if neither. */
    @Nullable
    static String linuxDevice(Path root, List<String> mounts) {
        String path = root.toString().replace('\\', '/');
        if (path.matches("/dev/sr[0-9]+")) {
            return path;
        }
        for (String line : mounts) {
            String[] fields = line.split(" ");
            if (fields.length > 1 && fields[0].matches("/dev/sr[0-9]+") && unescape(fields[1]).equals(path)) {
                return fields[0];
            }
        }
        return null;
    }

    /** A path as /proc/mounts writes it, with a space as \040 and so on. */
    private static String unescape(String path) {
        return java.util.regex.Pattern.compile("\\\\([0-7]{3})").matcher(path).replaceAll(octal ->
                java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(octal.group(1), 8))));
    }

    /**
     * Whether the optical drive at {@code root} is ready, has closed on a disc it still makes out, or has none: its
     * own answer (SCSI TEST UNIT READY), as Windows says only "not ready" for the last two. A drive answers "becoming
     * ready" only for a moment as it closes, then nothing until it is: see BUSY_TICKS.
     */
    private static int sense(Path root) {
        try {
            WinNT.HANDLE handle = device(root);
            if (handle == null) {
                return UNKNOWN;
            }
            try {
                // A 64-bit SCSI_PASS_THROUGH (ntddscsi.h), then room for the drive's sense. The command is six zeroes.
                Memory request = new Memory(SENSE_AT + 32);
                request.clear();
                request.setShort(0, (short) SENSE_AT);
                request.setByte(6, (byte) 6);
                request.setByte(7, (byte) 32);
                // No data either way, five seconds to answer, and where the sense goes.
                request.setByte(8, (byte) 2);
                request.setInt(16, 5);
                request.setInt(32, SENSE_AT);
                if (!Kernel32.INSTANCE.DeviceIoControl(handle, IOCTL_SCSI_PASS_THROUGH, request, (int) request.size(), request,
                        (int) request.size(), new IntByReference(), null)) {
                    return UNKNOWN;
                }
                if (request.getByte(2) == 0) {
                    return READY;
                }
                // Sense key 2 is "not ready": code 0x3A for no disc, 4 with qualifier 1 for becoming ready.
                int key = request.getByte(SENSE_AT + 2) & 15, code = request.getByte(SENSE_AT + 12) & 255;
                return key != 2 ? UNKNOWN : code == 0x3A ? NO_DISC
                        : code == 4 && request.getByte(SENSE_AT + 13) == 1 ? BECOMING_READY : UNKNOWN;
            } finally {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        } catch (RuntimeException | LinkageError unknown) {
            return UNKNOWN;
        }
    }

    /**
     * A disc turning in the emulator at {@code pos}, for whoever has its screen open and no one else: one loop, which
     * rises in pitch and loudness as the disc spins up and falls as it spins down, then ends; at once when the screen closes.
     */
    private static final class Spin extends AbstractTickableSoundInstance {
        /** How loud a disc at speed is, and how many ticks it takes to get there and to stop. Tuning knobs. */
        private static final float VOLUME = 0.3f, UP_TICKS = 50, DOWN_TICKS = 35, CLOSE_TICKS = 4;

        private final BlockPos pos;
        private float speed;

        Spin(BlockPos pos) {
            super(DiscFeature.DRIVE_SPIN.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.pos = pos;
            looping = true;
            delay = 0;
            // The screen's, not the block's: as loud wherever its player stands.
            relative = true;
            attenuation = Attenuation.NONE;
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
            boolean shown = DriveScreen.shows(pos);
            turns &= shown;
            speed = Mth.approach(speed, turns ? 1 : 0, 1 / (turns ? UP_TICKS : shown ? DOWN_TICKS : CLOSE_TICKS));
            if (speed <= 0 && !turns) {
                stop();
                return;
            }
            volume = VOLUME * speed;
            pitch = 0.5f + 0.5f * speed;
        }
    }
}
