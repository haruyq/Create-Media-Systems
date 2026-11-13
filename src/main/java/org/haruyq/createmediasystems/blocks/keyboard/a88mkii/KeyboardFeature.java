package org.haruyq.createmediasystems.blocks.keyboard.a88mkii;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class KeyboardFeature implements Feature {
    public static final DeferredBlock<MidiKeyboardBlock> KEYBOARD = BLOCKS.register("midi_keyboard",
            () -> new MidiKeyboardBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(1.5f).noOcclusion()));
    public static final DeferredItem<?> KEYBOARD_ITEM = ITEMS.registerSimpleBlockItem("midi_keyboard", KEYBOARD);
    public static final Supplier<BlockEntityType<MidiKeyboardBlock.Entity>> KEYBOARD_ENTITY = BLOCK_ENTITIES.register(
            "midi_keyboard", () -> BlockEntityType.Builder.of(MidiKeyboardBlock.Entity::new, KEYBOARD.get()).build(null));

    /** Placeholder event: a keyboard's sound plays through it, the audio itself comes from its {@code getStream}. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PLAYED =
            SOUNDS.register("midi_keyboard", SoundEvent::createVariableRangeEvent);

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        KeyboardPackets.register(registrar);
    }
}
