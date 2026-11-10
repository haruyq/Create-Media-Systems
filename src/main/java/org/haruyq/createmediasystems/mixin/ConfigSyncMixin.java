package org.haruyq.createmediasystems.mixin;

import java.util.List;

import org.haruyq.createmediasystems.ServerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;

/**
 * NeoForge sends each player logging in every server config file as it is on disk: CMS's goes without its P2P secret
 * (see ServerConfig#withoutSecrets). Required, so a NeoForge that no longer has this method fails to start rather than
 * send the secret.
 */
@Mixin(ConfigSync.class)
abstract class ConfigSyncMixin {
    @Inject(method = "syncConfigs", at = @At("RETURN"), cancellable = true)
    private static void cms$withoutSecrets(CallbackInfoReturnable<List<ConfigFilePayload>> result) {
        result.setReturnValue(result.getReturnValue().stream()
                .map(p -> p.fileName().equals(ServerConfig.FILE)
                        ? new ConfigFilePayload(p.fileName(), ServerConfig.withoutSecrets(p.contents())) : p)
                .toList());
    }
}
