package org.haruyq.createmediasystems.disc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock;
import org.haruyq.createmediasystems.video.DriveEmulatorBlock.Drive;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * This computer's real disc drive, in the drive emulator its player uses (see DriveEmulatorBlock). A disc put in the
 * real drive while that emulator's screen is open is read and shared (see DiscClient) and plays there; from then on,
 * screen open or not, taking it out stops it. Game thread only, but for looking at the drive.
 */
public final class DriveClient {
    /** A disc in the real drive: its root, its volume label, and whether it is a DVD. */
    record Disc(Path root, String label, boolean dvd) {
    }

    /** The emulator whose screen is open or whose disc this drive's is; null for none. */
    @Nullable
    private static BlockPos block;
    private static ResourceKey<Level> dimension;
    private static boolean screen;
    /** The disc read or shared there, and which of DiscClient's loads it is. */
    @Nullable
    private static Path loaded;
    private static int load;
    /** Ejected with the screen's button, the disc still in the real drive: open until that comes out, or the button again. */
    private static boolean ejected;
    /** The disc in the real drive as last looked; null until it has been, for this emulator. */
    @Nullable
    private static volatile Optional<Disc> found;
    /** Whether there was a drive to look at. */
    private static volatile boolean drive;
    private static Thread looking = Thread.ofVirtual().unstarted(() -> {});
    private static int ticks;

    private DriveClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            block = null;
            loaded = null;
            screen = false;
        });
    }

    /** The screen of the emulator at {@code pos} opened: a disc put in the real drive now goes in it. */
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

    /** What the screen of the emulator at {@code pos} says under its open drive: what to do, or why nothing happens. */
    static String hint(BlockPos pos) {
        return !pos.equals(block) ? "elsewhere" : found == null ? "looking" : !drive ? "no_drive" : ejected ? "ejected" : "insert";
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
        if (ticks++ % 20 == 0 && !looking.isAlive()) {
            looking = Thread.ofVirtual().start(() -> found = look());
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
        } else if (disc.isPresent() && screen && !ejected && emulator != null && !taken) {
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
                send(emulator, Drive.DOWNLOADING, disc.label(), disc.dvd());
            }

            @Override
            public boolean loaded(DiscUrl url, DiscSource source) {
                send(emulator, Drive.READY, url.toString(), disc.dvd());
                return true;
            }

            @Override
            public void failed(String message) {
                send(emulator, Drive.FAILED, message, disc.dvd());
            }
        });
        // 0: the one before is still being stopped; again in a moment.
        if (started != 0) {
            loaded = disc.root();
            load = started;
            send(emulator, Drive.READING, disc.label(), disc.dvd());
        }
    }

    private static void unload(boolean eject) {
        if (DiscClient.current(load)) {
            DiscClient.clear();
        }
        loaded = null;
        ejected = eject;
        // ponytail: the server takes the place in this player's dimension, so an emulator in another one is told only
        // once this player is back at it (see check); send the dimension too if that wait matters.
        send(block, Drive.OPEN, "", false);
    }

    /**
     * {@code emulator} says this player's disc is in it. One this drive isn't in (its player left and came back, or
     * the game stopped with the disc in) is told it is out.
     */
    public static void check(DriveEmulatorBlock.Entity emulator) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(emulator.host())
                && !(loaded != null && emulator.getBlockPos().equals(block))) {
            send(emulator.getBlockPos(), Drive.OPEN, "", false);
        }
    }

    private static void send(BlockPos emulator, Drive state, String text, boolean dvd) {
        PacketDistributor.sendToServer(new DriveEmulatorBlock.Update(emulator, state, text == null ? "" : text, dvd));
    }

    /**
     * The disc in the real drive: the first of this computer's optical drives with a DVD or Blu-ray in it, or the
     * one drive or folder cms-client.toml's discDrive names. Off the game thread: a drive spinning up takes seconds.
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
            boolean bluray = Files.isRegularFile(root.resolve("BDMV/index.bdmv"));
            if (bluray || Files.isRegularFile(root.resolve("VIDEO_TS/VIDEO_TS.IFO"))) {
                String label = "";
                try {
                    label = root.getFileName() == null ? Files.getFileStore(root).name() : root.getFileName().toString();
                } catch (IOException unlabelled) {
                    // Nameless.
                }
                return Optional.of(new Disc(root, label == null ? "" : label, !bluray));
            }
        }
        return Optional.empty();
    }
}
