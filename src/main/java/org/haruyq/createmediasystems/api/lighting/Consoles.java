package org.haruyq.createmediasystems.api.lighting;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.jetbrains.annotations.ApiStatus;

/** Every lighting console, for a mod that runs their shows. */
@ApiStatus.Experimental
public final class Consoles {
    private Consoles() {
    }

    /**
     * Runs {@code ticker} on the server each tick for every formed console that is loaded, on its controller: what a
     * mod changing its settings over time (see {@link ConsoleView#set}) works from.
     */
    public static void onTick(Consumer<ConsoleView> ticker) {
        LightingConsoleBlock.onTick(ticker::accept);
    }
}
