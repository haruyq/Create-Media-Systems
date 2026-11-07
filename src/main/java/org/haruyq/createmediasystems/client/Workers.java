package org.haruyq.createmediasystems.client;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Threads of our own for the light's block scans and rays (stage lights, displays). Util.backgroundExecutor is the
 * game's pool for chunk loading and lighting: long jobs there hold those up.
 */
public final class Workers {
    public static final Executor LIGHT = new ThreadPoolExecutor(threads(), threads(), 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("CMS light", 0)
            .priority(Thread.NORM_PRIORITY - 1).factory());

    private Workers() {
    }

    private static int threads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 3);
    }
}
