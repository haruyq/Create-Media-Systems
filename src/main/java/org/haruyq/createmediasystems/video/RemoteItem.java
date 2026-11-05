package org.haruyq.createmediasystems.video;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.stream.SourceLinks;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Controls one Blu-ray player from afar (within the server's {@code remoteRange}): Shift+right-click a Blu-ray player to pair
 * it, right-click in the air for its buttons.
 */
public class RemoteItem extends Item {
    public RemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof VideoPlayerBlock.Entity)) {
            return InteractionResult.PASS;
        }
        if (!context.getLevel().isClientSide) {
            context.getItemInHand().set(CreateMediaSystems.REMOTE_TARGET.get(),
                    GlobalPos.of(context.getLevel().dimension(), context.getClickedPos().immutable()));
            context.getItemInHand().set(CreateMediaSystems.REMOTE_SOURCE.get(),
                    SourceLinks.identify(context.getLevel(), context.getClickedPos()));
            player.displayClientMessage(Component.translatable("create_media_systems.remote.paired"), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /** The remote in {@code player}'s main hand, else what is in their off hand. */
    static ItemStack held(Player player) {
        return player.getMainHandItem().is(CreateMediaSystems.REMOTE.get()) ? player.getMainHandItem() : player.getOffhandItem();
    }

    @Nullable
    public static GlobalPos target(Level level, ItemStack stack) {
        GlobalPos target = stack.get(CreateMediaSystems.REMOTE_TARGET.get());
        if (target == null || level.isClientSide) {
            return target;
        }
        Level targetLevel = level.getServer().getLevel(target.dimension());
        if (targetLevel == null) {
            return null;
        }
        UUID id = stack.get(CreateMediaSystems.REMOTE_SOURCE.get());
        if (id == null) {
            id = SourceLinks.identify(targetLevel, target.pos());
            if (id != null) {
                stack.set(CreateMediaSystems.REMOTE_SOURCE.get(), id);
            }
        }
        BlockPos pos = SourceLinks.resolve(targetLevel, id, target.pos());
        if (pos == null) {
            return null;
        }
        GlobalPos resolved = GlobalPos.of(target.dimension(), pos);
        if (!resolved.equals(target)) {
            stack.set(CreateMediaSystems.REMOTE_TARGET.get(), resolved);
        }
        return resolved;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!level.isClientSide && level.getGameTime() % 10 == 0) {
            target(level, stack);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        GlobalPos target = stack.get(CreateMediaSystems.REMOTE_TARGET.get());
        if (level.isClientSide) {
            if (target == null) {
                player.displayClientMessage(Component.translatable("create_media_systems.remote.unpaired"), true);
            } else {
                RemoteScreen.open(target);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos target = stack.get(CreateMediaSystems.REMOTE_TARGET.get());
        tooltip.add(target == null
                ? Component.translatable("create_media_systems.remote.unpaired").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("create_media_systems.remote.target", target.pos().getX(), target.pos().getY(),
                        target.pos().getZ()).withStyle(ChatFormatting.GRAY));
    }

    /** Client → server: a remote's button ({@link VideoPlayerBlock.Action}) for its paired Blu-ray player. */
    public record RemoteControl(int action, int value) implements CustomPacketPayload {
        public static final Type<RemoteControl> TYPE = new Type<>(CreateMediaSystems.id("remote_control"));
        public static final StreamCodec<ByteBuf, RemoteControl> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RemoteControl::action,
                ByteBufCodecs.VAR_INT, RemoteControl::value,
                RemoteControl::new);

        @Override
        public Type<RemoteControl> type() {
            return TYPE;
        }
    }

    /** A button of the remote in hand, for the Blu-ray player it is paired with, if that is within the server's range. */
    static void remoteControl(ServerPlayer player, RemoteControl payload) {
        ItemStack remote = held(player);
        GlobalPos target = remote.is(CreateMediaSystems.REMOTE.get()) ? RemoteItem.target(player.level(), remote) : null;
        if (target == null) {
            if (remote.has(CreateMediaSystems.REMOTE_TARGET.get())) {
                player.displayClientMessage(Component.translatable("create_media_systems.remote.missing"), true);
            }
            return;
        }
        BlockPos pos = target.pos();
        if (target.dimension() != player.level().dimension()
                || SableCompat.distance(player.level(), pos, player.blockPosition()) > ServerConfig.REMOTE_RANGE.get()) {
            player.displayClientMessage(Component.translatable("create_media_systems.remote.too_far", ServerConfig.REMOTE_RANGE.get())
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!player.level().isLoaded(pos) || !(player.level().getBlockEntity(pos) instanceof VideoPlayerBlock.Entity videoPlayer)) {
            player.displayClientMessage(Component.translatable("create_media_systems.remote.missing").withStyle(ChatFormatting.RED), true);
            return;
        }
        videoPlayer.control(VideoPlayerBlock.Action.byId(payload.action()), payload.value());
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(RemoteControl.TYPE, RemoteControl.CODEC,
                (payload, context) -> remoteControl((ServerPlayer) context.player(), payload));
    }
}
