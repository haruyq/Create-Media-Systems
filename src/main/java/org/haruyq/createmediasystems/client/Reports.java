package org.haruyq.createmediasystems.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Tells the player something failed, in red. Safe from any thread. */
public final class Reports {
    private static Component last;
    private static long lastAt;

    private Reports() {
    }

    /** Above the hotbar when {@code overlay}, else in chat so it isn't missed. */
    public static void show(Component message, boolean overlay) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            // Several speakers on one source fail together; say it once.
            long now = System.currentTimeMillis();
            if (mc.player == null || message.equals(last) && now - lastAt < 5000) {
                return;
            }
            last = message;
            lastAt = now;
            mc.player.displayClientMessage(message.copy().withStyle(ChatFormatting.RED), overlay);
        });
    }
}
