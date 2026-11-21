package org.haruyq.createmediasystems.items.instrument;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum AutoEffect implements StringRepresentable {
    WAH("auto_wah_pedal"),
    TREMOLO("auto_tremolo_pedal"),
    CHORUS("auto_chorus_pedal"),
    COMPRESSOR("auto_compressor_pedal");

    public static final Codec<AutoEffect> CODEC = StringRepresentable.fromEnum(AutoEffect::values);
    private final String id;

    AutoEffect(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    Component displayName() {
        return Component.translatable("block.create_media_systems." + id);
    }
}
