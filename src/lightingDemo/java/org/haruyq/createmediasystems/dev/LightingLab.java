package org.haruyq.createmediasystems.dev;

import java.nio.file.Path;

import org.haruyq.createmediasystems.lighting.ConsoleLayout;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The lighting lab, the world every lighting harness runs in (LightingDemoBootstrap builds it; the benchmark, the
 * network test and the Sable demo build on it): which harness this run is, what the world is called, where its console
 * and media stand, and its video.
 */
public final class LightingLab {
    /** This run is the Sable demo's (-PsableTests with -PlightingDemo). */
    public static final boolean SABLE = Boolean.getBoolean("cms.sableLightingDemo");
    /** This run is the network test's (-PlightingNetworkTest): a server and two clients. */
    public static final boolean NETWORK = Boolean.getBoolean("cms.lightingNetworkTest");
    public static final String LEVEL_NAME = SABLE ? "CMS Sable Lighting Lab" : "CMS Lighting Lab";
    /** Its save's folder. */
    public static final String LEVEL_ID = SABLE ? "cms_sable_lighting_lab" : "cms_lighting_lab";
    /** The console's root desk; it faces south. */
    public static final BlockPos CONSOLE = new BlockPos(-1, 65, 5);
    public static final BlockPos PLAYER = new BlockPos(-8, 65, 5);
    public static final BlockPos SPEAKER = new BlockPos(-6, 65, 5);
    public static final BlockPos DISPLAY = new BlockPos(-5, 67, 5);
    /** Its video's file name, as served and as bundled. */
    public static final String MEDIA = "cms-lighting-lab.mp4";

    private LightingLab() {
    }

    /** Where its video is kept, under a game directory (the server's, for its media server). */
    public static Path media() {
        return Path.of("media", MEDIA);
    }

    /** Whether {@code server} runs the lab's world. */
    public static boolean runs(MinecraftServer server) {
        return server.getWorldData().getLevelName().equals(LEVEL_NAME);
    }

    /** The lab's console in {@code level}, if it is there. */
    @Nullable
    public static LightingConsoleBlock.Entity console(Level level) {
        return level.getBlockEntity(CONSOLE) instanceof LightingConsoleBlock.Entity console ? console : null;
    }

    /** Builds a console at {@code origin} facing {@code facing} and forms it at once. */
    public static LightingConsoleBlock.Entity buildConsole(Level level, BlockPos origin, Direction facing) {
        ConsoleLayout.place(level, origin, facing, Block.UPDATE_ALL);
        LightingConsoleBlock.Entity console = (LightingConsoleBlock.Entity) level.getBlockEntity(origin);
        console.updateStructure();
        return console;
    }
}
