package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;
import org.haruyq.createmediasystems.blocks.radio.RadioFeature;
import org.haruyq.createmediasystems.blocks.radio.RadioStations;
import org.haruyq.createmediasystems.blocks.radio.RadioTransmitterBlock;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.DiscTransport;
import org.haruyq.createmediasystems.stream.Emitter;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.PlaylistView;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The Sunny ZS-RS81BT: a CD radio in one block, worked by pointing at its keys (see BoomboxKey). On FM it plays, to
 * everyone near it, the radio station of its frequency that reaches it (see RadioStations); on CD, the disc under the
 * lid on its top.
 */
public class BoomboxBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable, SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<BoomboxBlock> CODEC = simpleCodec(BoomboxBlock::new);
    /** Its body, without the handle, so that the lid under the handle can be pointed at. */
    private static final VoxelShape ALONG_X = Block.box(0, 0, BoomboxKey.BACK, 16, BoomboxKey.TOP, BoomboxKey.FRONT);
    private static final VoxelShape ALONG_Z = Block.box(BoomboxKey.BACK, 0, 0, BoomboxKey.FRONT, BoomboxKey.TOP, 16);

    public BoomboxBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<BoomboxBlock> codec() {
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

    /** Drawn by its renderer; its model only gives its particles. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ALONG_X : ALONG_Z;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity set) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), set.disc);
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
        // Both sides: a client counts its display on, and moves its lid, between the server's words.
        return type != BoomboxFeature.ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    public enum Band {
        CD, FM;

        static Band byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    public static class Entity extends BlockEntity implements Emitter, MediaSource, DiscTransport.Deck, PlaylistView {
        public static final int MAX_VOLUME = 30;
        /** How loud its hiss is with no signal, against a station at full volume. Tuning knob. */
        private static final float HISS = .5f;
        private static final DiscTransport.Keys KEYS = new DiscTransport.Keys("Track", "Playing", "Paused", "Ticks");
        /** What a client does each tick for a set it has loaded: its hiss (see client.BoomboxClient). */
        public static Consumer<Entity> clientTick = set -> {
        };

        private boolean power;
        private Band band = Band.FM;
        /** Tenths of a MHz. */
        private int frequency = RadioFeature.DEFAULT_FREQUENCY;
        /** 0 to MAX_VOLUME. */
        private int volume = 12;
        private ItemStack disc = ItemStack.EMPTY;
        private boolean lidOpen;
        private final DiscTransport transport = new DiscTransport(this, true, PlaybackClock.unattended());
        /** How strong (0..1) it receives the station of its frequency, as of its last relay on FM; 0 for none. */
        private float signal;
        /** Whether its disc's track still waits for its listeners to load it: its counter stands until they have. */
        private boolean loading;
        /** Client only: how far its lid is open (0 to 1) this tick and the last. */
        public float lid;
        public float lastLid;
        /** Client only: the sound of its hiss, while that plays (see client.BoomboxClient). */
        @Nullable
        public Object noise;

        public Entity(BlockPos pos, BlockState state) {
            super(BoomboxFeature.ENTITY.get(), pos, state);
        }

        public boolean power() {
            return power;
        }

        public Band band() {
            return band;
        }

        public int frequency() {
            return frequency;
        }

        /** Its volume as its display shows it, 0 to MAX_VOLUME. */
        public int volumeStep() {
            return volume;
        }

        public ItemStack disc() {
            return disc;
        }

        public boolean lidOpen() {
            return lidOpen;
        }

        public DiscTransport transport() {
            return transport;
        }

        public float signal() {
            return signal;
        }

        public boolean loading() {
            return loading;
        }

        /** How loud it hisses, 0 to 1: on FM, as much as its signal is short of full. */
        public float hiss() {
            return power && band == Band.FM ? HISS * gain() * (1 - signal) : 0;
        }

        /** Whether its lid takes {@code stack}: a disc that plays sound alone. */
        public static boolean takes(ItemStack stack) {
            DiscKind kind = MediaItem.kindOf(stack);
            return kind != null && kind.fits(DiscKind.Slot.DISC_TRAY) && !kind.hasPicture();
        }

        /** Sets it up as a scene or an item shows it. */
        public void arrange(boolean power, Band band, boolean lidOpen, ItemStack disc) {
            this.power = power;
            this.band = band;
            this.lidOpen = lidOpen;
            this.disc = disc;
            signal = 1;
            lid = lastLid = lidOpen ? 1 : 0;
        }

        @Override
        public List<String> playlist() {
            return MediaItem.linksOf(disc);
        }

        @Override
        public int index() {
            return transport.index();
        }

        /** Every listener loads the track from where it now is, and they start together (see Streams#loading). */
        @Override
        public void started(@Nullable Player by, boolean sought) {
            if (level != null) {
                Streams.loading(this, by, transport.clock(), level.getGameTime());
            }
        }

        @Override
        public void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        /** Its disc, on CD with the lid shut; a station is no track of its own (see relay). */
        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            return power && band == Band.CD && !lidOpen ? transport.source(worldPosition, null) : null;
        }

        @Override
        public float volume() {
            return volume / (float) MAX_VOLUME;
        }

        /** Its volume turned up slowly at first, as a real one; silent while switched off. */
        @Override
        public float gain() {
            return power ? (float) Math.pow(volume(), 1.5) : 0;
        }

        /** On FM it plays a station's source from afar: that source's displays keep their sound beside it. */
        @Override
        public boolean apart() {
            return band == Band.FM;
        }

        void tick() {
            long now = level.getGameTime();
            if (level.isClientSide) {
                transport.clock().tick(now, !loading);
                lastLid = lid;
                lid = Mth.clamp(lid + (lidOpen ? .15f : -.15f), 0, 1);
                clientTick.accept(this);
                return;
            }
            Streams.tick(this, transport.clock(), now);
            transport.advance(now);
            boolean waits = nowPlaying() != null && !Streams.loaded(this);
            if (waits != loading) {
                loading = waits;
                changed();
            }
            if (transport.clock().running()) {
                setChanged();
                // Its clients count on by themselves; now and then they are put right.
                if (now % 100 == 0) {
                    changed();
                }
            }
            if (Streams.relayDue(level)) {
                relay((ServerLevel) level);
            }
        }

        /**
         * On CD it is its own speaker. On FM it is a speaker of the source of the station it receives, as loud as the
         * station sends and its signal here allows; with no station, of nothing.
         */
        @Override
        public void relay(ServerLevel level) {
            Direction facing = getBlockState().getValue(FACING);
            Vec3 mouth = Vec3.atLowerCornerOf(worldPosition).add(HifiLayout.toBlock(facing, 8, BoomboxKey.TOP / 2, BoomboxKey.FRONT));
            Optional<BlockPos> link = Optional.empty();
            float heard = 0;
            float received = 0;
            if (power && band == Band.CD) {
                link = Optional.of(worldPosition);
                heard = 1;
            } else if (power) {
                Vec3 here = SableCompat.toWorld(level, worldPosition, worldPosition.getCenter());
                double range = EmitterRange.DEFAULT_RANGE + EmitterRange.DROP_MARGIN;
                RadioStations.Reception station = RadioStations.receive(level, frequency, here,
                        level.players().stream().anyMatch(player -> player.position().distanceToSqr(here) <= range * range));
                if (station != null) {
                    received = station.signal();
                    link = Optional.ofNullable(station.station().source());
                    BlockPos transmitter = station.transmitter();
                    heard = gain() * (float) Math.sqrt(received) * (level.isLoaded(transmitter)
                            && level.getBlockEntity(transmitter) instanceof RadioTransmitterBlock.Transmitter sender ? sender.volume() : 1);
                }
            }
            if (Math.abs(received - signal) > .01f) {
                signal = received;
                changed();
            }
            Streams.relay(level, this, link, mouth, Optional.of(facing), Streams.Channel.MIX, heard);
        }

        /** {@code player} pressed {@code key}. Its lid works by hand; the rest only while it is switched on. */
        public void press(ServerPlayer player, BoomboxKey key) {
            long now = level.getGameTime();
            switch (key) {
                case POWER -> {
                    power = !power;
                    if (!power) {
                        transport.stop();
                    }
                    click(power ? .9f : .7f);
                }
                case LID, OPEN -> lid(player, key, player.getMainHandItem());
                default -> {
                    if (!power) {
                        return;
                    }
                    switch (key) {
                        case VOLUME_DOWN, VOLUME_UP -> {
                            volume = Mth.clamp(volume + (key == BoomboxKey.VOLUME_UP ? 1 : -1), 0, MAX_VOLUME);
                            player.displayClientMessage(Component.translatable("create_media_systems.boombox.volume", volume), true);
                        }
                        case CD -> band = Band.CD;
                        case FM -> {
                            band = Band.FM;
                            transport.stop();
                        }
                        case PLAY -> {
                            if (band == Band.CD && lidOpen) {
                                close(player);
                            } else if (band == Band.CD) {
                                transport.control(Remotable.Action.PLAY_PAUSE, 0, player, now);
                            }
                        }
                        case STOP -> transport.stop();
                        case PREVIOUS, NEXT -> {
                            if (band == Band.CD) {
                                transport.control(key == BoomboxKey.NEXT ? Remotable.Action.NEXT : Remotable.Action.PREVIOUS,
                                        0, player, now);
                            } else {
                                // Round the ends of the band.
                                int next = frequency + (key == BoomboxKey.NEXT ? 1 : -1);
                                frequency = next > RadioFeature.MAX_FREQUENCY ? RadioFeature.MIN_FREQUENCY
                                        : next < RadioFeature.MIN_FREQUENCY ? RadioFeature.MAX_FREQUENCY : next;
                                player.displayClientMessage(Component.translatable("create_media_systems.radio.frequency_set",
                                        RadioFeature.mhz(frequency)), true);
                            }
                        }
                        default -> {
                        }
                    }
                    click(1);
                }
            }
            changed();
            // What its own speaker and those linked to it play follows at once, not at the next relay.
            Streams.changed((ServerLevel) level, worldPosition);
            relay((ServerLevel) level);
        }

        /**
         * Shut, the lid opens, and the disc stops. Open, a disc {@code player} holds goes in, or an empty hand takes
         * the one there out; anything else shuts it.
         */
        private void lid(ServerPlayer player, BoomboxKey key, ItemStack held) {
            if (!lidOpen) {
                lidOpen = true;
                transport.stop();
                click(1.2f);
            } else if (key == BoomboxKey.LID && disc.isEmpty() && takes(held)) {
                disc = held.split(1);
                level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .6f, 1.4f);
            } else if (key == BoomboxKey.LID && !disc.isEmpty() && held.isEmpty()) {
                player.getInventory().placeItemBackInInventory(disc);
                disc = ItemStack.EMPTY;
                level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, .6f, 1.4f);
            } else {
                close(player);
            }
        }

        /** The lid shuts; on CD the disc under it plays from its first track. */
        private void close(@Nullable Player by) {
            lidOpen = false;
            click(1.1f);
            if (power && band == Band.CD) {
                transport.insert(by);
            }
        }

        private void click(float pitch) {
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, .4f, pitch);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && !level.isClientSide) {
                Streams.stop(this);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putBoolean("Power", power);
            tag.putInt("Band", band.ordinal());
            tag.putInt("Frequency", frequency);
            tag.putInt("Volume", volume);
            if (!disc.isEmpty()) {
                tag.put("Disc", disc.save(registries));
            }
            tag.putBoolean("LidOpen", lidOpen);
            transport.save(tag, KEYS);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            power = tag.getBoolean("Power");
            band = tag.contains("Band") ? Band.byId(tag.getInt("Band")) : Band.FM;
            frequency = tag.contains("Frequency") ? RadioFeature.clampFrequency(tag.getInt("Frequency")) : RadioFeature.DEFAULT_FREQUENCY;
            volume = tag.contains("Volume") ? Mth.clamp(tag.getInt("Volume"), 0, MAX_VOLUME) : volume;
            disc = ItemStack.parseOptional(registries, tag.getCompound("Disc"));
            lidOpen = tag.getBoolean("LidOpen");
            transport.load(tag, KEYS);
            loading = tag.getBoolean("Loading");
            signal = tag.getFloat("Signal");
        }

        /** Every client draws all of it; whether it loads and what it receives are for them alone, not kept. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveWithoutMetadata(registries);
            tag.putBoolean("Loading", loading);
            tag.putFloat("Signal", signal);
            return tag;
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Client → server: the key {@code key} of the set at {@code pos} was pressed. */
    public record Press(BlockPos pos, int key) implements CustomPacketPayload {
        public static final Type<Press> TYPE = new Type<>(CreateMediaSystems.id("boombox_press"));
        public static final StreamCodec<ByteBuf, Press> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Press::pos, ByteBufCodecs.VAR_INT, Press::key, Press::new);

        @Override
        public Type<Press> type() {
            return TYPE;
        }

        static void handle(Press payload, IPayloadContext context) {
            Entity set = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND, Entity.class);
            if (set != null) {
                set.press((ServerPlayer) context.player(), BoomboxKey.byId(payload.key()));
            }
        }
    }
}
