package org.haruyq.createmediasystems.items.launcher;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Attachment and expiry keep working when a loaded CD stops receiving movement ticks. */
public final class DiscProjectileLifecycle {
    private static final Set<DiscProjectile> LOADED = ConcurrentHashMap.newKeySet();

    private DiscProjectileLifecycle() {}

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof DiscProjectile disc)) return;
        if (disc.blockAttachmentExpired()) event.setCanceled(true);
        else LOADED.add(disc);
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof DiscProjectile disc) LOADED.remove(disc);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        // Removing a recovered entity emits EntityLeaveLevelEvent, so iterate a snapshot.
        boolean checkPlayback = event.getServer().getTickCount() % 20 == 0;
        for (DiscProjectile disc : List.copyOf(LOADED)) {
            disc.tickLoaded(checkPlayback);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LOADED.clear();
    }
}
