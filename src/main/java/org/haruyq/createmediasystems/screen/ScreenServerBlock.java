package org.haruyq.createmediasystems.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Stability;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Viewer;
import org.haruyq.createmediasystems.stream.DiscDrive;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaKind;

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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
 * A rack-mount server that shares its user's screen, with a Monitor on top: while they share a window or
 * a monitor through it (see ShareScreen), that monitor and the Blu-ray Player beside it play that live, the player as
 * it would a drive's disc. Their client captures and sends it directly (see ScreenShare).
 */
public class ScreenServerBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable,
        SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<ScreenServerBlock> CODEC = simpleCodec(ScreenServerBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ScreenServerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    protected MapCodec<ScreenServerBlock> codec() {
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // Without its monitor it opens nothing; its Ponder scene tells why.
        if (!monitored(level, pos)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            ShareScreen.open(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Whether the server at {@code pos} has its monitor on top, without which it shares nothing. */
    static boolean monitored(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).getBlock() instanceof ScreenMonitorBlock;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity server) {
            server.release();
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
        return type != ScreenFeature.SERVER_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /**
     * Client → server: this player now shares their screen through the server at {@code pos} as {@code link} (see
     * DiscUrl#screen), showing {@code what}; an empty link stops it.
     */
    public record Update(BlockPos pos, String link, String what) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("screen_update"));
        public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Update::pos,
                ByteBufCodecs.stringUtf8(256), Update::link,
                ByteBufCodecs.stringUtf8(64), Update::what,
                Update::new);

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    /** Client → server: the viewers of this player's share through the server at {@code pos}, once a second. */
    public record Viewers(BlockPos pos, List<Viewer> viewers) implements CustomPacketPayload {
        public static final Type<Viewers> TYPE = new Type<>(CreateMediaSystems.id("screen_viewers"));
        public static final StreamCodec<ByteBuf, Viewers> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Viewers::pos,
                Viewer.CODEC.apply(ByteBufCodecs.list(64)), Viewers::viewers,
                Viewers::new);

        @Override
        public Type<Viewers> type() {
            return TYPE;
        }
    }

    static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Update.TYPE, Update.CODEC, (payload, context) -> {
            if (server(context.player(), payload.pos()) instanceof Entity server) {
                server.update((ServerPlayer) context.player(), payload.link(), payload.what());
            }
        });
        registrar.playToServer(Viewers.TYPE, Viewers.CODEC, (payload, context) -> {
            if (server(context.player(), payload.pos()) instanceof Entity server) {
                server.viewers((ServerPlayer) context.player(), payload.viewers());
            }
        });
    }

    /** The server beside {@code pos} (a Blu-ray Player's) that shares {@code link}; null if none does. */
    @Nullable
    public static Entity sharing(@Nullable BlockGetter level, BlockPos pos, String link) {
        if (level != null) {
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(pos.relative(side)) instanceof Entity server && server.live() && server.link().equals(link)) {
                    return server;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockEntity server(Player player, BlockPos pos) {
        return player.level().isLoaded(pos) ? player.level().getBlockEntity(pos) : null;
    }

    public static class Entity extends SmartBlockEntity implements IHaveGoggleInformation, DiscDrive {
        /** Whose screen it shares, from when they start until they stop or leave. */
        @Nullable
        private UUID host;
        private String hostName = "";
        /** The share's link (see DiscUrl#screen), empty while nothing is shared, and what it shows. */
        private String link = "";
        private String what = "";
        /** The share's viewers by name, as its host's client last told. Not saved. */
        private List<Viewer> viewers = List.of();
        private List<String> viewerNames = List.of();

        public Entity(BlockPos pos, BlockState state) {
            super(ScreenFeature.SERVER_ENTITY.get(), pos, state);
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

        public String what() {
            return what;
        }

        public boolean live() {
            return !link.isEmpty();
        }

        /** Played as a Blu-ray would be: by the Blu-ray Player beside it. */
        @Override
        @Nullable
        public DiscKind ready() {
            return live() ? DiscKind.BLU_RAY : null;
        }

        @Override
        public String link() {
            return link;
        }

        public boolean feeds() {
            for (Direction side : Direction.values()) {
                if (level != null && level.getBlockEntity(worldPosition.relative(side)) instanceof DiscDrive.Fed fed
                        && fed.plays(DiscKind.BLU_RAY)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code player} now shares {@code next} (empty: nothing). Anyone at a free server makes it theirs by sharing;
         * from then on only they change it, from anywhere, and only to a share of their own.
         */
        void update(ServerPlayer player, String next, String shows) {
            if (host == null ? next.isEmpty() || !player.canInteractWithBlock(worldPosition, 4)
                    : !host.equals(player.getUUID())) {
                return;
            }
            if (!next.isEmpty() && (MediaKind.of(next) != MediaKind.SCREEN || !MediaKind.writableBy(next, player.getUUID())
                    || !monitored(level, worldPosition))) {
                return;
            }
            boolean relinked = live() && !next.isEmpty() && !next.equals(link);
            if (next.equals(link) && shows.equals(what)) {
                return;
            }
            boolean changedLink = !next.equals(link);
            link = next;
            what = next.isEmpty() ? "" : shows;
            host = next.isEmpty() ? null : player.getUUID();
            hostName = player.getGameProfile().getName();
            if (changedLink) {
                changed(player, relinked);
            } else {
                setChanged();
                sendData();
            }
        }

        void viewers(ServerPlayer player, List<Viewer> told) {
            if (!player.getUUID().equals(host) || !live() || told.equals(viewers)) {
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
            if (live()) {
                link = "";
                what = "";
                host = null;
                changed(null, false);
            }
        }

        private void changed(@Nullable Player by, boolean relinked) {
            if (!live()) {
                viewers = List.of();
                viewerNames = List.of();
            }
            setChanged();
            if (level == null || level.isClientSide) {
                return;
            }
            sendData();
            if (getBlockState().getValue(LIT) != live() && level.getBlockState(worldPosition).is(getBlockState().getBlock())) {
                level.setBlockAndUpdate(worldPosition, getBlockState().setValue(LIT, live()));
            }
            for (Direction side : Direction.values()) {
                if (level.getBlockEntity(worldPosition.relative(side)) instanceof DiscDrive.Fed fed) {
                    if (relinked) {
                        fed.driveRelinked(by);
                    } else {
                        fed.driveChanged(by);
                    }
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
                ScreenClient.tick(this);
                return;
            }
            if (level.getGameTime() % 10 == 0 && host != null && (level.getServer().getPlayerList().getPlayer(host) == null
                    || !monitored(level, worldPosition))) {
                // They left, or the monitor did: the share went with them.
                release();
            }
        }

        public Component status() {
            return live() ? Component.translatable("create_media_systems.screen_server.live").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("create_media_systems.screen_server.idle").withStyle(ChatFormatting.GRAY);
        }

        /** What a Blu-ray Player playing this share says of it: what is shared, and whose it is. */
        public void describe(List<Component> tooltip) {
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.screen_server.showing", what))
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.screen_server.host", hostName))
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            tooltip.add(Component.literal("     ").append(status()));
            if (!live()) {
                return true;
            }
            tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.host", hostName))
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("     ").append(Component.translatable(viewers.isEmpty()
                    ? "create_media_systems.drive.no_viewers" : "create_media_systems.drive.viewers")).withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < viewers.size(); i++) {
                Viewer viewer = viewers.get(i);
                Stability stability = viewer.stability(DriveEmulatorBlock.Disc.BLU_RAY);
                tooltip.add(Component.literal("     " + viewerNames.get(i) + "  ").withStyle(ChatFormatting.WHITE)
                        .append(Component.translatable("create_media_systems.drive.stability."
                                + stability.name().toLowerCase(Locale.ROOT)).withStyle(stability.color()))
                        .append(Component.literal(!viewer.measured() ? "" : String.format(Locale.ROOT, "  %d ms  %.1f Mbps",
                                viewer.rttMillis(), viewer.kbps() / 1000.0)).withStyle(ChatFormatting.GRAY)));
            }
            return true;
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            if (host != null) {
                tag.putUUID("Host", host);
            }
            tag.putString("HostName", hostName);
            tag.putString("Link", link);
            tag.putString("What", what);
            if (clientPacket) {
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
            host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
            hostName = tag.getString("HostName");
            link = tag.getString("Link");
            what = tag.getString("What");
            if (clientPacket) {
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
