package org.haruyq.createmediasystems.blocks.display;

import java.util.Optional;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Emitter;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.haruyq.createmediasystems.stream.VideoSource;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
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
public class DisplayBlock extends HorizontalDirectionalBlock implements EntityBlock, SableCompat.SilentAssemblyRemoval,
        IProxyHoveringInformation {
    private static final MapCodec<DisplayBlock> CODEC = simpleCodec(DisplayBlock::new);
    /** Sizes are in sixteenths of a block, so a screen can keep a ratio exactly. */
    public static final int UNIT = 16;
    /** Largest side of a screen, in blocks. */
    public static final int MAX_SIZE = 128;
    /** How far the panel stands out of the wall it is on; the picture is on its front. */
    public static final double DEPTH = 1 / 16.0;
    /**
     * A cell of a screen past its own block, filled only so the whole screen can be aimed at, broken and walked into;
     * the screen draws it. A cell something else fills when the screen grows over it stays as it is.
     */
    public static final BooleanProperty PART = BooleanProperty.create("part");
    /** How far the screen reaches across a cell from its left (as seen from the front), in sixteenths: all but at its right edge. */
    public static final IntegerProperty CELL_WIDTH = IntegerProperty.create("cell_width", 1, UNIT);
    /** How far the screen reaches up a cell from its bottom, in sixteenths: all but at its top edge. */
    public static final IntegerProperty CELL_HEIGHT = IntegerProperty.create("cell_height", 1, UNIT);
    /** The panel in a cell, by facing (2D data value), cell width and cell height. */
    private static final VoxelShape[][][] SHAPES = new VoxelShape[4][UNIT + 1][UNIT + 1];

    static {
        for (int w = 1; w <= UNIT; w++) {
            for (int h = 1; h <= UNIT; h++) {
                SHAPES[Direction.SOUTH.get2DDataValue()][w][h] = box(0, 0, 0, w, h, 1);
                SHAPES[Direction.WEST.get2DDataValue()][w][h] = box(15, 0, 0, 16, h, w);
                SHAPES[Direction.NORTH.get2DDataValue()][w][h] = box(16 - w, 0, 15, 16, h, 16);
                SHAPES[Direction.EAST.get2DDataValue()][w][h] = box(0, 0, 16 - w, 1, h, 16);
            }
        }
    }

    /** Parts come and go without block updates to their neighbours: a large screen has thousands. */
    private static final int PART_FLAGS = Block.UPDATE_CLIENTS;
    /** Set while a screen adds or takes away its own parts, so that a part going does not break its screen. */
    private static boolean fitting;

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
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(PART, false)
                .setValue(CELL_WIDTH, UNIT).setValue(CELL_HEIGHT, UNIT));
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
        return Component.translatable("create_media_systems.display.size", ScreenEntity.format(width / (double) UNIT),
                ScreenEntity.format(height / (double) UNIT), ratio.label());
    }

    public static int clampSize(int sixteenths) {
        return Mth.clamp(sixteenths, UNIT / 4, MAX_SIZE * UNIT);
    }

    @Override
    protected MapCodec<? extends DisplayBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, CELL_WIDTH, CELL_HEIGHT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    /**
     * Fills the cells that a screen at {@code anchor} covers at {@code width} by {@code height} sixteenths with its
     * parts, and empties those it covered at {@code fromWidth} by {@code fromHeight} but no longer does. Only
     * replaceable, dry cells are filled; only parts of the screen's kind facing its way are taken out. Every cell it
     * covers, its own block's too, gets the shape of the screen in it.
     */
    static void fitParts(Level level, BlockPos anchor, BlockState state, int fromWidth, int fromHeight, int width, int height) {
        if (level.isClientSide || state.getValue(PART)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction right = facing.getCounterClockWise();
        BlockState part = state.setValue(PART, true);
        int across = cells(width);
        int up = cells(height);
        fitting = true;
        try {
            for (int y = 0; y < Math.max(up, cells(fromHeight)); y++) {
                for (int x = 0; x < Math.max(across, cells(fromWidth)); x++) {
                    BlockPos at = anchor.relative(right, x).above(y);
                    if (!level.isLoaded(at)) {
                        continue;
                    }
                    boolean own = x == 0 && y == 0;
                    BlockState current = level.getBlockState(at);
                    boolean ours = current.is(state.getBlock()) && current.getValue(PART) != own && current.getValue(FACING) == facing;
                    if (x < across && y < up) {
                        BlockState cell = (own ? current : part).setValue(CELL_WIDTH, Math.min(width - x * UNIT, UNIT))
                                .setValue(CELL_HEIGHT, Math.min(height - y * UNIT, UNIT));
                        if (ours ? current != cell : !own && current.canBeReplaced() && level.getFluidState(at).isEmpty()) {
                            level.setBlock(at, cell, PART_FLAGS);
                        }
                    } else if (ours && !own) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), PART_FLAGS);
                    }
                }
            }
        } finally {
            fitting = false;
        }
    }

    /** The cells a side {@code sixteenths} long reaches into. */
    private static int cells(int sixteenths) {
        return (sixteenths + UNIT - 1) / UNIT;
    }

    /** The block of the screen that {@code pos} is a part of; {@code pos} itself for anything else. */
    public static BlockPos anchor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ScreenEntity screen = state.getBlock() instanceof DisplayBlock && state.getValue(PART) ? screenOf(level, pos, state) : null;
        return screen != null ? screen.getBlockPos() : pos;
    }

    /** Goggles on a part read its screen. */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        return anchor(level, pos);
    }

    /**
     * The screen that the part {@code part} at {@code pos} belongs to: the nearest one of its kind below and to the
     * left of it (as seen from the front), facing the same way, that reaches over it. Null when none does.
     */
    @Nullable
    private static ScreenEntity screenOf(Level level, BlockPos pos, BlockState part) {
        Direction facing = part.getValue(FACING);
        Direction left = facing.getClockWise();
        for (int y = 0; y < MAX_SIZE; y++) {
            for (int x = 0; x < MAX_SIZE; x++) {
                BlockPos at = pos.relative(left, x).below(y);
                if (!level.isLoaded(at)) {
                    continue;
                }
                BlockState state = level.getBlockState(at);
                if (state.is(part.getBlock()) && !state.getValue(PART) && state.getValue(FACING) == facing
                        && level.getBlockEntity(at) instanceof ScreenEntity screen
                        && x * UNIT < screen.widthUnits() && y * UNIT < screen.heightUnits()) {
                    return screen;
                }
            }
        }
        return null;
    }

    /** A part broken in creative takes its screen with it, without the screen's item. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART)) {
            ScreenEntity screen = screenOf(level, pos, state);
            if (screen != null) {
                level.destroyBlock(screen.getBlockPos(), false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** A screen that goes takes its parts with it; a part that goes breaks its screen, which drops its item. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this) && !fitting) {
            if (state.getValue(PART)) {
                ScreenEntity screen = screenOf(level, pos, state);
                if (screen != null) {
                    level.destroyBlock(screen.getBlockPos(), true);
                }
            } else if (level.getBlockEntity(pos) instanceof ScreenEntity screen) {
                fitParts(level, pos, state, screen.widthUnits(), screen.heightUnits(), 0, 0);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return defaultBlockState().setValue(FACING, face.getAxis().isHorizontal() ? face
                : context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(CELL_WIDTH)][state.getValue(CELL_HEIGHT)];
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
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) ? null : new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != DisplayFeature.DISPLAY_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l);
    }

    /**
     * The panel of a screen {@code width} by {@code height} blocks anchored at {@code anchor} facing {@code facing},
     * grown by {@code margin} around and reaching {@code front} out of the picture, in the anchor's block space.
     */
    public static AABB box(BlockPos anchor, Direction facing, double width, double height, float margin, float front) {
        Screen screen = new Screen(facing);
        Vec3 corner = Vec3.atLowerCornerOf(anchor);
        return new AABB(corner.add(screen.at(-margin, -margin, -(float) DEPTH)),
                corner.add(screen.at((float) width + margin, (float) height + margin, front)));
    }

    /** A display: the Blu-ray player it is linked to, whose video it shows and whose sound it plays. */
    public static class Entity extends ScreenEntity implements Emitter {
        private Link source = Link.NONE;
        /** Its Blu-ray player's lighting in percent, kept here so every client has it (see VideoPlayerBlock). */
        private int lighting = 100;
        /** Its source's still picture (see VideoSource#image), kept here so every client has it; empty for none. */
        private String image = "";

        public Entity(BlockPos pos, BlockState state) {
            super(DisplayFeature.DISPLAY_ENTITY.get(), pos, state);
        }

        public Optional<BlockPos> source() {
            return Optional.ofNullable(source.pos());
        }

        /** How brightly it lights its surroundings as it plays, 0..1, as its Blu-ray player is set. */
        public float lighting() {
            return lighting / 100f;
        }

        /** The link of the still picture it shows while it shows no video; empty for none. */
        public String image() {
            return image;
        }

        /** Links it to the Blu-ray player at {@code source}, or unlinks it; its viewers start over. */
        public void link(@Nullable BlockPos source) {
            this.source = Link.to(level, source);
            changed();
        }

        /** The middle of its picture, where its sound comes from, in its block space. */
        public Vec3 center() {
            return Vec3.atLowerCornerOf(worldPosition)
                    .add(new Screen(facing()).at((float) width() / 2, (float) height() / 2, 0));
        }

        @Override
        public boolean occludes() {
            return true;
        }

        private void tick(ServerLevel level) {
            if (Streams.relayDue(level)) {
                relay(level);
            }
        }

        /** Plays its Blu-ray player's video's sound from the screen's middle, unless a speaker plays it. */
        @Override
        public void relay(ServerLevel level) {
            followSource(level);
            Optional<BlockPos> linked = source();
            VideoSource videoPlayer = linked.filter(level::isLoaded).map(level::getBlockEntity)
                    .orElse(null) instanceof VideoSource found ? found : null;
            if (videoPlayer != null && Math.round(videoPlayer.lighting() * 100) != lighting) {
                lighting = Math.round(videoPlayer.lighting() * 100);
                changed();
            }
            // Every client fetches the image itself: a link the server no longer allows reaches none of them.
            String shown = videoPlayer != null && UrlPolicy.isAllowed(videoPlayer.image()) ? videoPlayer.image() : "";
            if (!shown.equals(image)) {
                image = shown;
                changed();
            }
            Streams.relay(level, this, linked, center(), Optional.empty(), Streams.Channel.MIX, videoPlayer == null ? 0 : 1);
        }

        /** Its sound is the speakers' to play, where its Blu-ray player has any. */
        @Override
        public boolean givesWay() {
            return true;
        }

        /** Its source as it is now, where Sable moved it; resent if that changed. */
        public void followSource(ServerLevel level) {
            Link followed = source.follow(level);
            if (!followed.equals(source)) {
                source = followed;
                changed();
            }
        }

        @Override
        protected Component shows() {
            return source().map(at -> Component.translatable("create_media_systems.display.linked", at.getX(), at.getY(), at.getZ()))
                    .orElseGet(() -> Component.translatable("create_media_systems.display.unlinked"));
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && !level.isClientSide) {
                Streams.stop(this);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            source.save(tag, "Source", "SourceId");
            tag.putInt("Lighting", lighting);
            if (!image.isEmpty()) {
                tag.putString("SourceImage", image);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            source = Link.load(tag, "Source", "SourceId");
            lighting = tag.contains("Lighting") ? Mth.clamp(tag.getInt("Lighting"), 0, 100) : 100;
            image = tag.getString("SourceImage");
        }
    }
}
