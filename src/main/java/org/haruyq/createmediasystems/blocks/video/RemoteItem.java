package org.haruyq.createmediasystems.blocks.video;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxEntity;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.Link;

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

/** Controls one paired device from afar, within the server's {@code remoteRange}. */
public class RemoteItem extends Item {
    public RemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof Remotable)) {
            return InteractionResult.PASS;
        }
        if (!context.getLevel().isClientSide) {
            Link link = Link.to(context.getLevel(), context.getClickedPos());
            context.getItemInHand().set(VideoFeature.REMOTE_TARGET.get(), GlobalPos.of(context.getLevel().dimension(), link.pos()));
            context.getItemInHand().set(VideoFeature.REMOTE_SOURCE.get(), link.id());
            context.getItemInHand().remove(VideoFeature.REMOTE_ENTITY.get());
            player.displayClientMessage(Component.translatable("create_media_systems.remote.paired"), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    public static void pair(PartyBoxEntity box, Player player, ItemStack remote) {
        remote.set(VideoFeature.REMOTE_TARGET.get(), GlobalPos.of(box.level().dimension(), box.blockPosition()));
        remote.set(VideoFeature.REMOTE_ENTITY.get(), box.getUUID());
        remote.remove(VideoFeature.REMOTE_SOURCE.get());
        player.displayClientMessage(Component.translatable("create_media_systems.remote.paired_partybox"), true);
    }

    /** Where the player {@code stack} is paired with is now (the stack follows it); null if unpaired or it is gone. */
    @Nullable
    public static GlobalPos target(Level level, ItemStack stack) {
        GlobalPos target = stack.get(VideoFeature.REMOTE_TARGET.get());
        if (target == null || level.isClientSide || stack.has(VideoFeature.REMOTE_ENTITY.get())) {
            return target;
        }
        Level targetLevel = level.getServer().getLevel(target.dimension());
        BlockPos pos = targetLevel == null ? null
                : Link.follow(targetLevel, stack, VideoFeature.REMOTE_TARGET.get(), VideoFeature.REMOTE_SOURCE.get());
        return pos == null ? null : GlobalPos.of(target.dimension(), pos);
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
        GlobalPos target = stack.get(VideoFeature.REMOTE_TARGET.get());
        if (level.isClientSide) {
            if (target == null) {
                player.displayClientMessage(Component.translatable("create_media_systems.remote.unpaired"), true);
            } else {
                RemoteScreen.open(target, hand);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos target = stack.get(VideoFeature.REMOTE_TARGET.get());
        tooltip.add(stack.has(VideoFeature.REMOTE_ENTITY.get())
                ? Component.translatable("create_media_systems.remote.partybox").withStyle(ChatFormatting.GRAY)
                : target == null
                ? Component.translatable("create_media_systems.remote.unpaired").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("create_media_systems.remote.target", target.pos().getX(), target.pos().getY(),
                        target.pos().getZ()).withStyle(ChatFormatting.GRAY));
    }

    /** Client → server: a remote's button for what it is paired with. */
    public record RemoteControl(InteractionHand hand, Remotable.Action action, int value) implements CustomPacketPayload {
        public static final Type<RemoteControl> TYPE = new Type<>(CreateMediaSystems.id("remote_control"));
        public static final StreamCodec<ByteBuf, RemoteControl> CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(id -> id == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND,
                        InteractionHand::ordinal), RemoteControl::hand,
                Remotable.Action.CODEC, RemoteControl::action,
                ByteBufCodecs.VAR_INT, RemoteControl::value,
                RemoteControl::new);

        @Override
        public Type<RemoteControl> type() {
            return TYPE;
        }
    }

    static void remoteControl(ServerPlayer player, RemoteControl payload) {
        if (player.isSpectator()) return;
        ItemStack remote = player.getItemInHand(payload.hand());
        if (!remote.is(VideoFeature.REMOTE.get())) return;
        GlobalPos target = RemoteItem.target(player.level(), remote);
        if (target == null) {
            if (remote.has(VideoFeature.REMOTE_TARGET.get())) {
                player.displayClientMessage(Component.translatable("create_media_systems.remote.missing"), true);
            }
            return;
        }
        if (target.dimension() != player.level().dimension()) {
            tooFar(player);
            return;
        }
        var entityId = remote.get(VideoFeature.REMOTE_ENTITY.get());
        if (entityId != null) {
            if (!(player.serverLevel().getEntity(entityId) instanceof PartyBoxEntity box) || box.isRemoved()) {
                player.displayClientMessage(Component.translatable("create_media_systems.remote.missing"), true);
                return;
            }
            double range = VideoFeature.REMOTE_RANGE.get();
            if (player.distanceToSqr(box) > range * range) {
                tooFar(player);
                return;
            }
            box.control(payload.action(), payload.value());
            return;
        }
        BlockPos pos = target.pos();
        if (SableCompat.distance(player.level(), pos, player.blockPosition()) > VideoFeature.REMOTE_RANGE.get()) {
            tooFar(player);
            return;
        }
        if (!player.level().isLoaded(pos) || !(player.level().getBlockEntity(pos) instanceof Remotable videoPlayer)) {
            player.displayClientMessage(Component.translatable("create_media_systems.remote.missing").withStyle(ChatFormatting.RED), true);
            return;
        }
        videoPlayer.control(payload.action(), payload.value(), player);
    }

    private static void tooFar(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("create_media_systems.remote.too_far", VideoFeature.REMOTE_RANGE.get())
                .withStyle(ChatFormatting.RED), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(RemoteControl.TYPE, RemoteControl.CODEC,
                (payload, context) -> remoteControl((ServerPlayer) context.player(), payload));
    }
}
