package org.haruyq.createmediasystems.audio;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.TextPromptScreen;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Jukebox-playable disc whose audio is the URL stored on it. Use in the air to edit the URL. */
public class CustomDiscItem extends Item {
    public CustomDiscItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            TextPromptScreen.open("create_media_systems.screen.url", stack.getOrDefault(AudioFeature.URL.get(), ""),
                    UrlPolicy.MAX_LENGTH, value -> PacketDistributor.sendToServer(new SetDiscUrl(value)));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String url = stack.get(AudioFeature.URL.get());
        String title = stack.get(AudioFeature.TITLE.get());
        if (title != null) {
            tooltip.add(Component.literal(title).withStyle(ChatFormatting.WHITE));
        }
        tooltip.add(url == null
                ? Component.translatable("create_media_systems.url.unset").withStyle(ChatFormatting.DARK_GRAY)
                : Component.literal(url).withStyle(ChatFormatting.GRAY));
    }

    /** Client → server: write {@code url} onto the custom disc in the main hand. */
    public record SetDiscUrl(String url) implements CustomPacketPayload {
        public static final Type<SetDiscUrl> TYPE = new Type<>(CreateMediaSystems.id("set_disc_url"));
        public static final StreamCodec<ByteBuf, SetDiscUrl> CODEC =
                StreamPackets.URL.map(SetDiscUrl::new, SetDiscUrl::url);

        @Override
        public Type<SetDiscUrl> type() {
            return TYPE;
        }
    }

    /**
     * Writes {@code url} onto the custom disc in the player's hand, but only once the API confirms it is playable
     * (its metadata exists); otherwise the disc is left as it was and the player is told why.
     */
    static void writeDisc(ServerPlayer player, String url) {
        if (!player.getMainHandItem().is(AudioFeature.CUSTOM_DISC.get())) {
            return;
        }
        if (!ServerConfig.isAllowed(url)) {
            player.displayClientMessage(Component.translatable("create_media_systems.url.rejected").withStyle(ChatFormatting.RED), true);
            return;
        }
        player.displayClientMessage(Component.translatable("create_media_systems.url.checking"), true);
        Streams.info(url).whenComplete((info, error) -> player.server.execute(() -> {
            if (error != null) {
                player.displayClientMessage(Component.translatable("create_media_systems.url.not_found", UrlPolicy.reason(error))
                        .withStyle(ChatFormatting.RED), false);
                return;
            }
            ItemStack held = player.getMainHandItem();
            if (!held.is(AudioFeature.CUSTOM_DISC.get())) {
                return; // switched items while the API was checking
            }
            held.set(AudioFeature.URL.get(), url);
            if (info.title() == null) {
                held.remove(AudioFeature.TITLE.get());
            } else {
                held.set(AudioFeature.TITLE.get(), info.title());
            }
            player.displayClientMessage(Component.translatable("create_media_systems.url.saved",
                    info.title() == null ? url : info.title()).withStyle(ChatFormatting.GREEN), true);
        }));
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetDiscUrl.TYPE, SetDiscUrl.CODEC,
                (payload, context) -> writeDisc((ServerPlayer) context.player(), payload.url().trim()));
    }
}
