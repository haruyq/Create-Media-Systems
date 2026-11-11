package org.haruyq.createmediasystems.p2p;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.ServerConfig;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Direct P2P between players, which the server introduces through the API (see P2p). */
public final class P2pFeature implements Feature {
    /** Read by P2p only, and never sent to players. */
    public static final ModConfigSpec.ConfigValue<String> SERVER_TOKEN = ServerConfig.secret("p2pServerToken",
            "Direct P2P's secret: the CMS-API's P2P_SERVER_TOKEN, 16 to 512 characters without spaces. Empty turns P2P off; the CMS_P2P_SERVER_TOKEN environment variable takes its place if set. Players never receive it, unlike the rest of this file: never give it to them.");

    @Override
    public void register(IEventBus modBus) {
        P2p.register();
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        P2p.registerPayloads(registrar);
    }
}
