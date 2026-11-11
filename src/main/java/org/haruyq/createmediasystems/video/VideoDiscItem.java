package org.haruyq.createmediasystems.video;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.stream.StreamPackets;

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

/**
 * A playlist of links for a Blu-ray player or a hi-fi stack (not a jukebox): a DVD or a Blu-ray, which differ in how
 * many videos they hold and how sharp they play, or a CD or a cassette, which have no picture and play in a hi-fi
 * stack only. Use in the air to edit the list.
 */
public class VideoDiscItem extends Item {
    /** Most links any disc holds (the network's limit). */
    public static final int MAX_ENTRIES = 64;
    private static final int SHOWN_ENTRIES = 5;

    /** How many links it holds, and the tallest picture (pixels) it plays: 0 for sound alone. */
    public final int entries;
    public final int maxHeight;

    public VideoDiscItem(Properties properties, int entries, int maxHeight) {
        super(properties);
        if (entries > MAX_ENTRIES) {
            throw new IllegalArgumentException("A disc holds at most " + MAX_ENTRIES + " links");
        }
        this.entries = entries;
        this.maxHeight = maxHeight;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            PlaylistScreen.open(stack.getOrDefault(VideoFeature.PLAYLIST.get(), List.of()), entries);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<String> playlist = stack.getOrDefault(VideoFeature.PLAYLIST.get(), List.of());
        if (playlist.isEmpty()) {
            tooltip.add(Component.translatable("create_media_systems.url.unset").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable(maxHeight > 0 ? "create_media_systems.disc.entries" : "create_media_systems.disc.tracks", playlist.size()).withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < Math.min(playlist.size(), SHOWN_ENTRIES); i++) {
            tooltip.add(Component.literal((i + 1) + ". " + playlist.get(i)).withStyle(ChatFormatting.GRAY));
        }
        if (playlist.size() > SHOWN_ENTRIES) {
            tooltip.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Client → server: write {@code urls} onto the disc in the main hand, as its playlist. */
    public record SetPlaylist(List<String> urls) implements CustomPacketPayload {
        public static final Type<SetPlaylist> TYPE = new Type<>(CreateMediaSystems.id("set_playlist"));
        public static final StreamCodec<ByteBuf, SetPlaylist> CODEC = StreamPackets.URL
                .apply(ByteBufCodecs.list(VideoDiscItem.MAX_ENTRIES)).map(SetPlaylist::new, SetPlaylist::urls);

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
        if (!(held.getItem() instanceof VideoDiscItem disc)) {
            return;
        }
        List<String> playlist = urls.stream().map(String::trim).filter(url -> !url.isEmpty())
                .limit(disc.entries).toList();
        for (int i = 0; i < playlist.size(); i++) {
            var physical = DiscUrl.parse(playlist.get(i));
            if (!ServerConfig.isAllowed(playlist.get(i)) || physical != null && !physical.owner().equals(player.getUUID())
                    && !held.getOrDefault(VideoFeature.PLAYLIST.get(), List.of()).contains(playlist.get(i))) {
                player.displayClientMessage(Component.translatable(disc.maxHeight > 0 ? "create_media_systems.disc.rejected"
                        : "create_media_systems.disc.rejected_track", i + 1)
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
        }
        if (playlist.isEmpty()) {
            held.remove(VideoFeature.PLAYLIST.get());
        } else {
            held.set(VideoFeature.PLAYLIST.get(), playlist);
        }
        player.displayClientMessage(Component.translatable(disc.maxHeight > 0 ? "create_media_systems.disc.saved"
                : "create_media_systems.disc.saved_tracks", playlist.size())
                .withStyle(ChatFormatting.GREEN), true);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetPlaylist.TYPE, SetPlaylist.CODEC,
                (payload, context) -> writePlaylist((ServerPlayer) context.player(), payload.urls()));
    }
}
