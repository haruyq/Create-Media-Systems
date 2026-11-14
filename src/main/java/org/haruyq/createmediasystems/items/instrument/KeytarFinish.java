package org.haruyq.createmediasystems.items.instrument;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public record KeytarFinish(boolean dark, DyeColor blade) {
    public static final KeytarFinish DEFAULT = new KeytarFinish(false, DyeColor.LIGHT_GRAY);
    public static final Codec<KeytarFinish> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("dark", false).forGetter(KeytarFinish::dark),
            DyeColor.CODEC.optionalFieldOf("blade", DyeColor.LIGHT_GRAY).forGetter(KeytarFinish::blade)
    ).apply(instance, KeytarFinish::new));
    public static final StreamCodec<ByteBuf, KeytarFinish> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, KeytarFinish::dark, DyeColor.STREAM_CODEC, KeytarFinish::blade, KeytarFinish::new);

    public static KeytarFinish of(ItemStack stack) {
        return stack.getOrDefault(WearableInstruments.KEYTAR_FINISH.get(), DEFAULT);
    }

    public void applyTo(ItemStack stack) {
        if (equals(DEFAULT)) stack.remove(WearableInstruments.KEYTAR_FINISH.get());
        else stack.set(WearableInstruments.KEYTAR_FINISH.get(), this);
    }

    static boolean accepts(Item material) {
        return material instanceof DyeItem || material == Items.INK_SAC || material == Items.BONE_MEAL;
    }

    KeytarFinish paintedWith(Item material) {
        if (material instanceof DyeItem dye) return new KeytarFinish(dark, dye.getDyeColor());
        return new KeytarFinish(material == Items.INK_SAC, blade);
    }

    public boolean usesDyedBlade() {
        return blade != DyeColor.LIGHT_GRAY && blade != (dark ? DyeColor.RED : DyeColor.YELLOW);
    }

    MutableComponent description() {
        var shell = Component.translatable(dark ? "wearable.finish.black_shell" : "wearable.finish.white_shell");
        Component trim = switch (blade) {
            case LIGHT_GRAY -> Component.translatable("wearable.finish.silver");
            case YELLOW -> dark ? dyeName() : Component.translatable("wearable.finish.gold");
            default -> dyeName();
        };
        return Component.translatable("wearable.finish", shell, trim);
    }

    private Component dyeName() {
        return Component.translatable("color.minecraft." + blade.getName());
    }
}
