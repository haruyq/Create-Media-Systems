package org.haruyq.createmediasystems.api.lighting.client;

import org.haruyq.createmediasystems.api.lighting.ConsoleView;
import org.haruyq.createmediasystems.lighting.LightingConsolePage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Another mod's page of the lighting console, for its family of fixtures: while the console's selected group holds
 * that family, the page takes over the console's three right-hand bays, deck and monitors (x from 1 to 4), drawing
 * them and answering what is pointed at there. The left bay (group faders and list) stays the console's own unless
 * {@link #ownsLeftBay}. Deck points are (x across, 0 to 4; z back from the front edge, 0 to 1.55); monitor points
 * (x across; y up, 1.12 to 1.86). Settings the console keeps change through {@link ConsoleView#set}; a page's own are
 * best kept in {@link ConsoleView#extras}.
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
public interface ConsolePage {
    /** Adds the page for {@code family}'s fixtures, from client setup. */
    static void register(String family, ConsolePage page) {
        LightingConsolePage.PAGES.put(family, page);
    }

    /** Whether it takes the left bay too (x from 0 to 4), choosing groups itself (ConsoleSetting.GROUP). */
    default boolean ownsLeftBay() {
        return false;
    }

    void render(ConsoleView console, ConsoleSurface surface);

    /** What is at {@code point} on the deck, or on the monitors if {@code monitor}; null for nothing. */
    @Nullable
    Control hit(ConsoleView console, Vec3 point, boolean monitor);

    /** A thing on the page a player can point at. */
    @ApiStatus.OverrideOnly
    interface Control {
        /** Where it is, as the console highlights it: deck (x, z) or monitor (x, y). */
        Area bounds();

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
}
