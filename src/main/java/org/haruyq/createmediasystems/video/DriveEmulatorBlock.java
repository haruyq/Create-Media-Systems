package org.haruyq.createmediasystems.video;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.disc.DriveClient;
import org.haruyq.createmediasystems.disc.DriveScreen;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A Blu-ray player whose disc is a real one: the DVD or Blu-ray in the drive of the computer of whoever uses it, which
 * their client reads and shares directly (see DriveClient). Right-click for its drive, which opens and closes as
 * theirs does, for everyone who looks (see DriveScreen). Displays, speakers and a remote take it for a Blu-ray player.
 */
public class DriveEmulatorBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<DriveEmulatorBlock> CODEC = simpleCodec(DriveEmulatorBlock::new);

    public DriveEmulatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<DriveEmulatorBlock> codec() {
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            DriveScreen.open(pos);
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
        return type != CreateMediaSystems.DRIVE_EMULATOR_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** What the real drive does, as everyone sees it: open with no disc, or closed on one. */
    public enum Drive {
        OPEN(ChatFormatting.GRAY), READING(ChatFormatting.YELLOW),
        /** Reading waits for FFmpeg to be downloaded. */
        DOWNLOADING(ChatFormatting.YELLOW),
        /** The disc is read and shared: it plays, like a disc in a Blu-ray player. */
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
     * Client → server: this player's real drive, for the emulator at {@code pos}, is now {@code drive}.
     * {@code text} is the disc's label as it is read, its link once it is shared (see DiscUrl), or why it failed.
     */
    public record Update(BlockPos pos, int drive, String text, boolean dvd) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("drive_update"));
        public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Update::pos,
                ByteBufCodecs.VAR_INT, Update::drive,
                ByteBufCodecs.stringUtf8(256), Update::text,
                ByteBufCodecs.BOOL, Update::dvd,
                Update::new);

        public Update(BlockPos pos, Drive drive, String text, boolean dvd) {
            this(pos, drive.ordinal(), text.length() > 256 ? text.substring(0, 256) : text, dvd);
        }

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Update.TYPE, Update.CODEC, (payload, context) -> {
            Level level = context.player().level();
            if (level.isLoaded(payload.pos()) && level.getBlockEntity(payload.pos()) instanceof Entity emulator) {
                emulator.update((ServerPlayer) context.player(), Drive.byId(payload.drive()), payload.text(), payload.dvd());
            }
        });
    }

    public static class Entity extends VideoPlayerBlock.Entity {
        private Drive drive = Drive.OPEN;
        /** Whose real drive it is, from when they put a disc in until it comes out or they leave. */
        @Nullable
        private UUID host;
        private String hostName = "";
        /** The disc's volume label; empty if it has none. */
        private String label = "";
        private boolean dvd;
        /** Why reading it failed. */
        private String error = "";

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.DRIVE_EMULATOR_ENTITY.get(), pos, state);
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

        public String error() {
            return error;
        }

        public boolean dvd() {
            return dvd;
        }

        /** What the disc in it is called: its label and kind. */
        public String disc() {
            String kind = dvd ? "DVD" : "Blu-ray";
            return label.isEmpty() ? kind : label + " (" + kind + ")";
        }

        /** Its drive's state as its screen and goggles say it: a disc that is ready plays, pauses or is stopped. */
        public Component status() {
            return drive == Drive.READY ? coloredState()
                    : Component.translatable("create_media_systems.drive." + drive.name().toLowerCase(Locale.ROOT))
                            .withStyle(drive.color);
        }

        /**
         * {@code player}'s real drive is now {@code next} (see Update). Anyone at a free emulator makes it theirs by
         * putting a disc in; from then on only they change it, from anywhere, and only to a disc they share.
         */
        public void update(ServerPlayer player, Drive next, String text, boolean dvd) {
            if (host == null ? next == Drive.OPEN || !player.canInteractWithBlock(worldPosition, 4)
                    : !host.equals(player.getUUID())) {
                return;
            }
            ItemStack disc = ItemStack.EMPTY;
            switch (next) {
                case OPEN -> label = "";
                case READING, DOWNLOADING -> {
                    label = text;
                    this.dvd = dvd;
                }
                case READY -> {
                    DiscUrl url = DiscUrl.parse(text);
                    if (url == null || !url.owner().equals(player.getUUID())) {
                        return;
                    }
                    this.dvd = dvd;
                    disc = new ItemStack(dvd ? CreateMediaSystems.DVD.get() : CreateMediaSystems.BLU_RAY.get());
                    disc.set(CreateMediaSystems.PLAYLIST.get(), List.of(text));
                }
                case FAILED -> error = text;
            }
            drive = next;
            host = next == Drive.OPEN ? null : player.getUUID();
            hostName = player.getGameProfile().getName();
            insert(disc, player);
        }

        @Override
        public void tick() {
            super.tick();
            if (level == null || host == null || level.getGameTime() % 20 != 0) {
                return;
            }
            if (level.isClientSide) {
                DriveClient.check(this);
            } else if (level.getServer().getPlayerList().getPlayer(host) == null) {
                // They left with their disc.
                drive = Drive.OPEN;
                host = null;
                label = "";
                insert(ItemStack.EMPTY, null);
            }
        }

        @Override
        protected void describeDisc(List<Component> tooltip) {
            tooltip.add(Component.literal("     ").append(status()));
            if (drive != Drive.OPEN) {
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.disc", disc()))
                        .withStyle(ChatFormatting.GOLD));
                tooltip.add(Component.literal("     ").append(Component.translatable("create_media_systems.drive.host", hostName))
                        .withStyle(ChatFormatting.GRAY));
            }
            if (drive == Drive.FAILED) {
                tooltip.add(Component.literal("     " + error).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.write(tag, registries, clientPacket);
            tag.putInt("Drive", drive.ordinal());
            if (host != null) {
                tag.putUUID("Host", host);
            }
            tag.putString("HostName", hostName);
            tag.putString("Label", label);
            tag.putBoolean("Dvd", dvd);
            tag.putString("Error", error);
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            super.read(tag, registries, clientPacket);
            drive = Drive.byId(tag.getInt("Drive"));
            host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
            hostName = tag.getString("HostName");
            label = tag.getString("Label");
            dvd = tag.getBoolean("Dvd");
            error = tag.getString("Error");
        }
    }
}
