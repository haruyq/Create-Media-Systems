package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DiscCellTierTest {
    @Test void ordinaryProgressionNeverNeedsAnotherAddon() {
        int shots = 0; float damage = 7;
        for (DiscCellTier tier : DiscCellTier.values()) {
            assertTrue(tier.shots > shots && tier.damage > damage);
            assertEquals(tier.shots, tier.capacity() / tier.energyPerShot);
            if (!tier.optional()) assertTrue(tier.upgradeMaterials().stream()
                    .allMatch(id -> id.startsWith("minecraft:") || id.startsWith("create:")));
            shots = tier.shots; damage = tier.damage;
        }
        assertTrue(DiscCellTier.CHEMI.upgradeMaterials().contains("create_heavy_indusstry:mox_pellet"));
    }
    @Test void reloadFullyOpensBeforeFeedingAndClosesBeforeTheShotIsUnlocked() {
        var open = LauncherTimeline.sample(28, -1);
        assertEquals(1, open.latch(), 1e-6); assertEquals(1, open.tray(), 1e-6);
        assertEquals(1, open.cellOut(), 1e-6); assertEquals(0, open.disc(), 1e-6);
        assertTrue(open.glow() > .5f);
        var fed = LauncherTimeline.sample(51, -1);
        assertEquals(1, fed.disc(), 1e-6); assertEquals(1, fed.latch(), 1e-6);
        var done = LauncherTimeline.sample(LauncherTimeline.RELOAD_TICKS, -1);
        assertEquals(0, done.latch()); assertEquals(0, done.glow()); assertEquals(0, done.supportHand());
    }
}
