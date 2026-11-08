package org.haruyq.createmediasystems.display;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.lighting.LightingScreens;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.SourceLinks;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;

/**
 * A screen on a wall, like WaterFrames' frames: it shows its linked Blu-ray player's video over its size, from the block
 * to the right and up (as seen from the front), and plays its sound from the screen's middle, like a jukebox, while
 * no speaker plays that Blu-ray player. The display quill links it and sets its size (see DisplayQuillHandler).
 */
public class DisplayBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<DisplayBlock> CODEC = simpleCodec(DisplayBlock::new);
    /** Sizes are in sixteenths of a block, so a screen can keep a ratio exactly. */
    public static final int UNIT = 16;
    /** Largest side of a screen, in blocks. */
    public static final int MAX_SIZE = 128;
    /** How far the panel stands out of the wall it is on; the picture is on its front. */
    public static final double DEPTH = 1 / 16.0;
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.NORTH, box(0, 0, 15, 16, 16, 16),
            Direction.SOUTH, box(0, 0, 0, 16, 16, 1),
            Direction.EAST, box(0, 0, 0, 1, 16, 16),
            Direction.WEST, box(15, 0, 0, 16, 16, 16));

    /** How a screen's height follows its width (see DisplayQuillHandler). */
    public enum Ratio {
        FREE(0), WIDE(16 / 9.0), STANDARD(4 / 3.0), CINEMA(21 / 9.0), SQUARE(1), VIDEO(0);

        /** Width over height; 0 for none (FREE) or the video's own (VIDEO). */
        public final double value;

        Ratio(double value) {
            this.value = value;
        }

        public Component label() {
            return Component.translatable("create_media_systems.display.ratio." + name().toLowerCase(java.util.Locale.ROOT));
        }

        public Ratio next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Ratio byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    public DisplayBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    /**
     * The front of the panel of a display facing {@code facing}, in its block's own coordinates, from its lower left
     * corner (as seen from the front): {@code at(x, y, lift)} is {@code x} blocks right, {@code y} up and {@code lift}
     * out of it.
     */
    public record Screen(Vec3 origin, Vec3 right, Vec3 out) {
        public Screen(Direction facing) {
            this(new Vec3(0.5, 0, 0.5)
                            .subtract(Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal()).scale(0.5))
                            .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(DEPTH - 0.5)),
                    Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal()),
                    Vec3.atLowerCornerOf(facing.getNormal()));
        }

        public Vec3 at(float x, float y, float lift) {
            return origin.add(right.scale(x)).add(0, y, 0).add(out.scale(lift));
        }
    }

    /** "W × H, ratio" for a screen {@code width} by {@code height} sixteenths. */
    public static Component sizeText(int width, int height, Ratio ratio) {
        return Component.translatable("create_media_systems.display.size", Entity.format(width / (double) UNIT),
                Entity.format(height / (double) UNIT), ratio.label());
    }

    /** A side, in sixteenths, within what a screen may be. */
    public static int clampSize(int sixteenths) {
        return Mth.clamp(sixteenths, UNIT / 4, MAX_SIZE * UNIT);
    }

    @Override
    protected MapCodec<? extends DisplayBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return defaultBlockState().setValue(FACING, face.getAxis().isHorizontal() ? face
                : context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public boolean hasDynamicLightEmission(BlockState state) {
        return true;
    }

    /** As bright as its picture where it plays: set on each computer that shows it (see DisplayRenderer); 0 on servers. */
    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        AuxiliaryLightManager lights = level.getAuxLightManager(pos);
        return lights == null ? 0 : lights.getLightAt(pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateMediaSystems.DISPLAY_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l);
    }

    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        private final SourceLinks.Link source = new SourceLinks.Link();
        private int width = UNIT;
        private int height = UNIT;
        private Ratio ratio = Ratio.WIDE;
        /** Its Blu-ray player's lighting in percent, kept here so every client has it (see VideoPlayerBlock). */
        private int lighting = 100;
        /** An image viewer's picture link (see ImageViewerBlock); empty for a display. */
        private String image = "";

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.DISPLAY_ENTITY.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            LightingScreens.add(this);
        }

        public Optional<BlockPos> source() {
            return source.get(level, this::changed);
        }

        public double width() {
            return width / (double) UNIT;
        }

        public double height() {
            return height / (double) UNIT;
        }

        public int widthUnits() {
            return width;
        }

        public int heightUnits() {
            return height;
        }

        public Ratio ratio() {
            return ratio;
        }

        /** How brightly it lights its surroundings as it plays, 0..1, as its Blu-ray player is set. */
        public float lighting() {
            return lighting / 100f;
        }

        public String image() {
            return image;
        }

        void setImage(String image) {
            this.image = image;
            changed();
        }

        public Direction facing() {
            return getBlockState().getValue(FACING);
        }

        /** Links it to the Blu-ray player at {@code source}, or unlinks it; its viewers start over. */
        void link(@Nullable BlockPos source) {
            this.source.set(level, source);
            changed();
        }

        /** A new size (sixteenths) and ratio; its viewers start over, to hear it from the screen's new middle. */
        public void resize(int width, int height, Ratio ratio) {
            this.width = clampSize(width);
            this.height = clampSize(height);
            this.ratio = ratio;
            changed();
        }

        private void changed() {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        /** The middle of its picture, where its sound comes from, in its block space. */
        private Vec3 center() {
            return Vec3.atLowerCornerOf(worldPosition)
                    .add(new Screen(facing()).at((float) width() / 2, (float) height() / 2, 0));
        }

        /** Its panel, grown by {@code margin} around and reaching {@code front} out of the picture, in its block space. */
        public AABB box(float margin, float front) {
            Screen screen = new Screen(facing());
            Vec3 corner = Vec3.atLowerCornerOf(worldPosition);
            return new AABB(corner.add(screen.at(-margin, -margin, -(float) DEPTH)),
                    corner.add(screen.at((float) width() + margin, (float) height() + margin, front)));
        }

        public boolean isImageViewer() {
            return getBlockState().getBlock() instanceof ImageViewerBlock;
        }

        /** Plays its Blu-ray player's video's sound from the screen's middle, unless a speaker plays it. */
        private void tick(ServerLevel level) {
            if (!Streams.relayDue(level)) {
                return;
            }
            Optional<BlockPos> linked = source();
            VideoPlayerBlock.Entity videoPlayer = linked.filter(level::isLoaded).map(level::getBlockEntity)
                    .orElse(null) instanceof VideoPlayerBlock.Entity found ? found : null;
            if (videoPlayer != null && Math.round(videoPlayer.lighting() * 100) != lighting) {
                lighting = Math.round(videoPlayer.lighting() * 100);
                changed();
            }
            boolean speakers = linked.isPresent() && Streams.relayedBy(level, linked.get(), SpeakerBlock.Entity.class);
            Streams.relay(level, this, linked, center(), Optional.empty(), Streams.Channel.MIX,
                    videoPlayer == null || speakers ? 0 : 1);
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            tooltip.add(Component.literal("     ").append(isImageViewer()
                    ? image.isEmpty() ? Component.translatable("create_media_systems.image_viewer.empty")
                    : Component.literal(image.length() > 48 ? image.substring(0, 47) + "…" : image)
                    : source().map(at -> Component.translatable("create_media_systems.display.linked", at.getX(), at.getY(), at.getZ()))
                    .orElseGet(() -> Component.translatable("create_media_systems.display.unlinked")))
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("     ").append(sizeText(width, height, ratio)).withStyle(ChatFormatting.GOLD));
            return true;
        }

        /** Blocks, to a sixteenth, without trailing zeros. */
        public static String format(double blocks) {
            return blocks == Math.rint(blocks) ? Long.toString(Math.round(blocks))
                    : String.format(java.util.Locale.ROOT, "%.2f", blocks).replaceAll("0+$", "");
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            LightingScreens.remove(this);
            if (level != null && !level.isClientSide) {
                Streams.stop(this);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            source.save(level, tag);
            tag.putInt("Width16", width);
            tag.putInt("Height16", height);
            tag.putInt("Ratio", ratio.ordinal());
            tag.putInt("Lighting", lighting);
            if (!image.isEmpty()) {
                tag.putString("Image", image);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            source.load(tag);
            width = tag.contains("Width16") ? clampSize(tag.getInt("Width16")) : UNIT;
            height = tag.contains("Height16") ? clampSize(tag.getInt("Height16")) : UNIT;
            ratio = tag.contains("Ratio") ? Ratio.byId(tag.getInt("Ratio")) : Ratio.WIDE;
            lighting = tag.contains("Lighting") ? Mth.clamp(tag.getInt("Lighting"), 0, 100) : 100;
            image = tag.getString("Image");
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveCustomOnly(registries);
            // Every client fetches the image itself: a link the server no longer allows reaches none of them.
            if (!ServerConfig.isAllowed(image)) {
                tag.remove("Image");
            }
            return tag;
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}
