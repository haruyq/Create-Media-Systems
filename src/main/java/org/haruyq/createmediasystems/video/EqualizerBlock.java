package org.haruyq.createmediasystems.video;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Turns bands of a Blu-ray player's video's sound up or down, for everyone, with the three faders on its top (see
 * EqualizerFaders), facing whoever placed it. Touching the Blu-ray player, its faders are Low, Mid and Hi; a second
 * equalizer touching that one splits each of those bands in two, taking the lower half as BASS, Low-Mid and Hi-Mid.
 */
public class EqualizerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<EqualizerBlock> CODEC = simpleCodec(EqualizerBlock::new);
    public static final int FADERS = 3;
    public static final float MAX_GAIN = 12;

    public EqualizerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<EqualizerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    /** The first equalizer touching {@code pos}, if any. */
    @Nullable
    private static Entity touching(BlockGetter level, BlockPos pos) {
        return Arrays.stream(Direction.values()).map(side -> level.getBlockEntity(pos.relative(side)))
                .filter(Entity.class::isInstance).map(Entity.class::cast).findFirst().orElse(null);
    }

    private static boolean touchesPlayer(BlockGetter level, BlockPos pos) {
        return Arrays.stream(Direction.values())
                .anyMatch(side -> level.getBlockState(pos.relative(side)).is(CreateMediaSystems.VIDEO_PLAYER.get()));
    }

    /** Whether the equalizer at {@code pos} is a second one: touching one that touches a Blu-ray player. */
    static boolean isSecond(BlockGetter level, BlockPos pos) {
        return !touchesPlayer(level, pos) && Arrays.stream(Direction.values()).map(pos::relative)
                .anyMatch(beside -> level.getBlockEntity(beside) instanceof Entity && touchesPlayer(level, beside));
    }

    /**
     * What the Blu-ray player at {@code player} plays through, as {@code low, high, gain} triples: the first equalizer
     * touching it and the first touching that one, if any.
     */
    public static float[] bands(BlockGetter level, BlockPos player) {
        Entity first = touching(level, player);
        if (first == null) {
            return new float[0];
        }
        Entity second = touching(level, first.getBlockPos());
        float[] bands = new float[(second == null ? 1 : 2) * FADERS * 3];
        int n = 0;
        for (int fader = 0; fader < FADERS; fader++) {
            Band band = Band.of(fader, false);
            bands[n++] = band.low(second == null);
            bands[n++] = band.high();
            bands[n++] = first.gains[fader];
            if (second != null) {
                band = Band.of(fader, true);
                bands[n++] = band.low(false);
                bands[n++] = band.high();
                bands[n++] = second.gains[fader];
            }
        }
        return bands;
    }

    /**
     * Where a look from {@code eye} along {@code look} (both in the world) crosses the top of the equalizer at
     * {@code pos}, in its block space, which moves and turns with it on a sub-level (see SableCompat); null if the
     * look doesn't come down onto it from above.
     */
    @Nullable
    public static Vec3 aim(Level level, BlockPos pos, Vec3 eye, Vec3 look) {
        Vec3 from = SableCompat.toBlockSpace(level, pos, eye);
        Vec3 along = SableCompat.toBlockSpace(level, pos, eye.add(look)).subtract(from);
        double above = from.y - (pos.getY() + 1);
        return above > 0 && along.y < 0 ? from.add(along.scale(above / -along.y)) : null;
    }

    /** {@code gain} as a fader can set it: in half-dB steps, within {@link #MAX_GAIN} either way. */
    static float snap(float gain) {
        return Mth.clamp(Math.round(gain * 2) / 2f, -MAX_GAIN, MAX_GAIN);
    }

    public static String db(float gain) {
        return String.format(Locale.ROOT, "%+.1f dB", gain);
    }

    /**
     * The bands, a half-decade each from BASS (20 to 63 Hz) to Hi (6.3 to 20 kHz). Alone, an equalizer's Low, Mid and
     * Hi each take the whole decade below their top.
     */
    public enum Band {
        BASS("BASS", 0xE03C3C), LOW("Low", 0xF08A24), LOW_MID("Low-Mid", 0xF0D030), MID("Mid", 0x5CC850),
        HI_MID("Hi-Mid", 0x30C0E0), HI("Hi", 0x8A5CF0);

        public final String label;
        /** Its knob's colour (RGB). */
        public final int color;

        Band(String label, int color) {
            this.label = label;
            this.color = color;
        }

        /** Fader {@code fader}'s band, on a second equalizer if {@code second}. */
        public static Band of(int fader, boolean second) {
            return values()[2 * fader + (second ? 0 : 1)];
        }

        float high() {
            return (float) (20 * Math.pow(10, (ordinal() + 1) / 2.0));
        }

        /** Its low edge: half a decade below its top, or a whole one if {@code whole}. */
        float low(boolean whole) {
            return high() / (float) Math.pow(10, whole ? 1 : 0.5);
        }
    }

    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        /** How much each fader raises its band (dB; lowers if negative). */
        private final float[] gains = new float[FADERS];

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.EQUALIZER_ENTITY.get(), pos, state);
        }

        public float gain(int fader) {
            return gains[fader];
        }

        public Band band(int fader) {
            return Band.of(fader, level != null && isSecond(level, worldPosition));
        }

        /**
         * Sets fader {@code fader}'s gain, kept to what it can do (half-dB steps); on the server, everyone hears it at
         * once. On the client it is heard here only, until the fader is let go (see EqualizerFaders).
         */
        public void set(int fader, float gain) {
            if (fader < 0 || fader >= FADERS || !Float.isFinite(gain)) {
                return;
            }
            gains[fader] = snap(gain);
            if (level != null && !level.isClientSide) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(Component.translatable("block.create_media_systems.equalizer")));
            for (int fader = FADERS - 1; fader >= 0; fader--) {
                Band band = band(fader);
                tooltip.add(Component.literal("     " + band.label + "  " + db(gains[fader])).withColor(band.color));
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            for (int fader = 0; fader < FADERS; fader++) {
                tag.putFloat("Gain" + fader, gains[fader]);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            for (int fader = 0; fader < FADERS; fader++) {
                gains[fader] = snap(tag.getFloat("Gain" + fader));
            }
        }

        /** Every client plays its Blu-ray player through it. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Client → server: the gain (dB) of fader {@code fader} of the equalizer at {@code equalizer}, once let go. */
    public record SetEqualizer(BlockPos equalizer, int fader, float gain) implements CustomPacketPayload {
        public static final Type<SetEqualizer> TYPE = new Type<>(CreateMediaSystems.id("set_equalizer"));
        public static final StreamCodec<ByteBuf, SetEqualizer> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetEqualizer::equalizer,
                ByteBufCodecs.VAR_INT, SetEqualizer::fader,
                ByteBufCodecs.FLOAT, SetEqualizer::gain,
                SetEqualizer::new);

        @Override
        public Type<SetEqualizer> type() {
            return TYPE;
        }
    }

    /** A fader of an equalizer within reach was let go. */
    public static void setEqualizer(Player player, SetEqualizer payload) {
        Entity equalizer = Reach.blockEntity(player, payload.equalizer(), Reach.HAND, Entity.class);
        if (equalizer != null) {
            equalizer.set(payload.fader(), payload.gain());
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetEqualizer.TYPE, SetEqualizer.CODEC,
                (payload, context) -> setEqualizer(context.player(), payload));
    }
}
