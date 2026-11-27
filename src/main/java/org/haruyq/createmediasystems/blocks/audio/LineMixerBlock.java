package org.haruyq.createmediasystems.blocks.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.MixBus;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Mixes sources into one line: a speaker linked to it plays them all, each as loud as its channel's fader and the
 * master's have it. Two blocks wide, its front towards {@code FACING}: the left block as its player faces it keeps
 * what it is set to, the right one ({@link #RIGHT}) is only its body. A source is plugged into a channel, and a
 * speaker linked to the mixer, with the range quill; the faders on its top are held and dragged.
 *
 * <p>Mixers set side by side, facing the same way, play as one: the rightmost has {@link #MAIN_CHANNELS} channels
 * and the master, each one to its left is an extension, with {@link #EXTENSION_CHANNELS} channels and no master.
 */
public class LineMixerBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable,
        IProxyHoveringInformation, SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<LineMixerBlock> CODEC = simpleCodec(LineMixerBlock::new);
    public static final int MAIN_CHANNELS = 5, EXTENSION_CHANNELS = 7;
    /** The master fader's number, after any channel's. */
    public static final int MASTER = EXTENSION_CHANNELS;
    /**
     * The most mixers side by side that play as one: no more are placed in a row.
     * ponytail: a fixed bound on the walk along a row. Raise it if longer desks are wanted.
     */
    public static final int MAX_UNITS = 16;
    /** How many places a fader has above its lowest. */
    public static final int STEPS = 100;
    /** Whether a block is the right half of its mixer. */
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    /** Its top, where the faders are (sixteenths). */
    public static final float HEIGHT = 4;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, HEIGHT, 16);
    /** Both halves, from the one whose other half is that way. */
    private static final Map<Direction, VoxelShape> WHOLE = new EnumMap<>(Direction.class);

    static {
        for (Direction other : Direction.Plane.HORIZONTAL) {
            WHOLE.put(other, Shapes.or(SHAPE, SHAPE.move(other.getStepX(), 0, other.getStepZ())));
        }
    }

    public LineMixerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(RIGHT, false));
    }

    /** Which way the right half is from the left: to the right of who faces its front. */
    public static Direction right(BlockState state) {
        return state.getValue(FACING).getCounterClockWise();
    }

    /** The left half of the mixer whose half {@code state} is at {@code pos}: the block that has its entity. */
    public static BlockPos root(BlockPos pos, BlockState state) {
        return state.getValue(RIGHT) ? pos.relative(right(state).getOpposite()) : pos;
    }

    /** Whether the left half of a mixer facing {@code facing} is at {@code pos}, in a chunk that is loaded. */
    private static boolean unit(Level level, BlockPos pos, Direction facing) {
        // A client has only what is loaded, and a Ponder scene's world says nothing is.
        if (!level.isClientSide && !level.isLoaded(pos)) {
            return false;
        }
        BlockState there = level.getBlockState(pos);
        return there.getBlock() instanceof LineMixerBlock && !there.getValue(RIGHT) && there.getValue(FACING) == facing;
    }

    /** How many mixers stand side by side with the one whose left half {@code state} is at {@code pos}, towards {@code side}. */
    private static int units(Level level, BlockPos pos, BlockState state, Direction side) {
        int units = 0;
        while (units < MAX_UNITS - 1 && unit(level, pos.relative(side, 2 * (units + 1)), state.getValue(FACING))) {
            units++;
        }
        return units;
    }

    /** How many mixers stand to the left of that one: all extensions, whose channels are numbered before its own. */
    public static int unitsToTheLeft(Level level, BlockPos pos, BlockState state) {
        return units(level, pos, state, right(state).getOpposite());
    }

    /** How many mixers stand to the right of that one: none for the one with the master, more for an extension. */
    public static int unitsToTheRight(Level level, BlockPos pos, BlockState state) {
        return units(level, pos, state, right(state));
    }

    /** How many channels a mixer has: an {@code extension}'s, or the one with the master's. */
    public static int channels(boolean extension) {
        return extension ? EXTENSION_CHANNELS : MAIN_CHANNELS;
    }

    /** How loud a fader at {@code position} (0 to 1) plays: nothing at its lowest, as it is at its highest. */
    public static float gain(float position) {
        return position * position;
    }

    @Override
    protected MapCodec<LineMixerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, RIGHT);
    }

    /** Drawn by its renderer, both halves from the left one. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Outlined whole, from either half: its own block and the other half's. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return WHOLE.get(state.getValue(RIGHT) ? right(state).getOpposite() : right(state));
    }

    /** Each half stands on its own block. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Goggles on either half read the left one, which has its entity. */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        return root(pos, state);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        BlockPos beside = context.getClickedPos().relative(right(state));
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return level.getWorldBorder().isWithinBounds(beside) && level.getBlockState(beside).canBeReplaced(context)
                && unitsToTheLeft(level, pos, state) + unitsToTheRight(level, pos, state) < MAX_UNITS ? state : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            level.setBlock(pos.relative(right(state)), state.setValue(RIGHT, true), Block.UPDATE_ALL);
        }
    }

    /** The other half of the mixer whose half {@code state} is at {@code pos}, if it is still there. */
    @Nullable
    private BlockPos otherHalf(Level level, BlockPos pos, BlockState state) {
        BlockPos other = state.getValue(RIGHT) ? root(pos, state) : pos.relative(right(state));
        BlockState there = level.getBlockState(other);
        return there.is(this) && there.getValue(RIGHT) != state.getValue(RIGHT)
                && there.getValue(FACING) == state.getValue(FACING) ? other : null;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // In creative, breaking the right half must not drop the left one's item.
        if (!level.isClientSide && player.isCreative() && state.getValue(RIGHT)) {
            BlockPos root = otherHalf(level, pos, state);
            if (root != null) {
                level.destroyBlock(root, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this)) {
            BlockPos other = otherHalf(level, pos, state);
            if (other != null && state.getValue(RIGHT)) {
                level.destroyBlock(other, true);
            } else if (other != null) {
                level.removeBlock(other, true);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    /** Turning one half alone would tear it in two. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!context.getLevel().isClientSide) {
            context.getLevel().destroyBlock(root(context.getClickedPos(), state), !player.isCreative(), player);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /** Mirrored, its halves change places: what was to the right of its front is to the left. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return mirror == Mirror.NONE ? state : super.mirror(state, mirror).cycle(RIGHT);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(RIGHT) ? null : new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != AudioFeature.LINE_MIXER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l);
    }

    public static class Entity extends BlockEntity implements MixBus, IHaveGoggleInformation {
        /** The source plugged into each channel: an extension has them all, the one with the master the first few. */
        private final Link[] inputs = new Link[EXTENSION_CHANNELS];
        /** Where each fader is, 0 (its lowest, silent) to 1: the channels', then the master's. */
        private final float[] faders = new float[EXTENSION_CHANNELS + 1];

        public Entity(BlockPos pos, BlockState state) {
            super(AudioFeature.LINE_MIXER_ENTITY.get(), pos, state);
            Arrays.fill(inputs, Link.NONE);
            Arrays.fill(faders, 1);
        }

        /** Whether a mixer stands to its right: it is then an extension of the rightmost. */
        public boolean extension() {
            return level != null && unitsToTheRight(level, worldPosition, getBlockState()) > 0;
        }

        /** How many channels it has, standing where it does. */
        public int channels() {
            return LineMixerBlock.channels(extension());
        }

        /** The number, from 0 along its whole row, of its first channel. */
        public int firstChannel() {
            return level == null ? 0 : EXTENSION_CHANNELS * unitsToTheLeft(level, worldPosition, getBlockState());
        }

        /** The mixers that play as one with it, from the leftmost: the last has their master, the others are extensions. */
        public List<Entity> row() {
            List<Entity> row = new ArrayList<>();
            if (level == null) {
                return row;
            }
            Direction facing = getBlockState().getValue(FACING), right = right(getBlockState());
            BlockPos first = worldPosition.relative(right, -2 * unitsToTheLeft(level, worldPosition, getBlockState()));
            for (int unit = 0; unit < MAX_UNITS; unit++) {
                BlockPos at = first.relative(right, 2 * unit);
                if (!unit(level, at, facing) || !(level.getBlockEntity(at) instanceof Entity mixer)) {
                    break;
                }
                row.add(mixer);
            }
            return row;
        }

        /** Where the source plugged into {@code channel} is; null for none. */
        @Nullable
        public BlockPos input(int channel) {
            return inputs[channel].pos();
        }

        public float fader(int fader) {
            return faders[fader];
        }

        /** Its whole row's sources, whichever of its mixers a speaker is linked to. */
        @Override
        public List<Feed> feeds(ServerLevel level) {
            List<Entity> row = row();
            List<Feed> feeds = new ArrayList<>();
            Set<BlockPos> fed = new HashSet<>();
            for (int unit = 0; unit < row.size(); unit++) {
                Entity mixer = row.get(unit);
                for (int channel = 0; channel < LineMixerBlock.channels(unit < row.size() - 1); channel++) {
                    BlockPos source = mixer.input(channel);
                    if (source != null && fed.add(source)) {
                        feeds.add(new Feed(source, LineMixerBlock.gain(mixer.faders[channel])
                                * LineMixerBlock.gain(row.getLast().faders[MASTER])));
                    }
                }
            }
            return feeds;
        }

        /** Its sources as they are now, where Sable moved them. */
        public void tick(ServerLevel level) {
            if (!Streams.relayDue(level)) {
                return;
            }
            for (int channel = 0; channel < inputs.length; channel++) {
                Link followed = inputs[channel].follow(level);
                if (!followed.equals(inputs[channel])) {
                    inputs[channel] = followed;
                    sync();
                }
            }
        }

        /**
         * Moves fader {@code fader}, one of the channels it has or (without a mixer to its right) the master, to
         * {@code position} (0 to 1), kept to its steps: everyone hears it at once.
         */
        public void setFader(int fader, float position) {
            boolean extension = extension();
            boolean has = fader == MASTER ? !extension : fader >= 0 && fader < LineMixerBlock.channels(extension);
            if (!has || !Float.isFinite(position)) {
                return;
            }
            float stepped = Mth.clamp(Math.round(position * STEPS), 0, STEPS) / (float) STEPS;
            if (stepped == faders[fader]) {
                return;
            }
            faders[fader] = stepped;
            sync();
            changed(fader == MASTER ? sources() : input(fader) == null ? Set.of() : Set.of(input(fader)));
        }

        /**
         * Plugs the source at {@code source} into {@code channel}, out of any other channel of its row it was in; null
         * unplugs what was there.
         */
        public void plug(ServerLevel level, int channel, @Nullable BlockPos source) {
            Set<BlockPos> heard = sources();
            for (Entity mixer : row()) {
                for (int other = 0; other < mixer.inputs.length; other++) {
                    if (source != null && source.equals(mixer.input(other))) {
                        mixer.inputs[other] = Link.NONE;
                        mixer.sync();
                    }
                }
            }
            inputs[channel] = Link.to(level, source);
            heard.addAll(sources());
            sync();
            changed(heard);
        }

        /** What its row passes on. */
        private Set<BlockPos> sources() {
            Set<BlockPos> sources = new LinkedHashSet<>();
            List<Entity> row = row();
            for (int unit = 0; unit < row.size(); unit++) {
                for (int channel = 0; channel < LineMixerBlock.channels(unit < row.size() - 1); channel++) {
                    if (row.get(unit).input(channel) != null) {
                        sources.add(row.get(unit).input(channel));
                    }
                }
            }
            return sources;
        }

        /** The speakers playing {@code sources} relay now, rather than at their next relay. */
        private void changed(Set<BlockPos> sources) {
            if (level instanceof ServerLevel server && !sources.isEmpty()) {
                Streams.changed(server, sources);
            }
        }

        private void sync() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            boolean extension = extension();
            int first = firstChannel();
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            for (int channel = 0; channel < LineMixerBlock.channels(extension); channel++) {
                BlockPos source = input(channel);
                Component plugged = source == null || level == null
                        ? Component.translatable("create_media_systems.line_mixer.empty").withStyle(ChatFormatting.DARK_GRAY)
                        : level.getBlockState(source).getBlock().getName().withStyle(ChatFormatting.GRAY);
                tooltip.add(Component.literal("     CH" + (first + channel + 1) + "  "
                        + percent(faders[channel]) + "  ").append(plugged));
            }
            if (!extension) {
                tooltip.add(Component.literal("     MASTER  " + percent(faders[MASTER])));
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            for (int channel = 0; channel < inputs.length; channel++) {
                inputs[channel].save(tag, "Input" + channel, "InputId" + channel);
            }
            for (int fader = 0; fader < faders.length; fader++) {
                tag.putFloat(fader == MASTER ? "Master" : "Fader" + fader, faders[fader]);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            for (int channel = 0; channel < inputs.length; channel++) {
                inputs[channel] = Link.load(tag, "Input" + channel, "InputId" + channel);
            }
            for (int fader = 0; fader < faders.length; fader++) {
                String key = fader == MASTER ? "Master" : "Fader" + fader;
                faders[fader] = tag.contains(key) ? Mth.clamp(tag.getFloat(key), 0, 1) : 1;
            }
        }

        /** Every client draws its faders, and what is plugged into it. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    public static String percent(float position) {
        return Math.round(position * 100) + "%";
    }

    /** Client → server: fader {@code fader} of the mixer at {@code mixer} is at {@code position}, as it is dragged. */
    public record SetFader(BlockPos mixer, int fader, float position) implements CustomPacketPayload {
        public static final Type<SetFader> TYPE = new Type<>(CreateMediaSystems.id("set_mixer_fader"));
        public static final StreamCodec<ByteBuf, SetFader> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetFader::mixer,
                ByteBufCodecs.VAR_INT, SetFader::fader,
                ByteBufCodecs.FLOAT, SetFader::position,
                SetFader::new);

        @Override
        public Type<SetFader> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: the range quill plugs the source at {@code source} into channel {@code channel} of the mixer
     * at {@code mixer}, or unplugs it if it is the one plugged in there.
     */
    public record PlugInput(BlockPos mixer, int channel, BlockPos source) implements CustomPacketPayload {
        public static final Type<PlugInput> TYPE = new Type<>(CreateMediaSystems.id("plug_mixer_input"));
        public static final StreamCodec<ByteBuf, PlugInput> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PlugInput::mixer,
                ByteBufCodecs.VAR_INT, PlugInput::channel,
                BlockPos.STREAM_CODEC, PlugInput::source,
                PlugInput::new);

        @Override
        public Type<PlugInput> type() {
            return TYPE;
        }
    }

    static void setFader(Player player, SetFader payload) {
        Entity mixer = Reach.blockEntity(player, payload.mixer(), Reach.HAND, Entity.class);
        if (mixer != null) {
            mixer.setFader(payload.fader(), payload.position());
        }
    }

    static void plugInput(ServerPlayer player, PlugInput payload) {
        Entity mixer = Reach.withQuill(player, AudioFeature.RANGE_QUILL.get(), payload.mixer(), Entity.class);
        int channel = payload.channel();
        if (mixer == null || channel < 0 || channel >= mixer.channels()) {
            return;
        }
        // As its panel numbers it: along the whole row.
        int number = mixer.firstChannel() + channel + 1;
        if (payload.source().equals(mixer.input(channel))) {
            mixer.plug(player.serverLevel(), channel, null);
            player.displayClientMessage(Component.translatable("create_media_systems.line_mixer.unplugged", number), true);
            return;
        }
        if (!Link.linkable(player, payload.source(), payload.mixer(), MediaSource.class)) {
            return;
        }
        if (player.level().getBlockEntity(payload.source()) instanceof MixBus) {
            player.displayClientMessage(Component.translatable("create_media_systems.line_mixer.no_mixer")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        mixer.plug(player.serverLevel(), channel, payload.source());
        player.displayClientMessage(Component.translatable("create_media_systems.line_mixer.plugged", number,
                player.level().getBlockState(payload.source()).getBlock().getName()), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetFader.TYPE, SetFader.CODEC, (payload, context) -> setFader(context.player(), payload));
        registrar.playToServer(PlugInput.TYPE, PlugInput.CODEC,
                (payload, context) -> plugInput((ServerPlayer) context.player(), payload));
    }

    /**
     * Where the point of the mixer at ({@code x}, {@code y}, {@code z}) of its panel (sixteenths: across from its
     * player's left, 0 to 32; up; and from its back edge towards its player, 0 to 16) is in its left block, in blocks.
     */
    public static Vec3 place(Direction facing, float x, float y, float z) {
        double across = (x - 8) / 16, out = (z - 8) / 16;
        return switch (facing) {
            case NORTH -> new Vec3(0.5 - across, y / 16, 0.5 - out);
            case EAST -> new Vec3(0.5 + out, y / 16, 0.5 - across);
            case WEST -> new Vec3(0.5 - out, y / 16, 0.5 + across);
            default -> new Vec3(0.5 + across, y / 16, 0.5 + out);
        };
    }

    /** The reverse of {@link #place}: the point of the panel (x, y, z) that {@code point} of the left block is. */
    public static Vec3 panel(Direction facing, Vec3 point) {
        double x = point.x - 0.5, z = point.z - 0.5;
        double across = switch (facing) {
            case NORTH -> -x;
            case EAST -> -z;
            case WEST -> z;
            default -> x;
        };
        double out = switch (facing) {
            case NORTH -> -z;
            case EAST -> x;
            case WEST -> -x;
            default -> z;
        };
        return new Vec3(across * 16 + 8, point.y * 16, out * 16 + 8);
    }
}
