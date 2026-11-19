package org.haruyq.createmediasystems.blocks.radio.client;

import org.haruyq.createmediasystems.blocks.radio.RadioFeature;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

public final class RadioClient {
    /** The paint of a tower's mast: international orange and white, in bands. */
    private static final int ORANGE = 0xFFE9531E, WHITE = 0xFFF4F4F0;
    /** How many blocks tall each band of paint is. */
    private static final int BAND = 4;

    private RadioClient() {
    }

    public static void register(IEventBus modBus) {
        RadioAudio.register();
        // The bands follow the mast's height in the world, so every tower is striped without a block state for it.
        modBus.addListener((RegisterColorHandlersEvent.Block event) -> event.register(
                (state, level, pos, tint) -> pos != null && Math.floorMod(pos.getY(), 2 * BAND) >= BAND ? WHITE : ORANGE,
                RadioFeature.MAST.get(), RadioFeature.ANTENNA.get()));
        modBus.addListener((RegisterColorHandlersEvent.Item event) -> event.register((stack, tint) -> ORANGE,
                RadioFeature.MAST_ITEM.get(), RadioFeature.ANTENNA_ITEM.get()));
    }
}
