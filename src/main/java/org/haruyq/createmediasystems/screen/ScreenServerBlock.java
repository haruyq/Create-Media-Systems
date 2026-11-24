package org.haruyq.createmediasystems.screen;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Viewer;
import org.haruyq.createmediasystems.disc.DriveEmulatorBlock.Viewers;
import org.haruyq.createmediasystems.disc.ViewerList;
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

    /** Client → server: the viewers of this player's share through the server at the payload's pos, once a second. */
    public static final CustomPacketPayload.Type<Viewers> VIEWERS = new CustomPacketPayload.Type<>(CreateMediaSystems.id("screen_viewers"));

    static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Update.TYPE, Update.CODEC, (payload, context) -> {
            if (server(context.player(), payload.pos()) instanceof Entity server) {
                server.update((ServerPlayer) context.player(), payload.link(), payload.what());
            }
        });
        registrar.playToServer(VIEWERS, Viewers.codec(VIEWERS), (payload, context) -> {
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
        private final ViewerList viewers = new ViewerList();

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
            return DiscDrive.feeds(level, worldPosition, DiscKind.BLU_RAY);
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
            if (player.getUUID().equals(host) && live() && viewers.tell(player, told)) {
                sendData();
            }
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
                viewers.clear();
            }
            setChanged();
            if (level == null || level.isClientSide) {
                return;
            }
            sendData();
            if (getBlockState().getValue(LIT) != live() && level.getBlockState(worldPosition).is(getBlockState().getBlock())) {
                level.setBlockAndUpdate(worldPosition, getBlockState().setValue(LIT, live()));
            }
            DiscDrive.changed(level, worldPosition, by, relinked);
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
            viewers.describe(tooltip, DriveEmulatorBlock.Disc.BLU_RAY);
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
                viewers.write(tag);
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
                viewers.read(tag);
            }
        }
    }
}
