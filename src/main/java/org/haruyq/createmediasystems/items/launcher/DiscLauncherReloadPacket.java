package org.haruyq.createmediasystems.items.launcher;

import org.haruyq.createmediasystems.CreateMediaSystems;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** R-key request only: all item validation and ammo escrow are performed by the server. */
public record DiscLauncherReloadPacket() implements CustomPacketPayload {
    public static final DiscLauncherReloadPacket INSTANCE = new DiscLauncherReloadPacket();
    public static final Type<DiscLauncherReloadPacket> TYPE =
            new Type<>(CreateMediaSystems.id("disc_launcher_reload"));
    public static final StreamCodec<ByteBuf, DiscLauncherReloadPacket> CODEC = StreamCodec.unit(INSTANCE);

    @Override public Type<DiscLauncherReloadPacket> type() { return TYPE; }

    private static void onServer(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof DiscLauncherItem)) return;
        boolean started = player.isShiftKeyDown()
                ? LauncherMagazine.eject(player, stack, player.level().getGameTime())
                : LauncherMagazine.reserve(player, stack, player.level().getGameTime());
        if (started) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    LauncherFeature.DISC_LATCH_SOUND.get(), SoundSource.PLAYERS, .6f, 1.2f);
        } else if (!LauncherMagazine.reloading(stack)) player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("cell.create_media_systems.need_cell"), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, (payload, context) -> onServer((ServerPlayer) context.player()));
    }
}
