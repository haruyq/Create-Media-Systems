package org.haruyq.createmediasystems.blocks.keyboard.a88mkii;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.LiveSource;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An 88-key MIDI keyboard, three blocks wide in one block: its body reaches a block to either side of the block it
 * stands in, its keys towards {@code FACING}. One player at a time plays it (see {@link Entity#sit}); what they play
 * is made into sound on each client near (see keyboard.client), from the keyboard itself or the speakers linked to it.
 */
public class MidiKeyboardBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<MidiKeyboardBlock> CODEC = simpleCodec(MidiKeyboardBlock::new);
    /** Its body, knobs and all, in sixteenths: the front towards +z, as it is drawn before it is turned to its facing. */
    private static final float BACK = (16 - Layout.DEPTH) / 2, TOP = 4;
    private static final Map<Direction, VoxelShape> SHAPES = new HashMap<>();

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Vec3 a = place(facing, 0, 0, 0), b = place(facing, Layout.WIDTH, TOP, Layout.DEPTH);
            SHAPES.put(facing, Shapes.create(new AABB(a, b)));
        }
    }

    public MidiKeyboardBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    /**
     * Where the point of the keyboard at ({@code x}, {@code y}, {@code z}) of its {@link Layout} (sixteenths: across
     * from the player's left, up, and from its back edge towards the player) is in its block, in blocks.
     */
    public static Vec3 place(Direction facing, float x, float y, float z) {
        double across = (x - Layout.WIDTH / 2) / 16, out = (BACK + z - 8) / 16;
        return switch (facing) {
            case NORTH -> new Vec3(0.5 - across, y / 16, 0.5 - out);
            case EAST -> new Vec3(0.5 + out, y / 16, 0.5 - across);
            case WEST -> new Vec3(0.5 - out, y / 16, 0.5 + across);
            default -> new Vec3(0.5 + across, y / 16, 0.5 + out);
        };
    }

    /** The reverse of {@link #place}: the point of the layout (x, y, z) that {@code point} of the block is. */
    public static Vec3 layout(Direction facing, Vec3 point) {
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
        return new Vec3(across * 16 + Layout.WIDTH / 2, point.y * 16, out * 16 + 8 - BACK);
    }

    /** The body of the keyboard at {@code pos}, in its block space. */
    public static AABB body(BlockPos pos, Direction facing) {
        return SHAPES.get(facing).bounds().move(pos);
    }

    @Override
    protected MapCodec<? extends MidiKeyboardBlock> codec() {
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer sitter && level.getBlockEntity(pos) instanceof Entity keyboard) {
            keyboard.sit(sitter);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != KeyboardFeature.KEYBOARD_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l);
    }

    /** A speaker linked to a keyboard: where it sounds from (in its block space), which way, which side, how loud. */
    public record Output(BlockPos pos, Vec3 origin, Optional<Direction> facing, Streams.Channel channel, float volume) {
    }

    public static class Entity extends BlockEntity implements LiveSource {
        /** How far (blocks) its player may be from it, and how far it is heard without a speaker. */
        public static final double REACH = 6, RANGE = 32;
        /**
         * Every keyboard a client has loaded, for what plays and works them there (see keyboard.client); on the
         * client's thread. Weakly: a Ponder scene's world never says its blocks are gone.
         */
        public static final Set<Entity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

        @Nullable
        private UUID performer;
        private String performerName = "";
        private byte[] panel = new Panel().bytes();
        @Nullable
        private Panel panelRead;
        /**
         * How its sound module is set up, as the last message of each kind that set it (see {@link #setsUp}): what a
         * client that comes near later plays first, to sound as everyone else's does.
         */
        private final Map<Integer, Integer> setup = new LinkedHashMap<>();
        private final Map<BlockPos, Output> outputs = new HashMap<>();
        /** The game time each output last said it was there. */
        private final Map<BlockPos, Long> heardAt = new HashMap<>();
        @Nullable
        private KeyboardPackets.Session session;
        private boolean changed;

        /** On a client, as its player's messages say: the keys and pads held down, and where the lever is. */
        public final AtomicLongArray keysDown = new AtomicLongArray(2);
        public volatile int padsDown, bend = 8192, modulation;

        public Entity(BlockPos pos, BlockState state) {
            super(KeyboardFeature.KEYBOARD_ENTITY.get(), pos, state);
        }

        public Optional<UUID> performer() {
            return Optional.ofNullable(performer);
        }

        public String performerName() {
            return performerName;
        }

        public Panel panel() {
            if (panelRead == null) {
                panelRead = new Panel(panel);
            }
            return panelRead;
        }

        public List<Output> outputs() {
            return List.copyOf(outputs.values());
        }

        /** Its setup as messages in the order to play them: controllers, then programs. */
        public int[] setup() {
            List<Integer> messages = new ArrayList<>(setup.values());
            messages.sort((a, b) -> (a & 0xF0) - (b & 0xF0));
            return messages.stream().mapToInt(Integer::intValue).toArray();
        }

        public Direction facing() {
            return getBlockState().getValue(FACING);
        }

        /** Where the middle of its keys is, in the world. */
        public Vec3 center() {
            return SableCompat.toWorld(level, worldPosition, Vec3.atLowerCornerOf(worldPosition)
                    .add(place(facing(), Layout.WIDTH / 2, Layout.HEIGHT, Layout.DEPTH / 2)));
        }

        /** {@code player} sits down to play it, unless someone else is: one player at a time, who alone works it. */
        public void sit(ServerPlayer player) {
            if (player.getUUID().equals(performer)) {
                return;
            }
            if (performer != null) {
                player.displayClientMessage(Component.translatable("create_media_systems.keyboard.in_use", performerName)
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            if (player.position().distanceTo(center()) > REACH) {
                return;
            }
            performer = player.getUUID();
            performerName = player.getGameProfile().getName();
            session = KeyboardPackets.open(player, worldPosition);
            session.listeners = listeners(player);
            sync();
        }

        /** Its player gets up: {@code player}, or whoever it is for null. */
        public void leave(@Nullable ServerPlayer player) {
            if (performer == null || player != null && !player.getUUID().equals(performer)) {
                return;
            }
            KeyboardPackets.close(performer, session);
            performer = null;
            performerName = "";
            session = null;
            sync();
        }

        /** Its player set its panel to {@code bytes}. */
        public void setPanel(ServerPlayer player, byte[] bytes) {
            if (player.getUUID().equals(performer)) {
                panel = new Panel(bytes).bytes();
                panelRead = null;
                sync();
            }
        }

        public void tick(ServerLevel level) {
            long now = level.getGameTime();
            if (session != null) {
                for (Integer message; (message = session.setup.poll()) != null;) {
                    setup.put(message & ((message & 0xF0) == Controller.PROGRAM ? 0xFF : 0xFFFF), message);
                    changed = true;
                }
            }
            if (!Streams.relayDue(level)) {
                return;
            }
            if (performer != null) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(performer);
                if (player == null || !player.isAlive() || player.level() != level || KeyboardPackets.session(performer) != session
                        || player.position().distanceTo(center()) > REACH) {
                    leave(null);
                } else {
                    session.listeners = listeners(player);
                }
            }
            // A speaker says it is there every relay: one that has not for two is gone, or linked elsewhere.
            if (heardAt.values().removeIf(at -> now - at > 2 * Streams.RELAY_TICKS)) {
                outputs.keySet().retainAll(heardAt.keySet());
                changed = true;
            }
            if (changed) {
                sync();
            }
        }

        /** Everyone but {@code player} who hears it: near it, or near a speaker linked to it. */
        private List<ServerPlayer> listeners(ServerPlayer player) {
            List<Vec3> heardFrom = new ArrayList<>(List.of(center()));
            outputs.values().forEach(output -> heardFrom.add(SableCompat.toWorld(level, output.pos(), output.origin())));
            double reach = EmitterRange.DEFAULT_RANGE + EmitterRange.DROP_MARGIN;
            return ((ServerLevel) level).players().stream().filter(other -> other != player
                    && heardFrom.stream().anyMatch(from -> from.distanceToSqr(other.position()) <= reach * reach)).toList();
        }

        @Override
        public void heardThrough(ServerLevel level, BlockPos emitter, Vec3 origin, Optional<Direction> facing,
                                 Streams.Channel channel, float volume) {
            Output output = new Output(emitter.immutable(), origin, facing, channel, volume);
            heardAt.put(output.pos(), level.getGameTime());
            if (!output.equals(outputs.put(output.pos(), output))) {
                sync();
            }
        }

        /**
         * Whether {@code message} sets how the sound module sounds from then on (a program, a channel's volume or
         * effects), rather than playing something that passes (a note, a pedal, the lever).
         */
        public static boolean setsUp(int message) {
            int controller = message >> 8 & 127;
            return (message & 0xF0) == Controller.PROGRAM || (message & 0xF0) == Controller.CONTROL
                    && (controller == 0 || controller == 7 || controller == 10 || controller >= 71 && controller <= 74
                    || controller == 91 || controller == 93);
        }

        private void sync() {
            changed = false;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level != null && level.isClientSide) {
                LOADED.add(this);
            }
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && level.isClientSide) {
                LOADED.remove(this);
            }
            if (performer != null && level != null && !level.isClientSide) {
                KeyboardPackets.close(performer, session);
                performer = null;
                session = null;
            }
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveWithoutMetadata(registries);
            if (performer != null) {
                tag.putUUID("Performer", performer);
                tag.putString("PerformerName", performerName);
            }
            ListTag heard = new ListTag();
            for (Output output : outputs.values()) {
                CompoundTag speaker = new CompoundTag();
                speaker.putLong("Pos", output.pos().asLong());
                speaker.putDouble("X", output.origin().x);
                speaker.putDouble("Y", output.origin().y);
                speaker.putDouble("Z", output.origin().z);
                output.facing().ifPresent(facing -> speaker.putInt("Facing", facing.get3DDataValue()));
                speaker.putInt("Channel", output.channel().ordinal());
                speaker.putFloat("Volume", output.volume());
                heard.add(speaker);
            }
            tag.put("Outputs", heard);
            return tag;
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putByteArray("Panel", panel);
            tag.putIntArray("Setup", setup.values().stream().mapToInt(Integer::intValue).toArray());
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            byte[] saved = tag.getByteArray("Panel");
            if (!Arrays.equals(saved, panel)) {
                panel = new Panel(saved).bytes();
                panelRead = null;
            }
            setup.clear();
            for (int message : tag.getIntArray("Setup")) {
                if (setsUp(message)) {
                    setup.put(message & ((message & 0xF0) == Controller.PROGRAM ? 0xFF : 0xFFFF), message);
                }
            }
            // Who plays it and through what is told to clients alone: a keyboard read from disk has nobody at it.
            performer = tag.hasUUID("Performer") ? tag.getUUID("Performer") : null;
            performerName = tag.getString("PerformerName");
            if (tag.contains("Outputs", Tag.TAG_LIST)) {
                outputs.clear();
                for (Tag entry : tag.getList("Outputs", Tag.TAG_COMPOUND)) {
                    CompoundTag speaker = (CompoundTag) entry;
                    Output output = new Output(BlockPos.of(speaker.getLong("Pos")),
                            new Vec3(speaker.getDouble("X"), speaker.getDouble("Y"), speaker.getDouble("Z")),
                            speaker.contains("Facing") ? Optional.of(Direction.from3DDataValue(speaker.getInt("Facing"))) : Optional.empty(),
                            Streams.Channel.byId(speaker.getInt("Channel")), speaker.getFloat("Volume"));
                    outputs.put(output.pos(), output);
                }
            }
        }
    }
}
