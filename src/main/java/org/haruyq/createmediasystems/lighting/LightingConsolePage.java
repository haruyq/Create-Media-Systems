package org.haruyq.createmediasystems.lighting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Another mod's page of the lighting console (client side), for its family of fixtures
 * (StageLightBlock.Entity.family): while the console's selected group holds that family, the page takes over the
 * console's three right-hand bays, deck and monitors (x from 1 to 4), drawing them (render) and answering what is
 * pointed at there (hit). The left bay, the group faders and the group list, stays the console's own, so groups are
 * chosen as ever, unless the page takes it too (ownsLeftBay). A group holds one family only (LightingConsoleBlock.Entity.accepts), so each shows one page.
 *
 * <p>Deck points are (x across, from 0 at the left to 4; z from the front edge, 0, back to 1.55), monitor points
 * (x across; y up, from 1.12 to 1.86), as LightingConsoleSurface draws them. Settings the console keeps are changed
 * with {@link #send}; a page's own are best kept in {@link LightingConsoleBlock.Entity#extras}, saved and synced
 * with the console, changed through its own packets.
 */
public interface LightingConsolePage {
    /**
     * Whether this page takes the left bay too, group faders and group list, drawing and answering all four
     * bays (x from 0 to 4): it then picks groups itself (send Control.GROUP). The console's own, if not.
     */
    default boolean ownsLeftBay() {
        return false;
    }

    /** Draws the page's bays. */
    void render(LightingConsoleBlock.Entity console, LightingConsoleSurface surface);

    /** What is at {@code point} on the deck, or on the monitors if {@code monitor}; null for nothing. */
    @Nullable
    Control hit(LightingConsoleBlock.Entity console, Vec3 point, boolean monitor);

    /** A thing on the page a player can point at. */
    interface Control {
        /** Where it is, as the console highlights it: deck (x, z) or monitor (x, y). */
        Area area();

        boolean monitor();

        /** What it is, shown while it is pointed at. */
        Component label();

        /** What it is set to, shown after the label. */
        Component value();

        /** Used (right-click; with sneak if {@code shift}). */
        default void press(boolean shift) {
        }

        /** Scrolled over, a {@code step} of 1 or -1, with sneak ({@code shift}) or Ctrl held. */
        default void scroll(int step, boolean shift, boolean control) {
        }

        /** Whether holding use on it drags it (a fader, an aim pad): then drag follows. */
        default boolean drags() {
            return false;
        }

        /** While dragged, the deck point the player looks at. */
        default void drag(Vec3 point) {
        }
    }

    /** A rectangle on the deck (left, front, right, back) or a monitor (left, bottom, right, top). */
    record Area(float left, float bottom, float right, float top) {
        public boolean contains(double x, double vertical) {
            return x >= left && x <= right && vertical >= bottom && vertical <= top;
        }
    }

    Map<String, LightingConsolePage> PAGES = new ConcurrentHashMap<>();

    /** Adds the page for {@code family}'s fixtures, from the client's setup. */
    static void register(String family, LightingConsolePage page) {
        PAGES.put(family, page);
    }

    /** The page {@code console} shows now: its selected group's family's, or null for the console's own. */
    @Nullable
    static LightingConsolePage showing(LightingConsoleBlock.Entity console) {
        String family = console.family(console.settings.selectedGroup);
        return family == null ? null : PAGES.get(family);
    }

    /**
     * Changes a setting the console keeps, as its own controls do: here at once, and on the server. {@code group}
     * is the group it applies to (most settings, the selected one); value and other as LightingPackets.apply takes
     * them for {@code control}.
     */
    static void send(LightingConsoleBlock.Entity console, LightingPackets.Control control, int group, int index,
                     float value, float other) {
        LightingPackets.Change change = new LightingPackets.Change(console.getBlockPos(), group, control.ordinal(), index,
                value, other);
        LightingPackets.apply(console, change, Minecraft.getInstance().player);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(change);
    }
}
