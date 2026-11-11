package org.haruyq.createmediasystems.client;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

/** One feature's part of the mod on a client: its listeners and settings, and its Ponder scenes. */
public interface ClientFeature {
    /** Adds its client listeners and its settings (see ClientConfig), while the mod is constructed on a client. */
    void register(IEventBus modBus);

    default void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
    }
}
