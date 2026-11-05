package org.haruyq.createmediasystems.display;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.TextPromptScreen;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A picture on a wall, like WaterMedia's images in WaterFrames' frames: a display (same size and quill) that shows
 * an image from a link instead of a Blu-ray player's video, see-through where the image is. Right-click it with a
 * wrench to change the link; the display quill's right-click takes its frame (the black panel) away or puts it back.
 */
public class ImageViewerBlock extends DisplayBlock {
    private static final MapCodec<ImageViewerBlock> CODEC = simpleCodec(ImageViewerBlock::new);
    /** With its frame, the picture sits on a black panel; without, only the picture shows. */
    public static final BooleanProperty FRAMED = BooleanProperty.create("framed");

    public ImageViewerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FRAMED, true));
    }

    @Override
    protected MapCodec<ImageViewerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FRAMED);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Tags.Items.TOOLS_WRENCH)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide && level.getBlockEntity(pos) instanceof Entity viewer) {
            TextPromptScreen.open("create_media_systems.screen.image", viewer.image(), UrlPolicy.MAX_LENGTH,
                    value -> PacketDistributor.sendToServer(new SetImage(pos, value)));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Nothing to relay: its picture is fetched by each computer that shows it. */
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    /** Client → server: a wrench set the link of the image viewer at {@code viewer}; empty clears it. */
    public record SetImage(BlockPos viewer, String url) implements CustomPacketPayload {
        public static final Type<SetImage> TYPE = new Type<>(CreateMediaSystems.id("set_image"));
        public static final StreamCodec<ByteBuf, SetImage> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetImage::viewer,
                StreamPackets.URL, SetImage::url,
                SetImage::new);

        @Override
        public Type<SetImage> type() {
            return TYPE;
        }
    }

    /** A wrench holder set an image viewer's link (empty clears it); only to a host the server allows. */
    static void setImage(ServerPlayer player, SetImage payload) {
        BlockPos pos = payload.viewer();
        DisplayBlock.Entity viewer = Reach.blockEntity(player, pos, Reach.HAND, DisplayBlock.Entity.class);
        if (!player.isHolding(stack -> stack.is(Tags.Items.TOOLS_WRENCH)) || viewer == null || !viewer.isImageViewer()) {
            return;
        }
        String url = payload.url().trim();
        if (!url.isEmpty() && !ServerConfig.isAllowed(url)) {
            player.displayClientMessage(Component.translatable("create_media_systems.url.rejected").withStyle(ChatFormatting.RED), true);
            return;
        }
        viewer.setImage(url);
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(SetImage.TYPE, SetImage.CODEC,
                (payload, context) -> setImage((ServerPlayer) context.player(), payload));
    }
}
