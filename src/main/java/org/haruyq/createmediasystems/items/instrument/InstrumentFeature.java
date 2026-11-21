package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

public final class InstrumentFeature implements Feature {
    public static final Supplier<DataComponentType<GuitarPatch>> PATCH = CreateMediaSystems.COMPONENTS.registerComponentType(
            "guitar_patch", builder -> builder.persistent(GuitarPatch.CODEC).networkSynchronized(GuitarPatch.STREAM_CODEC));
    public static final DeferredItem<InstrumentItem> CORAL_RED = stratocaster("coral_red");
    public static final DeferredItem<InstrumentItem> POLAR_WHITE = stratocaster("polar_white");
    public static final DeferredItem<InstrumentItem> SUNBURST = stratocaster("sunburst");
    public static final DeferredItem<InstrumentItem> BLACK = stratocaster("black");
    public static final DeferredItem<InstrumentItem> FLUTE = item(Kind.FLUTE, "yfl_212");
    public static final DeferredItem<InstrumentItem> TRUMPET = item(Kind.TRUMPET, "ytr_2330");
    public static final List<DeferredItem<InstrumentItem>> ITEMS = List.of(CORAL_RED, POLAR_WHITE, SUNBURST, BLACK, FLUTE, TRUMPET);

    public static final DeferredBlock<AutoPedalBlock> WAH = pedal(AutoEffect.WAH);
    public static final DeferredBlock<AutoPedalBlock> TREMOLO = pedal(AutoEffect.TREMOLO);
    public static final DeferredBlock<AutoPedalBlock> CHORUS = pedal(AutoEffect.CHORUS);
    public static final DeferredBlock<AutoPedalBlock> COMPRESSOR = pedal(AutoEffect.COMPRESSOR);
    public static final List<DeferredItem<BlockItem>> PEDALS = List.of(WAH, TREMOLO, CHORUS, COMPRESSOR).stream()
            .map(CreateMediaSystems.ITEMS::registerSimpleBlockItem).toList();

    private static DeferredBlock<AutoPedalBlock> pedal(AutoEffect effect) {
        return CreateMediaSystems.BLOCKS.register(effect.getSerializedName(), () ->
                new AutoPedalBlock(effect, BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion()));
    }

    private static DeferredItem<InstrumentItem> stratocaster(String finish) {
        return item(Kind.STRATOCASTER, "player_ii_stratocaster_" + finish);
    }

    private static DeferredItem<InstrumentItem> item(Kind kind, String id) {
        return CreateMediaSystems.ITEMS.register(id, () -> new InstrumentItem.TwoHanded(kind, id));
    }
}
