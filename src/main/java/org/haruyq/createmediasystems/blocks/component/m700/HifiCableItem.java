package org.haruyq.createmediasystems.blocks.component.m700;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class HifiCableItem extends Item {
    /** How far (blocks) a speaker cable reaches. */
    public static final int MAX_LENGTH = 24;
    /** The terminal each player last touched with a cable, until they touch a speaker with it. */
    // ponytail: kept until they do or the server stops, one entry per player who ever started a run; clear on logout if that grows.
    private static final Map<UUID, From> PICKED = new HashMap<>();

    /** An amplifier's speaker terminal: its stack, and the channel it puts out. */
    record From(GlobalPos stack, Streams.Channel channel) {
    }

    public HifiCableItem(Properties properties) {
        super(properties);
    }

    static void pick(ServerPlayer player, HifiStackBlock.Entity stack, Streams.Channel channel) {
        if (!player.getMainHandItem().is(HifiFeature.HIFI_CABLE.get())) {
            player.displayClientMessage(Component.translatable("create_media_systems.hifi.needs_cable")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        PICKED.put(player.getUUID(), new From(GlobalPos.of(player.level().dimension(), stack.getBlockPos().immutable()), channel));
        player.displayClientMessage(Component.translatable("create_media_systems.hifi.speaker_picked", channel.label()), true);
    }

    /** Where the cable {@code player} holds starts; null if they touched no terminal with it. */
    @Nullable
    static From picked(ServerPlayer player) {
        return PICKED.get(player.getUUID());
    }

    static void done(ServerPlayer player) {
        PICKED.remove(player.getUUID());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("create_media_systems.hifi.cable.hint").withStyle(ChatFormatting.GRAY));
    }
}
