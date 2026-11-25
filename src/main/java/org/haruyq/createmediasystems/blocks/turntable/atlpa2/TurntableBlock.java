package org.haruyq.createmediasystems.blocks.turntable.atlpa2;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.Part;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The audio-techtech AT-LPA2: a fully manual belt-drive turntable of clear acrylic, worked by right-clicking its parts
 * (see TurntableLayout.Part) as one works the real one. Its control unit switches it on, picks 33 or 45 and starts
 * the platter; a record goes on the platter under the open dust cover; the arm is taken off its rest to the record's
 * first groove, or to any groove by clicking that spot of the record, and the lift lever lowers it. The record plays
 * while the needle is down on it and the platter turns, from the groove the needle is in, until its run-out.
 */
public class TurntableBlock extends HorizontalDirectionalBlock implements EntityBlock, SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<TurntableBlock> CODEC = simpleCodec(TurntableBlock::new);
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    public TurntableBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(OPEN, false));
    }

    @Override
    protected MapCodec<TurntableBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Drawn by its renderer; its model only gives its particles. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TurntableLayout.shape(state.getValue(FACING), state.getValue(OPEN));
    }

    @Nullable
    static Part partAt(BlockState state, BlockPos pos, Vec3 hit) {
        return TurntableLayout.partAt(state.getValue(FACING), state.getValue(OPEN), hit.subtract(Vec3.atLowerCornerOf(pos)));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (partAt(state, pos, hit.getLocation()) != Part.PLATTER || !Entity.takes(stack)
                || !(level.getBlockEntity(pos) instanceof Entity turntable) || !turntable.record.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            turntable.use(Part.PLATTER, player, stack, hit.getLocation().subtract(Vec3.atLowerCornerOf(pos)));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        Part part = partAt(state, pos, hit.getLocation());
        if (part == null || part == Part.BASE || !(level.getBlockEntity(pos) instanceof Entity turntable)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            turntable.use(part, player, ItemStack.EMPTY, hit.getLocation().subtract(Vec3.atLowerCornerOf(pos)));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity turntable) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), turntable.record);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Both sides: a client turns its platter and swings its arm between the server's words.
        return type != TurntableFeature.ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    public enum Speed {
        RPM_33(100 / 3.0), RPM_45(45);

        public final double rpm;

        Speed(double rpm) {
            this.rpm = rpm;
        }

        /**
         * How fast a record plays at this speed against how it was cut: every music disc is taken to be an LP, cut at
         * 33 1/3, so at 45 it plays 35 % fast and high.
         */
        public double rate() {
            return rpm / RPM_33.rpm;
        }
    }

    public static class Entity extends BlockEntity {
        /** Ticks the platter takes to come up to speed: the record plays only once it is there. */
        public static final int SPIN_UP_TICKS = 30;
        /** Ticks the lift lever takes to set the needle down on the record (it is damped); raising it is quicker. */
        public static final int DROP_TICKS = 30, RAISE_TICKS = 5;
        /** How often (ticks) it asks again for the length of a record whose length it doesn't know yet. */
        private static final int LENGTH_TICKS = 20;
        /** What a client does each tick for a turntable it has loaded: its animation (see client.TurntableClient). */
        public static Consumer<Entity> clientTick = turntable -> {
        };
        /** What a client does as a turntable loads and unloads there: whether it is found to be heard. */
        public static Consumer<Entity> clientLoaded = turntable -> {
        };
        public static Consumer<Entity> clientRemoved = turntable -> {
        };

        private ItemStack record = ItemStack.EMPTY;
        private boolean power;
        /** Whether START/STOP has set the platter turning. */
        private boolean motor;
        private Speed speed = Speed.RPM_33;
        private boolean onRest = true;
        /** Whether the lift lever is up, the needle off the record. */
        private boolean raised = true;
        /** When the lever last moved, and when the motor last started (game time). */
        private long leverAt = Long.MIN_VALUE / 2;
        private long motorAt = Long.MIN_VALUE / 2;
        /** Where on the record the needle is (s of its track) as of {@code anchoredAt}, and whether it plays on from there. */
        private double at;
        private long anchoredAt;
        private boolean playing;
        /** Bumped for every new playback (started, stopped, moved): its listeners start over on it. */
        private int revision;
        /** The record's length (s); not above 0 while unknown. */
        private double length = -1;
        /** Client only: animation (see client.TurntableClient). */
        public Object animation;

        public Entity(BlockPos pos, BlockState state) {
            super(TurntableFeature.ENTITY.get(), pos, state);
        }

        /** Whether a record of {@code stack} goes on the platter: a music disc, or a Custom Disc. */
        public static boolean takes(ItemStack stack) {
            DiscKind kind = MediaItem.kindOf(stack);
            return kind != null ? kind.fits(DiscKind.Slot.JUKEBOX) : stack.has(DataComponents.JUKEBOX_PLAYABLE);
        }

        public ItemStack record() {
            return record;
        }

        public boolean power() {
            return power;
        }

        /** Whether the platter is driven: switched on and started. */
        public boolean turning() {
            return power && motor;
        }

        public Speed speed() {
            return speed;
        }

        public boolean onRest() {
            return onRest;
        }

        public boolean raised() {
            return raised;
        }

        public long leverAt() {
            return leverAt;
        }

        public boolean playing() {
            return playing;
        }

        public int revision() {
            return revision;
        }

        public double length() {
            return length;
        }

        /** Where on the record the needle is at game time {@code now} (s of its track). */
        public double seconds(long now) {
            return playing ? at + Math.max(0, now - anchoredAt) / (double) PlaybackClock.TICKS_PER_SECOND * rate() : at;
        }

        /**
         * How fast its record plays (see Speed#rate). A Custom Disc's link always plays at its own speed: the native
         * player decodes it in real time, and cannot feed it faster.
         */
        public double rate() {
            return MediaItem.kindOf(record) != null ? 1 : speed.rate();
        }

        /** How far through the record's side the needle is (0 at its first groove, 1 at its run-out); 0 if unknown. */
        public double fraction(long now) {
            return length > 0 ? Math.clamp(seconds(now) / length, 0, 1) : 0;
        }

        /** Whether the needle is down in the record's grooves and they turn under it at speed, before its run-out. */
        private boolean shouldPlay(long now) {
            return !record.isEmpty() && turning() && now - motorAt >= SPIN_UP_TICKS && !onRest && !raised
                    && now - leverAt >= DROP_TICKS && !(length > 0 && seconds(now) >= length);
        }

        /** Holds where the needle is now, and starts it playing on from there or not. */
        private void anchor(long now, boolean play) {
            at = length > 0 ? Math.min(seconds(now), length) : seconds(now);
            anchoredAt = now;
            playing = play;
            revision++;
        }

        void tick() {
            if (level.isClientSide) {
                clientTick.accept(this);
                return;
            }
            long now = level.getGameTime();
            if (length <= 0 && !record.isEmpty() && now % LENGTH_TICKS == 0) {
                double known = MediaItem.lengthSeconds(record, level.registryAccess());
                if (MediaItem.plausibleLength(known)) {
                    length = known;
                    changed();
                }
            }
            if (shouldPlay(now) != playing) {
                anchor(now, !playing);
                changed();
            } else if (playing && now % 100 == 0) {
                setChanged();
            }
        }

        /**
         * {@code player} right-clicked {@code part}, holding {@code held} (empty for an empty hand or anything that is
         * no record), at {@code inBlock} (0 to 1 in the block).
         */
        public void use(Part part, Player player, ItemStack held, Vec3 inBlock) {
            long now = level.getGameTime();
            switch (part) {
                case COVER, COVER_OPEN -> {
                    BlockState state = getBlockState();
                    boolean open = !state.getValue(OPEN);
                    level.setBlock(worldPosition, state.setValue(OPEN, open), Block.UPDATE_ALL);
                    level.playSound(null, worldPosition, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE,
                            SoundSource.BLOCKS, .25f, 1.7f);
                    return;
                }
                case POWER -> {
                    // Switched off, the platter stops, and stays stopped once switched on again.
                    power = !power;
                    motor = false;
                    click(power ? 1.1f : .9f);
                }
                case SPEED_33, SPEED_45 -> {
                    if (!power) {
                        return;
                    }
                    Speed picked = part == Part.SPEED_45 ? Speed.RPM_45 : Speed.RPM_33;
                    if (picked != speed) {
                        // Where it was is held at the old speed; it plays on from there at the new one.
                        anchor(now, playing);
                        speed = picked;
                    }
                    click(1.2f);
                }
                case START_STOP -> {
                    if (!power) {
                        return;
                    }
                    motor = !motor;
                    motorAt = now;
                    click(1);
                }
                case LIFT -> {
                    anchor(now, playing);
                    raised = !raised;
                    leverAt = now;
                    click(1.5f);
                }
                case ARM -> {
                    // Off its rest it goes over the first groove; back, onto its rest. Either way the lever is up.
                    lever(now);
                    onRest = !onRest;
                    at = 0;
                    level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, .4f, 1.6f);
                }
                case PLATTER -> {
                    if (!onRest) {
                        // The arm is lifted and set down over the groove at the spot clicked.
                        lever(now);
                        Vec3 spot = TurntableLayout.toTurntable(getBlockState().getValue(FACING), inBlock);
                        double fraction = TurntableLayout.fractionAt(TurntableLayout.radius(spot.x, spot.z));
                        at = length > 0 ? fraction * length : 0;
                        level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, .4f, 1.8f);
                    } else if (record.isEmpty() && takes(held)) {
                        record = held.split(1);
                        at = 0;
                        length = -1;
                        level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .6f, 1.2f);
                    } else if (!record.isEmpty() && held.isEmpty()) {
                        player.getInventory().placeItemBackInInventory(record);
                        record = ItemStack.EMPTY;
                        at = 0;
                        length = -1;
                        level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, .6f, 1.2f);
                    } else {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
            if (shouldPlay(now) != playing) {
                anchor(now, !playing);
            }
            changed();
        }

        /** Raises the lever if it is down, holding the needle where it is. */
        private void lever(long now) {
            anchor(now, false);
            if (!raised) {
                raised = true;
                leverAt = now;
            }
        }

        private void click(float pitch) {
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, .3f, pitch);
        }

        private void changed() {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }

        /** Sets it up as a scene shows it. */
        public void arrange(ItemStack record, boolean power, boolean motor, Speed speed, boolean onRest, boolean raised,
                            double at, double length) {
            this.record = record;
            this.power = power;
            this.motor = motor;
            this.speed = speed;
            this.onRest = onRest;
            this.raised = raised;
            this.at = at;
            this.length = length;
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level != null && level.isClientSide) {
                clientLoaded.accept(this);
            }
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && level.isClientSide) {
                clientRemoved.accept(this);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (!record.isEmpty()) {
                tag.put("Record", record.save(registries));
            }
            tag.putBoolean("Power", power);
            tag.putBoolean("Motor", motor);
            tag.putInt("Speed", speed.ordinal());
            tag.putBoolean("OnRest", onRest);
            tag.putBoolean("Raised", raised);
            tag.putLong("LeverAt", leverAt);
            tag.putLong("MotorAt", motorAt);
            tag.putDouble("At", at);
            tag.putLong("AnchoredAt", anchoredAt);
            tag.putBoolean("Playing", playing);
            tag.putInt("Revision", revision);
            tag.putDouble("Length", length);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            record = ItemStack.parseOptional(registries, tag.getCompound("Record"));
            power = tag.getBoolean("Power");
            motor = tag.getBoolean("Motor");
            speed = Speed.values()[Mth.clamp(tag.getInt("Speed"), 0, Speed.values().length - 1)];
            onRest = !tag.contains("OnRest") || tag.getBoolean("OnRest");
            raised = !tag.contains("Raised") || tag.getBoolean("Raised");
            leverAt = tag.contains("LeverAt") ? tag.getLong("LeverAt") : leverAt;
            motorAt = tag.contains("MotorAt") ? tag.getLong("MotorAt") : motorAt;
            at = tag.getDouble("At");
            anchoredAt = tag.getLong("AnchoredAt");
            playing = tag.getBoolean("Playing");
            revision = tag.getInt("Revision");
            length = tag.contains("Length") ? tag.getDouble("Length") : -1;
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
}
