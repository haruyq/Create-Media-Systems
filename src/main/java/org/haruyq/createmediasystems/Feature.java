package org.haruyq.createmediasystems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * One feature of the mod. Its blocks, items and other registry entries, and its server settings, are static fields of
 * the implementing class, added to CreateMediaSystems' registers and ServerConfig as the class loads.
 */
public interface Feature {
    /** Adds its listeners to the mod bus and NeoForge's, while the mod is constructed. */
    default void register(IEventBus modBus) {
    }

    /** Registers the payloads it sends, either way. */
    default void registerPayloads(PayloadRegistrar registrar) {
    }
}
