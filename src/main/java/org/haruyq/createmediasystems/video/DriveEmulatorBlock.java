package org.haruyq.createmediasystems.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.disc.DriveClient;
import org.haruyq.createmediasystems.disc.DriveScreen;
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
 * reads the disc and shares it directly (see DriveClient). Right-click for the drive, which opens and closes as the
 * real one does, for everyone who looks (see DriveScreen); goggles show how well each viewer's connection holds up.
 */
public class DriveEmulatorBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<DriveEmulatorBlock> CODEC = simpleCodec(DriveEmulatorBlock::new);
    /** Its lamp: lit while a disc is in. */
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
        return type != CreateMediaSystems.DRIVE_EMULATOR_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** What plays a drive's disc while it has none of its own: a Blu-ray player, a disc reader. */
    public interface Fed {
        /** The drive beside it took a disc ({@code by} put it in) or lost it. */
        void driveChanged(@Nullable Player by);
    }

    /**
     * The drive beside {@code pos} whose disc is ready for what is there: an audio CD for a disc reader
     * ({@code audio}), a DVD or Blu-ray for a Blu-ray player; null for none.
     */
    @Nullable
    public static Entity feeding(@Nullable BlockGetter level, BlockPos pos, boolean audio) {
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(pos.relative(side)) instanceof Entity emulator && emulator.drive == Drive.READY
                        && emulator.disc.audio() == audio) {
                    return emulator;
                }
            }
        }
        return null;
    }

    /** The drive beside {@code pos}, with a disc in it if any has; null for none. */
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
        READY(ChatFormatting.GREEN), FAILED(ChatFormatting.RED);

        private final ChatFormatting color;

        Drive(ChatFormatting color) {
            this.color = color;
        }

        public static Drive byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /**
     * The kinds of disc a drive takes: the tallest picture (pixels) each plays, 0 for sound alone, and the rate
     * (kbps) a viewer's connection has to keep up for it to play well (see Viewer#stability).
     */
    public enum Disc {
        BLU_RAY("Blu-ray", 1080, 4000), DVD("DVD", 480, 2000), CD("CD", 0, 500);

        public final String name;
        public final int maxHeight;
        final int kbps;

        Disc(String name, int maxHeight, int kbps) {
            this.name = name;
            this.maxHeight = maxHeight;
            this.kbps = kbps;
        }

        /** Whether it is a disc reader's, not a Blu-ray player's. */
        public boolean audio() {
            return maxHeight == 0;
        }

        public static Disc byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /** How well a viewer's connection holds up. */
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

        /** Stable while it keeps up the rate {@code disc} needs with a short round trip; poor at half that rate. */
        public Stability stability(Disc disc) {
            return !measured ? Stability.CONNECTING
                    : kbps < disc.kbps / 2 || rttMillis >= POOR_RTT ? Stability.POOR
                    : kbps < disc.kbps || rttMillis >= SLOW_RTT ? Stability.UNSTABLE : Stability.STABLE;
        }
    }

    /**
     * Client → server: this player's real drive, for the emulator at {@code pos}, is now {@code drive}.
     * {@code text} is the disc's label as it is read, its link once it is shared (see DiscUrl), or why it failed.
     */
    public record Update(BlockPos pos, int drive, String text, int disc) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("drive_update"));
        public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Update::pos,
                ByteBufCodecs.VAR_INT, Update::drive,
                ByteBufCodecs.stringUtf8(256), Update::text,
                ByteBufCodecs.VAR_INT, Update::disc,
                Update::new);

        public Update(BlockPos pos, Drive drive, String text, Disc disc) {
            this(pos, drive.ordinal(), text.length() > 256 ? text.substring(0, 256) : text, disc.ordinal());
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
                        Disc.byId(payload.disc()));
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

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation {
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
        /** Why reading it failed. */
        private String error = "";
        /** Whether the disc turns: as it is read, and while what it is beside plays it. For its sound and lamp. */
        private boolean spinning;
        /** The disc's viewers by name, as its host's client last told. Not saved. */
        private List<Viewer> viewers = List.of();
        private List<String> viewerNames = List.of();

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.DRIVE_EMULATOR_ENTITY.get(), pos, state);
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

        public String link() {
            return link;
        }

        public String error() {
            return error;
        }

        public boolean spinning() {
            return spinning;
        }

        /** What the disc in it is called: its label and kind. */
        public String discName() {
            return label.isEmpty() ? disc.name : label + " (" + disc.name + ")";
        }

        /** Its drive's state, as its screen and goggles say it. */
        public Component status() {
            return Component.translatable("create_media_systems.drive." + drive.name().toLowerCase(Locale.ROOT))
                    .withStyle(drive.color);
        }

        /** Whether what plays a disc of kind {@code kind} is beside it: a disc reader for a CD, else a Blu-ray player. */
        public boolean feeds(Disc kind) {
            for (Direction side : Direction.values()) {
                BlockEntity beside = level == null ? null : level.getBlockEntity(worldPosition.relative(side));
                if (kind.audio() ? beside instanceof DiscReaderBlock.Entity : beside instanceof VideoPlayerBlock.Entity) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code player}'s real drive is now {@code next} (see Update). Anyone at a free emulator makes it theirs by
         * putting a disc in; from then on only they change it, from anywhere, and only to a disc they share.
         */
        public void update(ServerPlayer player, Drive next, String text, Disc kind) {
            if (host == null ? next == Drive.OPEN || !player.canInteractWithBlock(worldPosition, 4)
                    : !host.equals(player.getUUID())) {
                return;
            }
            if (next == Drive.READY) {
                DiscUrl url = DiscUrl.parse(text);
                if (url == null || !url.owner().equals(player.getUUID())) {
                    return;
                }
            }
            boolean wasOpen = drive == Drive.OPEN;
            switch (next) {
                case OPEN -> label = "";
                case READING, DOWNLOADING -> label = text;
                case FAILED -> error = text;
                case READY -> {
                }
            }
            if (next != Drive.OPEN) {
                disc = kind;
            }
            link = next == Drive.READY ? text : "";
            drive = next;
            host = next == Drive.OPEN ? null : player.getUUID();
            hostName = player.getGameProfile().getName();
            if (wasOpen || next == Drive.OPEN) {
                // The tray, a little higher as it springs open.
                sound(CreateMediaSystems.DRIVE_TRAY.get(), next == Drive.OPEN ? 1.15f : 1);
            }
            if (next == Drive.READING) {
                sound(CreateMediaSystems.DRIVE_SEEK.get(), 1);
            }
            changed(player);
        }

        /** The viewers of its disc, from its host's client. */
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

        /** Open again, its disc gone with its player or the block. */
        void release() {
            if (drive != Drive.OPEN) {
                drive = Drive.OPEN;
                host = null;
                label = "";
                link = "";
                changed(null);
            }
        }

        private void sound(SoundEvent sound, float pitch) {
            level.playSound(null, worldPosition, sound, SoundSource.BLOCKS, 0.5f, pitch);
        }

        /** Its drive changed: clients are told, its lamp follows, and what is beside it plays the disc, or stops. */
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
                if (level.getBlockEntity(worldPosition.relative(side)) instanceof Fed fed) {
                    fed.driveChanged(by);
                }
            }
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
            boolean turns = drive == Drive.READING || drive == Drive.DOWNLOADING || drive == Drive.READY && played();
            if (turns != spinning) {
                spinning = turns;
                sendData();
            }
        }

        /** Whether what is beside it plays its disc now. */
        private boolean played() {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(worldPosition.relative(side)) instanceof MediaSource source) {
                    Streams.Source playing = source.nowPlaying();
                    if (playing != null && playing.url().equals(link)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** What goggles on the Blu-ray player beside it say of it: its state, and its disc and whose it is. */
        public void describe(List<Component> tooltip) {
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.drive", status()))
                    .withStyle(ChatFormatting.GRAY));
            if (drive != Drive.OPEN) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.disc", discName()))
                        .withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.host", hostName))
                        .withStyle(ChatFormatting.GRAY));
            }
            if (drive == Drive.FAILED) {
                tooltip.add(Component.literal("     " + error).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        /** Its own goggles: how well each viewer's connection to its host holds up, their round trip and rate. */
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
