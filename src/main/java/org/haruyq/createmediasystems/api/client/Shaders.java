package org.haruyq.createmediasystems.api.client;

import org.haruyq.createmediasystems.client.IrisCompat;

/** Iris shader packs, as CMS sees them; false without Iris. */
public final class Shaders {
    private Shaders() {
    }

    public static boolean packInUse() {
        return IrisCompat.shaderPackInUse();
    }

    /** Whether the pack is drawing its shadow map now, from the sun rather than the player's eyes. */
    public static boolean shadowPass() {
        return IrisCompat.shadowPass();
    }
}
