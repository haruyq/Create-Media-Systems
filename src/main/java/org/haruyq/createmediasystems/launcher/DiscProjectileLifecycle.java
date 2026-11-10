package org.haruyq.createmediasystems.launcher;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Attachment and expiry keep working when a loaded CD stops receiving movement ticks. */
@EventBusSubscriber(modid = CreateMediaSystems.MOD_ID)
public final class DiscProjectileLifecycle {
    private static final Set<DiscProjectile> LOADED = ConcurrentHashMap.newKeySet();

    private DiscProjectileLifecycle() {}

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof DiscProjectile disc) LOADED.add(disc);
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof DiscProjectile disc) LOADED.remove(disc);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        // Removing a recovered entity emits EntityLeaveLevelEvent, so iterate a snapshot.
        boolean checkExpiry=event.getServer().getTickCount()%20==0;
        for (DiscProjectile disc : List.copyOf(LOADED)) {
            if(disc.attached())disc.tickLoaded();else if(checkExpiry)disc.tickPlayback();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LOADED.clear();
    }
}
