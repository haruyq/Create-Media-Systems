package org.haruyq.createmediasystems.items.instrument;

import java.util.List;

import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

public final class InstrumentFeature implements Feature {
    public static final DeferredItem<InstrumentItem> CORAL_RED = stratocaster("coral_red");
    public static final DeferredItem<InstrumentItem> POLAR_WHITE = stratocaster("polar_white");
    public static final DeferredItem<InstrumentItem> SUNBURST = stratocaster("sunburst");
    public static final DeferredItem<InstrumentItem> BLACK = stratocaster("black");
    public static final DeferredItem<InstrumentItem> FLUTE = item(Kind.FLUTE, "yfl_212");
    public static final DeferredItem<InstrumentItem> TRUMPET = item(Kind.TRUMPET, "ytr_2330");
    public static final List<DeferredItem<InstrumentItem>> ITEMS = List.of(CORAL_RED, POLAR_WHITE, SUNBURST, BLACK, FLUTE, TRUMPET);

    private static DeferredItem<InstrumentItem> stratocaster(String finish) {
        return item(Kind.STRATOCASTER, "player_ii_stratocaster_" + finish);
    }

    private static DeferredItem<InstrumentItem> item(Kind kind, String id) {
        return CreateMediaSystems.ITEMS.register(id, () -> new InstrumentItem.TwoHanded(kind, id));
    }
}
