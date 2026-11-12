package org.haruyq.createmediasystems.disc;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class PlaylistItem extends Item implements MediaItem {
    /** Most links any disc holds (the network's limit). */
    public static final int MAX_ENTRIES = 64;
    private static final int SHOWN_ENTRIES = 5;

    private final DiscKind kind;

    public PlaylistItem(Properties properties, DiscKind kind) {
        super(properties);
        if (kind.entries > MAX_ENTRIES) {
            throw new IllegalArgumentException("A disc holds at most " + MAX_ENTRIES + " links");
        }
        this.kind = kind;
    }

    @Override
    public DiscKind kind() {
        return kind;
    }

    @Override
    public List<String> links(ItemStack stack) {
        return stack.getOrDefault(DiscFeature.PLAYLIST.get(), List.of());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            PlaylistScreen.open(links(stack), kind.entries);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<String> playlist = links(stack);
        if (playlist.isEmpty()) {
            tooltip.add(Component.translatable("create_media_systems.url.unset").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable(kind.hasPicture() ? "create_media_systems.disc.entries" : "create_media_systems.disc.tracks", playlist.size()).withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < Math.min(playlist.size(), SHOWN_ENTRIES); i++) {
            Component title = Streams.title(playlist.get(i));
            tooltip.add(Component.literal((i + 1) + ". ").append(title == null ? Component.literal(playlist.get(i)) : title)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (playlist.size() > SHOWN_ENTRIES) {
            tooltip.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Client → server: write {@code urls} onto the disc in the main hand, as its playlist. */
    public record SetPlaylist(List<String> urls) implements CustomPacketPayload {
        public static final Type<SetPlaylist> TYPE = new Type<>(CreateMediaSystems.id("set_playlist"));
        public static final StreamCodec<ByteBuf, SetPlaylist> CODEC = StreamPackets.URL
                .apply(ByteBufCodecs.list(PlaylistItem.MAX_ENTRIES)).map(SetPlaylist::new, SetPlaylist::urls);

        @Override
        public Type<SetPlaylist> type() {
            return TYPE;
        }
    }

    /**
     * Writes {@code urls} onto the disc in the player's hand, all of them or (if any isn't allowed) none, and says which
     * entry was refused.
     */
    static void writePlaylist(ServerPlayer player, List<String> urls) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof PlaylistItem disc)) {
            return;
        }
        List<String> playlist = urls.stream().map(String::trim).filter(url -> !url.isEmpty())
                .limit(disc.kind.entries).toList();
        for (int i = 0; i < playlist.size(); i++) {
            String url = playlist.get(i);
            if (!UrlPolicy.isAllowed(url) || !MediaKind.writableBy(url, player.getUUID()) && !disc.links(held).contains(url)) {
                player.displayClientMessage(Component.translatable(disc.kind.hasPicture() ? "create_media_systems.disc.rejected"
                        : "create_media_systems.disc.rejected_track", i + 1)
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
        }
        if (playlist.isEmpty()) {
            held.remove(DiscFeature.PLAYLIST.get());
        } else {
            held.set(DiscFeature.PLAYLIST.get(), playlist);
        }
        player.displayClientMessage(Component.translatable(disc.kind.hasPicture() ? "create_media_systems.disc.saved"
                : "create_media_systems.disc.saved_tracks", playlist.size())
                .withStyle(ChatFormatting.GREEN), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetPlaylist.TYPE, SetPlaylist.CODEC,
                (payload, context) -> writePlaylist((ServerPlayer) context.player(), payload.urls()));
    }
}
