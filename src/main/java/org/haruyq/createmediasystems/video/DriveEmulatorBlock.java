package org.haruyq.createmediasystems.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.disc.DriveClient;
import org.haruyq.createmediasystems.disc.DriveScreen;
import org.haruyq.createmediasystems.stream.DiscDrive;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.MediaSource;
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
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The disc drive of a real computer, beside what plays its disc: the Blu-ray or DVD in the drive of whoever uses it
 * is a Blu-ray player's disc, and the audio CD a disc reader's, while those hold no disc of their own. Their client
 * reads the disc and shares it directly (see DriveClient).
 */
public class DriveEmulatorBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable,
        SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<DriveEmulatorBlock> CODEC = simpleCodec(DriveEmulatorBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public DriveEmulatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    protected MapCodec<DriveEmulatorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            DriveScreen.open(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity emulator) {
            emulator.release();
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
        return type != VideoFeature.DRIVE_EMULATOR_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    @Nullable
    public static Entity beside(@Nullable BlockGetter level, BlockPos pos) {
        Entity found = null;
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(pos.relative(side)) instanceof Entity emulator
                        && (found == null || emulator.drive != Drive.OPEN)) {
                    found = emulator;
                }
            }
        }
        return found;
    }

    /** What the real drive does, as everyone sees it: open with no disc, or closed on one. */
    public enum Drive {
        OPEN(ChatFormatting.GRAY), READING(ChatFormatting.YELLOW),
        /** Reading waits for FFmpeg to be downloaded. */
        DOWNLOADING(ChatFormatting.YELLOW),
        /** The disc is read and shared: what it is beside plays it. */
        READY(ChatFormatting.GREEN), FAILED(ChatFormatting.RED),
        /** The real drive has closed on a disc and is still making it out: its kind is not known yet. Last, as saves hold the others' numbers. */
        LOADING(ChatFormatting.YELLOW);

        private final ChatFormatting color;

        Drive(ChatFormatting color) {
            this.color = color;
        }

        public static Drive byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /**
     * The kinds of disc a drive takes, as the media items they are (see DiscKind), and the rate (kbps) a viewer's
     * connection has to keep up for each to play well (see Viewer#stability).
     */
    public enum Disc {
        BLU_RAY("Blu-ray", DiscKind.BLU_RAY, 4000), DVD("DVD", DiscKind.DVD, 2000), CD("CD", DiscKind.CD, 500);

        public final String name;
        public final DiscKind kind;
        final int kbps;

        Disc(String name, DiscKind kind, int kbps) {
            this.name = name;
            this.kind = kind;
            this.kbps = kbps;
        }

        /** The drive's disc of {@code kind}; null for a kind no drive takes. */
        @Nullable
        public static Disc of(DiscKind kind) {
            for (Disc disc : values()) {
                if (disc.kind == kind) {
                    return disc;
                }
            }
            return null;
        }

        public static Disc byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    public enum Stability {
        CONNECTING(ChatFormatting.GRAY), POOR(ChatFormatting.RED), UNSTABLE(ChatFormatting.YELLOW), STABLE(ChatFormatting.GREEN);

        private final ChatFormatting color;

        Stability(ChatFormatting color) {
            this.color = color;
        }
    }

    /**
     * A viewer of the disc as its host's client sees their direct connection: whether its rate is measured yet, its
     * round trip (ms), and the rate (kbps) the host sends them at, which falls when they fall behind.
     */
    public record Viewer(UUID id, boolean measured, int rttMillis, int kbps) {
        public static final StreamCodec<ByteBuf, Viewer> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Viewer::id,
                ByteBufCodecs.BOOL, Viewer::measured,
                ByteBufCodecs.VAR_INT, Viewer::rttMillis,
                ByteBufCodecs.VAR_INT, Viewer::kbps,
                Viewer::new);
        /** Round trips (ms) from which a connection is unstable, and poor. Tuning knobs. */
        static final int SLOW_RTT = 150, POOR_RTT = 300;

        public Stability stability(Disc disc) {
            return !measured ? Stability.CONNECTING
                    : kbps < disc.kbps / 2 || rttMillis >= POOR_RTT ? Stability.POOR
                    : kbps < disc.kbps || rttMillis >= SLOW_RTT ? Stability.UNSTABLE : Stability.STABLE;
        }
    }

    /**
     * Client → server: this player's real drive, for the emulator at {@code pos}, is now {@code drive}, with a disc of
     * kind {@code disc} (-1 while it is not known: open, or still loading). {@code text} is the disc's label as it is
     * read, its link once it is shared (see DiscUrl), or why it failed.
     */
    public record Update(BlockPos pos, int drive, String text, int disc) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("drive_update"));
        public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Update::pos,
                ByteBufCodecs.VAR_INT, Update::drive,
                ByteBufCodecs.stringUtf8(256), Update::text,
                ByteBufCodecs.VAR_INT, Update::disc,
                Update::new);

        public Update(BlockPos pos, Drive drive, String text, @Nullable Disc disc) {
            this(pos, drive.ordinal(), text.length() > 256 ? text.substring(0, 256) : text, disc == null ? -1 : disc.ordinal());
        }

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    /** Client → server: the viewers of the disc this player shares through the emulator at {@code pos}, once a second. */
    public record Viewers(BlockPos pos, List<Viewer> viewers) implements CustomPacketPayload {
        public static final Type<Viewers> TYPE = new Type<>(CreateMediaSystems.id("drive_viewers"));
        public static final StreamCodec<ByteBuf, Viewers> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Viewers::pos,
                Viewer.CODEC.apply(ByteBufCodecs.list(64)), Viewers::viewers,
                Viewers::new);

        @Override
        public Type<Viewers> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Update.TYPE, Update.CODEC, (payload, context) -> {
            if (emulator(context.player(), payload.pos()) instanceof Entity emulator) {
                emulator.update((ServerPlayer) context.player(), Drive.byId(payload.drive()), payload.text(),
                        payload.disc() < 0 ? null : Disc.byId(payload.disc()));
            }
        });
        registrar.playToServer(Viewers.TYPE, Viewers.CODEC, (payload, context) -> {
            if (emulator(context.player(), payload.pos()) instanceof Entity emulator) {
                emulator.viewers((ServerPlayer) context.player(), payload.viewers());
            }
        });
    }

    @Nullable
    private static BlockEntity emulator(Player player, BlockPos pos) {
        return player.level().isLoaded(pos) ? player.level().getBlockEntity(pos) : null;
    }

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation, DiscDrive {
        private Drive drive = Drive.OPEN;
        /** Whose real drive it is, from when they put a disc in until it comes out or they leave. */
        @Nullable
        private UUID host;
        private String hostName = "";
        private Disc disc = Disc.BLU_RAY;
        /** The disc's volume label; empty if it has none. */
        private String label = "";
        /** The disc as shared (see DiscUrl), once it is ready. */
        private String link = "";
        private String error = "";
        private boolean spinning;
        /** Whether what it is beside has its disc but still loads it: shared, yet nothing of it is seen or heard so far. */
        private boolean starting;
        /** The disc's viewers by name, as its host's client last told. Not saved. */
        private List<Viewer> viewers = List.of();
        private List<String> viewerNames = List.of();

        public Entity(BlockPos pos, BlockState state) {
            super(VideoFeature.DRIVE_EMULATOR_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        }

        public Drive drive() {
            return drive;
        }

        @Nullable
        public UUID host() {
            return host;
        }

        public String hostName() {
            return hostName;
        }

        public Disc disc() {
            return disc;
        }

        @Override
        @Nullable
        public DiscKind ready() {
            return drive == Drive.READY ? disc.kind : null;
        }

        @Override
        public String link() {
            return link;
        }

        public String error() {
            return error;
        }

        public boolean spinning() {
            return spinning;
        }

        public String discName() {
            return label.isEmpty() ? disc.name : label + " (" + disc.name + ")";
        }

        public Component status() {
            Drive shown = drive == Drive.READY && starting ? Drive.LOADING : drive;
            return Component.translatable("create_media_systems.drive." + shown.name().toLowerCase(Locale.ROOT))
                    .withStyle(shown.color);
        }

        public boolean feeds(Disc kind) {
            for (Direction side : Direction.values()) {
                if (level != null && level.getBlockEntity(worldPosition.relative(side)) instanceof DiscDrive.Fed fed
                        && fed.plays(kind.kind)) {
                    return true;
                }
            }
            return false;
        }

        public boolean feedsAny() {
            for (Disc kind : Disc.values()) {
                if (feeds(kind)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code player}'s real drive is now {@code next} (see Update), with a disc of {@code kind} once that is known.
         * Anyone at a free emulator makes it theirs by putting a disc in; from then on only they change it, from
         * anywhere, and only to a disc they share.
         */
        public void update(ServerPlayer player, Drive next, String text, @Nullable Disc kind) {
            if (host == null ? next == Drive.OPEN || !player.canInteractWithBlock(worldPosition, 4)
                    : !host.equals(player.getUUID())) {
                return;
            }
            if (next == Drive.READY) {
                if (MediaKind.of(text) != MediaKind.DISC || !MediaKind.writableBy(text, player.getUUID())) {
                    return;
                }
            }
            boolean wasOpen = drive == Drive.OPEN;
            switch (next) {
                case OPEN, LOADING -> label = "";
                case READING, DOWNLOADING -> label = text;
                case FAILED -> error = text;
                case READY -> {
                }
            }
            if (next != Drive.OPEN && kind != null) {
                disc = kind;
            }
            link = next == Drive.READY ? text : "";
            drive = next;
            host = next == Drive.OPEN ? null : player.getUUID();
            hostName = player.getGameProfile().getName();
            if (wasOpen || next == Drive.OPEN) {
                sound(next == Drive.OPEN ? VideoFeature.DRIVE_TRAY_OPEN.get() : VideoFeature.DRIVE_TRAY.get());
            }
            if (next == Drive.READING) {
                sound(VideoFeature.DRIVE_SEEK.get());
            }
            changed(player);
        }

        void viewers(ServerPlayer player, List<Viewer> told) {
            if (!player.getUUID().equals(host) || drive != Drive.READY || told.equals(viewers)) {
                return;
            }
            List<Viewer> known = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Viewer viewer : told) {
                ServerPlayer watching = player.server.getPlayerList().getPlayer(viewer.id());
                if (watching != null) {
                    known.add(viewer);
                    names.add(watching.getGameProfile().getName());
                }
            }
            viewers = known;
            viewerNames = names;
            sendData();
        }

        void release() {
            if (drive != Drive.OPEN) {
                drive = Drive.OPEN;
                host = null;
                label = "";
                link = "";
                changed(null);
            }
        }

        private void sound(SoundEvent sound) {
            level.playSound(null, worldPosition, sound, SoundSource.BLOCKS, 0.5f, 1);
        }

        private void changed(@Nullable Player by) {
            if (drive != Drive.READY) {
                viewers = List.of();
                viewerNames = List.of();
            }
            setChanged();
            if (level == null || level.isClientSide) {
                return;
            }
            sendData();
            if (getBlockState().getValue(LIT) != (drive != Drive.OPEN) && level.getBlockState(worldPosition).is(getBlockState().getBlock())) {
                level.setBlockAndUpdate(worldPosition, getBlockState().setValue(LIT, drive != Drive.OPEN));
            }
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(worldPosition.relative(side)) instanceof DiscDrive.Fed fed) {
                    fed.driveChanged(by);
                }
            }
            turn();
        }

        @Override
        public void tick() {
            super.tick();
            if (level == null) {
                return;
            }
            if (level.isClientSide) {
                DriveClient.tick(this);
                return;
            }
            if (level.getGameTime() % 10 != 0) {
                return;
            }
            if (host != null && level.getServer().getPlayerList().getPlayer(host) == null) {
                // They left with their disc.
                release();
            }
            turn();
        }

        private void turn() {
            BlockEntity player = drive == Drive.READY ? player() : null;
            boolean turns = drive == Drive.LOADING || drive == Drive.READING || drive == Drive.DOWNLOADING || player != null;
            boolean starts = player != null && !Streams.loaded(player);
            if (turns != spinning || starts != starting) {
                spinning = turns;
                starting = starts;
                sendData();
            }
        }

        @Nullable
        private BlockEntity player() {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(worldPosition.relative(side)) instanceof MediaSource source) {
                    Streams.Source playing = source.nowPlaying();
                    if (playing != null && playing.url().equals(link)) {
                        return (BlockEntity) source;
                    }
                }
            }
            return null;
        }

        public void describe(List<Component> tooltip) {
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.drive", status()))
                    .withStyle(ChatFormatting.GRAY));
            if (drive != Drive.OPEN && drive != Drive.LOADING) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.disc", discName()))
                        .withStyle(ChatFormatting.GOLD));
            }
            if (drive != Drive.OPEN) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.host", hostName))
                        .withStyle(ChatFormatting.GRAY));
            }
            if (drive == Drive.FAILED) {
                tooltip.add(Component.literal("     " + error).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            if (drive != Drive.READY) {
                tooltip.add(Component.literal("     ").append(status()));
                return true;
            }
            tooltip.add(Component.literal("     ").append(Component.translatable(viewers.isEmpty()
                    ? "create_media_systems.drive.no_viewers" : "create_media_systems.drive.viewers")).withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < viewers.size(); i++) {
                Viewer viewer = viewers.get(i);
                Stability stability = viewer.stability(disc);
                tooltip.add(Component.literal("     " + viewerNames.get(i) + "  ").withStyle(ChatFormatting.WHITE)
                        .append(Component.translatable("create_media_systems.drive.stability."
                                + stability.name().toLowerCase(Locale.ROOT)).withStyle(stability.color))
                        .append(Component.literal(!viewer.measured() ? "" : String.format(Locale.ROOT, "  %d ms  %.1f Mbps",
                                viewer.rttMillis(), viewer.kbps() / 1000.0)).withStyle(ChatFormatting.GRAY)));
            }
            return true;
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            tag.putInt("Drive", drive.ordinal());
            if (host != null) {
                tag.putUUID("Host", host);
            }
            tag.putString("HostName", hostName);
            tag.putInt("Disc", disc.ordinal());
            tag.putString("Label", label);
            tag.putString("Link", link);
            tag.putString("Error", error);
            if (clientPacket) {
                tag.putBoolean("Spinning", spinning);
                tag.putBoolean("Starting", starting);
                ListTag list = new ListTag();
                for (int i = 0; i < viewers.size(); i++) {
                    CompoundTag viewer = new CompoundTag();
                    viewer.putUUID("Id", viewers.get(i).id());
                    viewer.putString("Name", viewerNames.get(i));
                    viewer.putBoolean("Measured", viewers.get(i).measured());
                    viewer.putInt("Rtt", viewers.get(i).rttMillis());
                    viewer.putInt("Kbps", viewers.get(i).kbps());
                    list.add(viewer);
                }
                tag.put("Viewers", list);
            }
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            drive = Drive.byId(tag.getInt("Drive"));
            host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
            hostName = tag.getString("HostName");
            disc = Disc.byId(tag.getInt("Disc"));
            label = tag.getString("Label");
            link = tag.getString("Link");
            error = tag.getString("Error");
            if (clientPacket) {
                spinning = tag.getBoolean("Spinning");
                starting = tag.getBoolean("Starting");
                List<Viewer> known = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (Tag each : tag.getList("Viewers", Tag.TAG_COMPOUND)) {
                    CompoundTag viewer = (CompoundTag) each;
                    known.add(new Viewer(viewer.getUUID("Id"), viewer.getBoolean("Measured"), viewer.getInt("Rtt"), viewer.getInt("Kbps")));
                    names.add(viewer.getString("Name"));
                }
                viewers = known;
                viewerNames = names;
            }
        }
    }
}
