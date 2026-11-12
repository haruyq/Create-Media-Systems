package org.haruyq.createmediasystems.lighting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.api.lighting.ConsoleView;
import org.haruyq.createmediasystems.api.lighting.client.ConsolePage;
import org.haruyq.createmediasystems.api.lighting.client.ConsoleSurface;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A console page as Create: Large Lights 0.1.0 implements and registers it (src/test/resources/api/legacy-links.txt),
 * on the console's own types: kept until 2.0, as are register and send. Use api.lighting.client.ConsolePage. Also
 * where every page is kept.
 */
public interface LightingConsolePage extends ConsolePage {
    void render(LightingConsoleBlock.Entity console, LightingConsoleSurface surface);

    @Nullable
    Control hit(LightingConsoleBlock.Entity console, Vec3 point, boolean monitor);

    @Override
    default void render(ConsoleView console, ConsoleSurface surface) {
        render((LightingConsoleBlock.Entity) console, (LightingConsoleSurface) surface);
    }

    @Override
    @Nullable
    default ConsolePage.Control hit(ConsoleView console, Vec3 point, boolean monitor) {
        return hit((LightingConsoleBlock.Entity) console, point, monitor);
    }

    interface Control extends ConsolePage.Control {
        Area area();

        @Override
        default ConsolePage.Area bounds() {
            Area area = area();
            return new ConsolePage.Area(area.left(), area.bottom(), area.right(), area.top());
        }
    }

    /** A rectangle on the deck (left, front, right, back) or a monitor (left, bottom, right, top). */
    record Area(float left, float bottom, float right, float top) {
        public boolean contains(double x, double vertical) {
            return x >= left && x <= right && vertical >= bottom && vertical <= top;
        }
    }

    /** Every family's page (ConsolePage.register). */
    Map<String, ConsolePage> PAGES = new ConcurrentHashMap<>();

    static void register(String family, LightingConsolePage page) {
        PAGES.put(family, page);
    }

    /** The page {@code console} shows now: its selected group's family's, or null for the console's own. */
    @Nullable
    static ConsolePage showing(LightingConsoleBlock.Entity console) {
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
        LightingPackets.Change change = new LightingPackets.Change(console.getBlockPos(), group, control, index,
                value, other);
        LightingPackets.apply(console, change, Minecraft.getInstance().player);
        PacketDistributor.sendToServer(change);
    }
}
