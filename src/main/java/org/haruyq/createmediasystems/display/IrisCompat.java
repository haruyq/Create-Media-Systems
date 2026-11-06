package org.haruyq.createmediasystems.display;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModList;

/** Whether an Iris shader pack is on, asked through Iris' API by reflection: no dependency, and false without Iris. */
final class IrisCompat {
    private static final boolean LOADED = ModList.get().isLoaded("iris");
    @Nullable
    private static final MethodHandle IN_USE = LOADED ? api("isShaderPackInUse") : null;
    @Nullable
    private static final MethodHandle SHADOW_PASS = LOADED ? api("isRenderingShadowPass") : null;
    private static volatile boolean failed;

    private IrisCompat() {
    }

    static boolean shaderPackInUse() {
        return ask(IN_USE);
    }

    /** Whether the pack is drawing its shadow map now, from the sun rather than the player's eyes. */
    static boolean shadowPass() {
        return ask(SHADOW_PASS);
    }

    private static boolean ask(@Nullable MethodHandle question) {
        if (question == null || failed) {
            return false;
        }
        try {
            return (boolean) question.invoke();
        } catch (Throwable e) {
            failed = true;
            LogUtils.getLogger().warn("Iris's API failed; displays draw as without a shader pack", e);
            return false;
        }
    }

    @Nullable
    private static MethodHandle api(String method) {
        try {
            Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api.getClass(), method,
                    MethodType.methodType(boolean.class)).bindTo(api);
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Iris is installed but its API changed; displays draw as without a shader pack", e);
            return null;
        }
    }
}
