package org.haruyq.createmediasystems.items.instrument;

import io.netty.buffer.ByteBuf;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.jetbrains.annotations.Nullable;

public final class MetalZoneBlock extends GuitarPedalBlock implements EntityBlock {
    public static final float KNOB_Z = 2.91f;
    private static final int PRESS_EVENT = 1;
    private static final Map<Direction, VoxelShape> SHAPES = createShapes();

    MetalZoneBlock(Properties properties) {
        super(AutoEffect.METAL_ZONE, properties);
    }

    public enum Cap {
        FULL(5.19f, 6.86f, .78f),
        OUTER(5.19f, 6.00f, .82f),
        INNER(6.00f, 6.76f, .51f);

        public final float base, top, radius;

        Cap(float base, float top, float radius) {
            this.base = base;
            this.top = top;
            this.radius = radius;
        }
    }

    public enum Knob {
        LEVEL(4.91f, Cap.FULL),
        LOW(6.97f, Cap.OUTER),
        HIGH(6.97f, Cap.INNER),
        MID_FREQ(9.03f, Cap.OUTER),
        MIDDLE(9.03f, Cap.INNER),
        DIST(11.09f, Cap.FULL);

        private static final Knob[] ALL = values();
        public final float x;
        public final Cap cap;

        Knob(float x, Cap cap) {
            this.x = x;
            this.cap = cap;
        }

        public Knob outer() {
            return switch (this) {
                case HIGH -> LOW;
                case MIDDLE -> MID_FREQ;
                default -> this;
            };
        }
    }

    @Override
    GuitarPatch.Setting setting(GuitarPatch.Link link, Level level, BlockState state) {
        if (level.getBlockEntity(link.location().pos()) instanceof Entity pedal) {
            return new GuitarPatch.Setting(link, enabled(state) ? 1 : 0, pedal.settings());
        }
        return new GuitarPatch.Setting(link, 0);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    protected void press(Level level, BlockPos pos, BlockState state, Player player) {
        super.press(level, pos, state, player);
        level.blockEvent(pos, this, PRESS_EVENT, 0);
        level.playSound(null, pos, enabled(state) ? SoundEvents.STONE_BUTTON_CLICK_OFF : SoundEvents.STONE_BUTTON_CLICK_ON,
                SoundSource.BLOCKS, .30f, 1.15f);
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int value) {
        return level.getBlockEntity(pos) instanceof Entity entity && entity.triggerEvent(id, value);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    private static Map<Direction, VoxelShape> createShapes() {
        var shapes = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (Direction front : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.or(orientedBox(front, 4.10, 0, .57, 11.90, 3.89, 15.25),
                    orientedBox(front, 4.12, 3.89, .61, 11.88, 5.19, 5.46),
                    orientedBox(front, 4.21, 3.89, 9.56, 11.79, 4.014, 14.63),
                    orientedBox(front, 3.75, 1.78, 5.20, 4.10, 3, 6.42),
                    orientedBox(front, 11.90, 1.78, 5.20, 12.25, 3, 6.42),
                    orientedBox(front, 7.56, .92, 15.25, 8.44, 1.80, 15.50),
                    orientedBox(front, 7.58, 2.42, .49, 8.42, 3.26, .57));
            for (Knob knob : Knob.ALL) {
                Cap cap = knob.cap;
                shape = Shapes.or(shape, orientedBox(front, knob.x - cap.radius, cap.base, KNOB_Z - cap.radius,
                        knob.x + cap.radius, cap.top, KNOB_Z + cap.radius));
            }
            shapes.put(front, shape);
        }
        return shapes;
    }

    private static VoxelShape orientedBox(Direction front, double x0, double y0, double z0, double x1, double y1, double z1) {
        Direction right = front.getCounterClockWise();
        double xa = 8 + (x0 - 8) * right.getStepX() + (z0 - 8) * front.getStepX();
        double xb = 8 + (x1 - 8) * right.getStepX() + (z1 - 8) * front.getStepX();
        double za = 8 + (x0 - 8) * right.getStepZ() + (z0 - 8) * front.getStepZ();
        double zb = 8 + (x1 - 8) * right.getStepZ() + (z1 - 8) * front.getStepZ();
        return box(Math.min(xa, xb), y0, Math.min(za, zb), Math.max(xa, xb), y1, Math.max(za, zb));
    }

    /** Model coordinates of a point in the block's world space, with the tread at the south end. */
    public static Vec3 modelPoint(BlockPos pos, Direction front, Vec3 at) {
        Direction right = front.getCounterClockWise();
        Vec3 offset = at.subtract(Vec3.atCenterOf(pos)).scale(16);
        return new Vec3(8 + offset.x * right.getStepX() + offset.z * right.getStepZ(), 8 + offset.y,
                8 + offset.x * front.getStepX() + offset.z * front.getStepZ());
    }

    @Nullable
    public static Knob knobAt(Vec3 hit) {
        // The outer ring shares its upper edge with the inner cap. Give that edge to the cap.
        Knob nearest = null;
        for (Knob knob : Knob.ALL) {
            var cap = knob.cap;
            if (hit.y >= cap.base - .01 && hit.y <= cap.top + .01
                    && Math.abs(hit.x - knob.x) <= cap.radius + .01 && Math.abs(hit.z - KNOB_Z) <= cap.radius + .01) {
                nearest = knob;
            }
        }
        return nearest;
    }

    public static final class Entity extends BlockEntity {
        private MetalZoneSettings settings = MetalZoneSettings.DEFAULT;
        private long pressedAt;

        Entity(BlockPos pos, BlockState state) {
            super(InstrumentFeature.METAL_ZONE_ENTITY.get(), pos, state);
        }

        public MetalZoneSettings settings() {
            return settings;
        }

        public void turn(Knob knob, int position) {
            if (settings.value(knob) == MetalZoneSettings.clamp(position)) return;
            settings = settings.with(knob, position);
            if (level != null && !level.isClientSide) {
                setChanged();
                var state = getBlockState();
                level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            }
        }

        public long pressedAt() {
            return pressedAt;
        }

        @Override
        public boolean triggerEvent(int id, int value) {
            if (id != PRESS_EVENT) return super.triggerEvent(id, value);
            if (level != null && level.isClientSide) pressedAt = System.nanoTime();
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            settings.save(tag);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            settings = MetalZoneSettings.load(tag);
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Sent once after an in-world knob is released. */
    public record SetKnob(BlockPos pos, int knob, int position) implements CustomPacketPayload {
        public static final Type<SetKnob> TYPE = new Type<>(CreateMediaSystems.id("metal_zone_knob"));
        public static final StreamCodec<ByteBuf, SetKnob> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetKnob::pos, ByteBufCodecs.VAR_INT, SetKnob::knob,
                ByteBufCodecs.VAR_INT, SetKnob::position, SetKnob::new);

        @Override public Type<SetKnob> type() {
            return TYPE;
        }

        public void apply(Player player) {
            if (knob < 0 || knob >= Knob.ALL.length || position < 0 || position > 100 || player.isSpectator()) return;
            var pedal = Reach.blockEntity(player, pos, Reach.HAND, Entity.class);
            if (pedal != null && player.level().mayInteract(player, pos)) pedal.turn(Knob.ALL[knob], position);
        }
    }
}
