package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.client.DjClient;
import org.haruyq.createmediasystems.stream.DiscTransport;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The PIONEAR DJ DDJ-FLX4, a two-deck DJ controller that lies in one block, its front towards {@code FACING}. A player
 * makes it theirs (its host): from then on it stands as their own DDJ-FLX4 does and shows what their rekordbox plays
 * (see client.DjClient), on itself and on a Monitor beside it, and the speakers linked to it play their rekordbox's
 * sound, which their client sends directly (see screen.SoundShare). Anyone near works its controls on its screen;
 * what they do goes to the host's rekordbox as the controller's own MIDI.
 */
public class DdjFlx4Block extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<DdjFlx4Block> CODEC = simpleCodec(DdjFlx4Block::new);
    /** Its body in sixteenths: as wide as its block, as deep as that makes it, lying in the middle. */
    public static final float SCALE = 16 / (Control.RIGHT - Control.LEFT), DEPTH = (Control.FRONT - Control.BACK) * SCALE,
            REAR = (16 - DEPTH) / 2, HEIGHT = 1.3f;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            boolean across = facing.getAxis() == Direction.Axis.Z;
            SHAPES.put(facing, across ? box(0, 0, REAR, 16, 2, 16 - REAR) : box(REAR, 0, 0, 16 - REAR, 2, 16));
        }
    }

    public DdjFlx4Block(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<DdjFlx4Block> codec() {
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
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide && near(player, pos) != null) {
            DjClient.open(pos);
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
        return level.isClientSide || type != DjFeature.ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /**
     * Client → server: this player makes the controller at {@code pos} theirs ({@code on}), their rekordbox's sound
     * being {@code link} (see DiscUrl#screen; empty for none), or gives it up.
     */
    public record Claim(BlockPos pos, boolean on, String link) implements CustomPacketPayload {
        public static final Type<Claim> TYPE = new Type<>(CreateMediaSystems.id("ddj_flx4_claim"));
        public static final StreamCodec<ByteBuf, Claim> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Claim::pos,
                ByteBufCodecs.BOOL, Claim::on,
                ByteBufCodecs.stringUtf8(256), Claim::link,
                Claim::new);

        @Override
        public Type<Claim> type() {
            return TYPE;
        }
    }

    /** Client → server: how the controller at {@code pos} stands now (see DjState#bytes), from its host. */
    public record Live(BlockPos pos, byte[] state) implements CustomPacketPayload {
        public static final Type<Live> TYPE = new Type<>(CreateMediaSystems.id("ddj_flx4_live"));
        public static final StreamCodec<ByteBuf, Live> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Live::pos,
                ByteBufCodecs.byteArray(DjState.MAX_BYTES), Live::state,
                Live::new);

        @Override
        public Type<Live> type() {
            return TYPE;
        }
    }

    /**
     * A player worked the control in {@code slot} of the controller at {@code pos} on its screen: it came to stand at
     * {@code value}, or with {@code turned} turned that many steps. To the server from that player, and on from the
     * server to the controller's host, whose rekordbox it is for.
     */
    public record Touch(BlockPos pos, int slot, int value, boolean turned) implements CustomPacketPayload {
        public static final Type<Touch> TYPE = new Type<>(CreateMediaSystems.id("ddj_flx4_touch"));
        public static final StreamCodec<ByteBuf, Touch> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Touch::pos,
                ByteBufCodecs.VAR_INT, Touch::slot,
                ByteBufCodecs.INT, Touch::value,
                ByteBufCodecs.BOOL, Touch::turned,
                Touch::new);

        @Override
        public Type<Touch> type() {
            return TYPE;
        }
    }

    /**
     * Part {@code index} of {@code total} of the track with {@code id} (see DjState.Track#bytes) that deck {@code deck}
     * of the controller at {@code pos} has loaded. To the server from its host, and from the server to a client that
     * asked for it (see {@link Want}).
     */
    public record TrackPart(BlockPos pos, int deck, int id, int index, int total, byte[] bytes) implements CustomPacketPayload {
        /** A serverbound payload holds 32 KB at most. */
        public static final int BYTES = 24 * 1024, MAX_PARTS = 12;
        public static final Type<TrackPart> TYPE = new Type<>(CreateMediaSystems.id("ddj_flx4_track"));
        public static final StreamCodec<ByteBuf, TrackPart> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TrackPart::pos,
                ByteBufCodecs.VAR_INT, TrackPart::deck,
                ByteBufCodecs.INT, TrackPart::id,
                ByteBufCodecs.VAR_INT, TrackPart::index,
                ByteBufCodecs.VAR_INT, TrackPart::total,
                ByteBufCodecs.byteArray(BYTES), TrackPart::bytes,
                TrackPart::new);

        @Override
        public Type<TrackPart> type() {
            return TYPE;
        }

        /** {@code track} of {@code deck} in parts, each told to {@code out}. */
        public static void split(BlockPos pos, int deck, int id, byte[] track, java.util.function.Consumer<TrackPart> out) {
            int total = (track.length + BYTES - 1) / BYTES;
            for (int index = 0; index < total && total <= MAX_PARTS; index++) {
                out.accept(new TrackPart(pos, deck, id, index, total,
                        Arrays.copyOfRange(track, index * BYTES, Math.min(track.length, (index + 1) * BYTES))));
            }
        }

        public boolean valid() {
            return (deck == 0 || deck == 1) && total >= 1 && total <= MAX_PARTS && index >= 0 && index < total;
        }
    }

    /** Parts of a track as they come in, till it is whole. */
    public static final class Arriving {
        private int id;
        @Nullable
        private byte[][] parts;

        /** Takes {@code part}; the whole track once every part of it is here, else null. */
        @Nullable
        public byte[] take(TrackPart part) {
            if (parts == null || id != part.id() || parts.length != part.total()) {
                id = part.id();
                parts = new byte[part.total()][];
            }
            parts[part.index()] = part.bytes();
            ByteArrayOutputStream whole = new ByteArrayOutputStream();
            for (byte[] each : parts) {
                if (each == null) {
                    return null;
                }
                whole.writeBytes(each);
            }
            parts = null;
            return whole.toByteArray();
        }
    }

    /** Client → server: this client lacks the track with {@code id} that deck {@code deck} of the controller at {@code pos} has loaded. */
    public record Want(BlockPos pos, int deck, int id) implements CustomPacketPayload {
        public static final Type<Want> TYPE = new Type<>(CreateMediaSystems.id("ddj_flx4_want"));
        public static final StreamCodec<ByteBuf, Want> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Want::pos,
                ByteBufCodecs.VAR_INT, Want::deck,
                ByteBufCodecs.INT, Want::id,
                Want::new);

        @Override
        public Type<Want> type() {
            return TYPE;
        }
    }

    static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Claim.TYPE, Claim.CODEC, (payload, context) -> {
            if (near(context.player(), payload.pos()) instanceof Entity controller) {
                controller.claim((ServerPlayer) context.player(), payload.on(), payload.link());
            }
        });
        registrar.playToServer(Live.TYPE, Live.CODEC, (payload, context) -> {
            // From anywhere: its host may walk off while a set plays.
            if (context.player().level().isLoaded(payload.pos())
                    && context.player().level().getBlockEntity(payload.pos()) instanceof Entity controller) {
                controller.live(context.player(), payload.state());
            }
        });
        registrar.playBidirectional(Touch.TYPE, Touch.CODEC, new DirectionalPayloadHandler<>(
                (payload, context) -> DjClient.touched(payload),
                (payload, context) -> {
                    if (near(context.player(), payload.pos()) instanceof Entity controller) {
                        controller.touch(payload);
                    }
                }));
        registrar.playBidirectional(TrackPart.TYPE, TrackPart.CODEC, new DirectionalPayloadHandler<>(
                (payload, context) -> DjClient.trackPart(payload),
                (payload, context) -> {
                    if (payload.valid() && context.player().level().isLoaded(payload.pos())
                            && context.player().level().getBlockEntity(payload.pos()) instanceof Entity controller) {
                        controller.trackPart(context.player(), payload);
                    }
                }));
        registrar.playToServer(Want.TYPE, Want.CODEC, (payload, context) -> {
            if (Reach.blockEntity(context.player(), payload.pos(), 128, Entity.class) instanceof Entity controller) {
                controller.want((ServerPlayer) context.player(), payload.deck(), payload.id());
            }
        });
    }

    @Nullable
    public static Entity near(Player player, BlockPos pos) {
        return Reach.blockEntity(player, pos, Reach.HAND, Entity.class);
    }

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation, MediaSource, DiscTransport.Deck {
        private static final DiscTransport.Keys KEYS = new DiscTransport.Keys("Index", "Playing", "Paused", "Ticks");
        /** Whose controller and rekordbox it follows, from when they make it theirs until they give it up or leave. */
        @Nullable
        private UUID host;
        private String hostName = "";
        /** The link of the host's rekordbox's sound (see DiscUrl#screen); empty while there is none. */
        private String link = "";
        private byte[] live = new DjState().bytes();
        /** On the server: when (System#nanoTime) its host last told how it stands. */
        private long liveAt;
        @Nullable
        private DjState state;
        /**
         * On a client: how it stood each time it was told since, oldest first, and the one of them its listener is
         * shown (see DjClient#view): each is shown once the sound of its moment has reached their speakers.
         */
        public final ArrayDeque<DjState> coming = new ArrayDeque<>();
        @Nullable
        public DjState heard;
        /** On the server: each deck's track as its host sent it, and the parts of the one coming in. Not saved. */
        private final byte[][] tracks = new byte[2][];
        private final int[] trackIds = new int[2];
        private final Arriving[] arriving = {new Arriving(), new Arriving()};
        private final DiscTransport transport = new DiscTransport(this, false, new PlaybackClock());

        public Entity(BlockPos pos, BlockState state) {
            super(DjFeature.ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        }

        @Nullable
        public UUID host() {
            return host;
        }

        public String hostName() {
            return hostName;
        }

        public String link() {
            return link;
        }

        public Direction facing() {
            return getBlockState().getValue(FACING);
        }

        /** How it stands. Not to be changed: a change is told as {@link Live} or {@link Touch}. */
        public DjState state() {
            if (state == null) {
                state = new DjState(live);
            }
            return state;
        }

        /**
         * {@code player} makes it theirs, their rekordbox's sound being {@code next}, or gives it up. Anyone at a free
         * controller makes it theirs; from then on only they change that, from anywhere.
         */
        void claim(ServerPlayer player, boolean on, String next) {
            if (host == null ? !on || !player.canInteractWithBlock(worldPosition, 4) : !host.equals(player.getUUID())) {
                return;
            }
            if (!on) {
                release();
                return;
            }
            if (!next.isEmpty() && (MediaKind.of(next) != MediaKind.SCREEN || !MediaKind.writableBy(next, player.getUUID()))) {
                return;
            }
            boolean relinked = !next.equals(link);
            host = player.getUUID();
            hostName = player.getGameProfile().getName();
            link = next;
            if (relinked) {
                transport.insert(player);
            } else {
                changed();
            }
        }

        private void release() {
            host = null;
            hostName = "";
            link = "";
            Arrays.fill(tracks, null);
            // Its knobs stay where they were; its decks are empty and nothing is going.
            DjState left = state();
            Arrays.fill(left.track, 0);
            Arrays.fill(left.rate, 0);
            Arrays.fill(left.bpm, 0);
            left.flags = 0;
            live = left.bytes();
            transport.insert(null);
        }

        void live(Player player, byte[] told) {
            if (player.getUUID().equals(host)) {
                state = new DjState(told);
                live = state.bytes();
                liveAt = System.nanoTime();
                changed();
            }
        }

        /** With a host its rekordbox answers, and the host tells how it then stands; without one the control just moves. */
        void touch(Touch touch) {
            if (touch.slot() < 0 || touch.slot() >= Control.SLOTS) {
                return;
            }
            ServerPlayer hosting = host == null ? null : level.getServer().getPlayerList().getPlayer(host);
            if (hosting != null) {
                PacketDistributor.sendToPlayer(hosting, new Touch(worldPosition, touch.slot(), touch.value(), touch.turned()));
            } else if (!touch.turned()) {
                DjState next = state();
                next.set(touch.slot(), touch.value());
                live = next.bytes();
                changed();
            }
        }

        void trackPart(Player player, TrackPart part) {
            if (!player.getUUID().equals(host)) {
                return;
            }
            byte[] whole = arriving[part.deck()].take(part);
            if (whole != null) {
                tracks[part.deck()] = whole;
                trackIds[part.deck()] = part.id();
            }
        }

        void want(ServerPlayer player, int deck, int id) {
            if ((deck == 0 || deck == 1) && tracks[deck] != null && trackIds[deck] == id) {
                TrackPart.split(worldPosition, deck, id, tracks[deck], part -> PacketDistributor.sendToPlayer(player, part));
            }
        }

        @Override
        public List<String> playlist() {
            return link.isEmpty() ? List.of() : List.of(link);
        }

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
                sendData();
            }
        }

        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            return transport.source(worldPosition, null);
        }

        @Override
        public void tick() {
            super.tick();
            long now = level.getGameTime();
            Streams.tick(this, transport.clock(), now);
            transport.advance(now);
            if (now % 10 == 0 && host != null && level.getServer().getPlayerList().getPlayer(host) == null) {
                // They left: their rekordbox went with them.
                release();
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            if (host == null) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.ddj_flx4.free"))
                        .withStyle(ChatFormatting.GRAY));
                return true;
            }
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.host", hostName))
                    .withStyle(ChatFormatting.GRAY));
            DjState now = state();
            line(tooltip, "controller", now.has(DjState.HARDWARE));
            line(tooltip, "rekordbox", now.has(DjState.REKORDBOX));
            line(tooltip, "sound", now.has(DjState.SOUND) && !link.isEmpty());
            return true;
        }

        private static void line(List<Component> tooltip, String what, boolean on) {
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.ddj_flx4." + what))
                    .append(": ").withStyle(ChatFormatting.GRAY).append(Component.translatable(on
                            ? "create_media_systems.ddj_flx4.on" : "create_media_systems.ddj_flx4.off")
                            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)));
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            if (host != null) {
                tag.putUUID("Host", host);
            }
            tag.putString("HostName", hostName);
            tag.putString("Link", link);
            tag.putByteArray("Live", live);
            if (clientPacket) {
                // A deck that plays on untouched is told once: a client that comes later is told how long ago.
                tag.putLong("LiveAge", System.nanoTime() - liveAt);
            }
            transport.save(tag, KEYS);
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
            hostName = tag.getString("HostName");
            link = tag.getString("Link");
            byte[] told = tag.getByteArray("Live");
            if (!Arrays.equals(told, live)) {
                state = new DjState(told);
                live = state.bytes();
                state.toldAt = System.nanoTime() - Math.max(0, tag.getLong("LiveAge"));
                if (clientPacket) {
                    coming.add(state);
                    // The first is shown at once; and so are the oldest, should no one be looking for long.
                    if (heard == null || coming.size() > 64) {
                        heard = coming.poll();
                    }
                }
            }
            transport.load(tag, KEYS);
        }
    }
}
