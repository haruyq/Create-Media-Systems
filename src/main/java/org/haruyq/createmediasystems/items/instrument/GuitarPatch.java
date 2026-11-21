package org.haruyq.createmediasystems.items.instrument;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** An ordered set of pedal connections stored on one guitar. */
public record GuitarPatch(List<Link> links) {
    public static final int MAX_PEDALS = 4;
    public static final int RANGE = 12;
    public static final GuitarPatch EMPTY = new GuitarPatch(List.of());
    public static final Codec<GuitarPatch> CODEC = Link.CODEC.listOf(0, MAX_PEDALS).xmap(GuitarPatch::new, GuitarPatch::links);
    public static final StreamCodec<ByteBuf, GuitarPatch> STREAM_CODEC = Link.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PEDALS))
            .map(GuitarPatch::new, GuitarPatch::links);

    public record Link(GlobalPos location, AutoEffect effect) {
        private static final Codec<Link> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                GlobalPos.CODEC.fieldOf("location").forGetter(Link::location),
                AutoEffect.CODEC.fieldOf("effect").forGetter(Link::effect)).apply(instance, Link::new));
        private static final StreamCodec<ByteBuf, Link> STREAM_CODEC = StreamCodec.composite(
                GlobalPos.STREAM_CODEC, Link::location, ByteBufCodecs.fromCodec(AutoEffect.CODEC), Link::effect, Link::new);
    }

    /** Resolved from the world on the game thread, then read by the sound thread. */
    public record Setting(Link link, int amount) {}

    public GuitarPatch {
        links = List.copyOf(links);
    }

    public static GuitarPatch get(ItemStack guitar) {
        return guitar.getOrDefault(InstrumentFeature.PATCH.get(), EMPTY);
    }

    static void toggle(ItemStack guitar, Player player, Link pedal) {
        var links = new ArrayList<>(get(guitar).links);
        var location = pedal.location();
        if (links.removeIf(link -> link.location().equals(location))) {
            if (links.isEmpty()) guitar.remove(InstrumentFeature.PATCH.get());
            else guitar.set(InstrumentFeature.PATCH.get(), new GuitarPatch(links));
            player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems.removed", pedal.effect().displayName()), true);
        } else if (links.size() >= MAX_PEDALS) {
            player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems.full", MAX_PEDALS), true);
        } else {
            links.add(pedal);
            guitar.set(InstrumentFeature.PATCH.get(), new GuitarPatch(links));
            player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems.connected",
                    links.size(), pedal.effect().displayName()), true);
        }
    }

    public List<Setting> settings(Player player) {
        Level level = player.level();
        return links.stream().distinct().map(link -> {
            var pos = link.location().pos();
            if (!link.location().dimension().equals(level.dimension()) || !pos.closerToCenterThan(player.position(), RANGE)
                    || !level.isLoaded(pos)) return new Setting(link, 0);
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof AutoPedalBlock pedal && pedal.effect() == link.effect()) {
                return new Setting(link, pedal.amount(state));
            }
            return new Setting(link, 0);
        }).toList();
    }
}
