package org.haruyq.createmediasystems.lighting;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.Link;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StageLightBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<StageLightBlock> CODEC = simpleCodec(StageLightBlock::new);
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");
    private static final Map<String, Component> FAMILY_NAMES = new ConcurrentHashMap<>();

    /** Names {@code family} (StageLightBlock.Entity.family) as players read it, as a group of it is told apart. */
    public static void nameFamily(String family, Component name) {
        FAMILY_NAMES.put(family, name);
    }

    /** {@code family}'s name, as named (nameFamily); its id if not. */
    public static Component familyName(String family) {
        Component name = FAMILY_NAMES.get(family);
        if (name != null) {
            return name;
        }
        return family.equals(Entity.DEFAULT_FAMILY) ? Component.translatable("create_media_systems.lighting.family")
                : Component.literal(family);
    }

    public StageLightBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(HANGING, false));
    }

    @Override
    protected MapCodec<StageLightBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HANGING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HANGING, context.getClickedFace() == Direction.DOWN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return box(2, 0, 2, 14, 16, 14);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && !movedByPiston
                && level.getBlockEntity(pos) instanceof Entity light) {
            light.unlink();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * A fixture the lighting console drives. Other mods' fixtures extend it with their own block entity type (see
     * the protected constructor): the console, the lighting link and the fixture controls take any subclass, and
     * {@link LightingIllumination#render} lights the faces its beam reaches. They draw themselves.
     */
    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        /** Its console; the console keeps its group and order (see LightingConsoleBlock.Entity.fixtures). */
        private Link console = Link.NONE;
        public int individualColor;

        public Entity(BlockPos pos, BlockState state) {
            this(LightingFeature.STAGE_LIGHT_ENTITY.get(), pos, state);
        }

        /** For other mods' fixtures, each under its own block entity type. */
        protected Entity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        public boolean laser() {
            return getBlockState().is(LightingFeature.LASER_PROJECTOR.get());
        }

        /** The family stage lights and laser projectors share: the console's own page runs them. */
        public static final String DEFAULT_FAMILY = CreateMediaSystems.MOD_ID;

        /**
         * Which console page runs this fixture (LightingConsolePage, registered for this family): fixtures of one
         * family share a page, so a console group holds fixtures of one family only. Another mod's fixtures name
         * their own, usually their mod id.
         */
        public String family() {
            return DEFAULT_FAMILY;
        }

        /** The fixture's name, as its goggle tooltip heads it. */
        public Component fixtureName() {
            return getBlockState().getBlock().getName();
        }

        /** Where its console was last seen; null if it has none. */
        @Nullable
        public BlockPos console() {
            return console.pos();
        }

        /** Its console, if that is formed and runs it, as this side has it. */
        @Nullable
        public LightingConsoleBlock.Entity controller() {
            LightingConsoleBlock.Entity owner = linked();
            return owner != null && owner.controller() == owner ? owner : null;
        }

        /** The console that has it among its fixtures, as this side has it, formed or not. */
        @Nullable
        LightingConsoleBlock.Entity linked() {
            BlockPos at = console.pos();
            return level != null && at != null && level.isLoaded(at)
                    && level.getBlockEntity(at) instanceof LightingConsoleBlock.Entity owner && owner.links(worldPosition)
                    ? owner : null;
        }

        /** Its group on its console; 0 without one. */
        public int group() {
            LightingConsoleBlock.Entity owner = linked();
            return owner == null ? 0 : owner.group(worldPosition);
        }

        /** Its place among its group's fixtures, from 0. */
        public int order() {
            LightingConsoleBlock.Entity owner = linked();
            return owner == null ? 0 : owner.order(worldPosition);
        }

        /** Whether it points at {@code owner}: by identity, or by position if either has none (saved before identities). */
        boolean follows(LightingConsoleBlock.Entity owner) {
            UUID id = owner.identity();
            return console.id() != null && id != null ? console.id().equals(id) : owner.getBlockPos().equals(console.pos());
        }

        /** Points it at the console {@code owner} links to, as that console is now. */
        void follow(Link owner) {
            if (!owner.equals(console)) {
                console = owner;
                changed();
            }
        }

        public void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        public void unlink() {
            if (level != null) {
                console = console.follow(level);
            }
            LightingConsoleBlock.Entity owner = linked();
            if (owner != null) {
                owner.remove(worldPosition);
            }
            console = Link.NONE;
            changed();
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            LightingConsoleBlock.Entity owner = controller();
            tooltip.add(fixtureName().copy().withColor(LightingText.TEXT));
            tooltip.add(console.pos() == null ? LightingText.message("unlinked")
                    : LightingText.group(owner == null ? null : owner.settings, group()));
            tooltip.add(LightingText.message("fixture", LightingText.number(order() + 1), LightingText.number(individualColor + 1)));
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            console.save(tag, "Console", "ConsoleId");
            tag.putInt("IndividualColor", individualColor);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            console = Link.load(tag, "Console", "ConsoleId");
            individualColor = Mth.clamp(tag.getInt("IndividualColor"), 0, LightingSettings.COLORS - 1);
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}
