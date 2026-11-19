package org.haruyq.createmediasystems.items.walkman.nws310;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A pocket FM receiver. While it is on, anywhere in a player's inventory, they hear the radio station of its
 * frequency whose tower reaches them (see blocks.radio.RadioStations), through its earphones: nobody else does.
 * Right-click opens its controls (see client.LoudmanScreen).
 */
public final class LoudmanItem extends Item {
    public static final int MAX_VOLUME = 30;
    /** Opens the controls of the Loudman in a hand: the client's screen, set as the client starts. */
    public static Consumer<InteractionHand> controls = hand -> {
    };
    /** Its body's colour (RGB). */
    public final int color;

    /** What a Loudman is set to: its frequency (tenths of a MHz), its volume (0..MAX_VOLUME), and whether it is on. */
    public record Tuning(int frequency, int volume, boolean on) {
        public static final Tuning DEFAULT = new Tuning(RadioFeature.DEFAULT_FREQUENCY, MAX_VOLUME / 2, false);
        public static final Codec<Tuning> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("frequency").forGetter(Tuning::frequency),
                Codec.INT.fieldOf("volume").forGetter(Tuning::volume),
                Codec.BOOL.fieldOf("on").forGetter(Tuning::on)).apply(i, Tuning::new));
        public static final StreamCodec<ByteBuf, Tuning> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Tuning::frequency, ByteBufCodecs.VAR_INT, Tuning::volume,
                ByteBufCodecs.BOOL, Tuning::on, Tuning::new);

        public Tuning {
            frequency = RadioFeature.clampFrequency(frequency);
            volume = Mth.clamp(volume, 0, MAX_VOLUME);
        }
    }

    public LoudmanItem(Properties properties, int color) {
        super(properties);
        this.color = color;
    }

    public static Tuning tuning(ItemStack stack) {
        return stack.getOrDefault(LoudmanFeature.TUNING.get(), Tuning.DEFAULT);
    }

    /** The Loudman {@code player} listens to: the first in their inventory that is on; null for none. */
    @Nullable
    public static ItemStack playing(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof LoudmanItem && tuning(stack).on()) {
                return stack;
            }
        }
        return null;
    }

    /** The frequency (tenths of a MHz) {@code player}'s Loudman receives; 0 while they listen to none. */
    public static int tuned(Player player) {
        ItemStack playing = player.isSpectator() ? null : playing(player);
        return playing == null ? 0 : tuning(playing).frequency();
    }

    /** How loud {@code player}'s Loudman plays, 0 to 1; 0 while they listen to none. */
    public static float volume(Player player) {
        ItemStack playing = playing(player);
        return playing == null ? 0 : tuning(playing).volume() / (float) MAX_VOLUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            controls.accept(hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack old, ItemStack next, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(old, next);
    }

    /** Client → server: the Loudman in that hand is set to {@code tuning}. */
    public record Set(boolean offhand, Tuning tuning) implements CustomPacketPayload {
        public static final Type<Set> TYPE = new Type<>(CreateMediaSystems.id("loudman_set"));
        public static final StreamCodec<ByteBuf, Set> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Set::offhand, Tuning.STREAM_CODEC, Set::tuning, Set::new);

        public Set(InteractionHand hand, Tuning tuning) {
            this(hand == InteractionHand.OFF_HAND, tuning);
        }

        @Override
        public Type<Set> type() {
            return TYPE;
        }

        static void register(PayloadRegistrar registrar) {
            registrar.playToServer(TYPE, CODEC, (payload, context) -> {
                ItemStack held = context.player().getItemInHand(payload.offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                if (held.getItem() instanceof LoudmanItem) {
                    // Tuning's constructor keeps its frequency and volume in range.
                    held.set(LoudmanFeature.TUNING.get(), payload.tuning);
                }
            });
        }
    }
}
