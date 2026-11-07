package org.haruyq.createmediasystems.lighting;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.SourceLinks;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StageLightBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<StageLightBlock> CODEC = simpleCodec(StageLightBlock::new);
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");

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

    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        public BlockPos console;
        private UUID consoleId;
        public int group;
        public int order;
        public int individualColor;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.STAGE_LIGHT_ENTITY.get(), pos, state);
        }

        public boolean laser() {
            return getBlockState().is(CreateMediaSystems.LASER_PROJECTOR.get());
        }

        @Nullable
        public LightingConsoleBlock.Entity controller() {
            LightingConsoleBlock.Entity owner = linkedController();
            return owner != null && owner.controller() == owner ? owner : null;
        }

        @Nullable
        public LightingConsoleBlock.Entity linkedController() {
            if (level != null && !level.isClientSide) {
                UUID before = consoleId;
                if (consoleId == null && console != null && level.isLoaded(console)
                        && level.getBlockEntity(console) instanceof LightingConsoleBlock.Entity owner) {
                    consoleId = owner.identity();
                }
                BlockPos resolved = SourceLinks.resolve(level, consoleId, console);
                if (!Objects.equals(console, resolved) || !Objects.equals(before, consoleId)) {
                    console = resolved;
                    changed();
                }
            }
            LightingConsoleBlock.Entity owner = level == null || console == null || !level.isLoaded(console) ? null
                    : level.getBlockEntity(console) instanceof LightingConsoleBlock.Entity entity ? entity : null;
            if (owner != null) {
                owner.refreshLinks();
            }
            return owner != null && owner.fixtures.contains(worldPosition) && follows(owner) ? owner : null;
        }

        boolean follows(LightingConsoleBlock.Entity owner) {
            if (consoleId != null) {
                UUID id = owner.identity();
                return id == null ? owner.getBlockPos().equals(console) : consoleId.equals(id);
            }
            return owner.getBlockPos().equals(console);
        }

        void follow(LightingConsoleBlock.Entity owner) {
            UUID id = owner.identity();
            if (!owner.getBlockPos().equals(console) || !Objects.equals(consoleId, id)) {
                console = owner.getBlockPos().immutable();
                consoleId = id;
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
            LightingConsoleBlock.Entity owner = linkedController();
            if (owner != null) {
                owner.remove(worldPosition);
            }
            console = null;
            consoleId = null;
            changed();
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            LightingConsoleBlock.Entity owner = controller();
            tooltip.add(Component.translatable(laser() ? "block.create_media_systems.laser_projector"
                    : "block.create_media_systems.stage_light").withColor(LightingText.TEXT));
            tooltip.add(console == null ? LightingText.message("unlinked")
                    : LightingText.group(owner == null ? null : owner.settings, group));
            tooltip.add(LightingText.message("fixture", LightingText.number(order + 1), LightingText.number(individualColor + 1)));
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (console != null) {
                tag.putLong("Console", console.asLong());
            }
            if (consoleId != null) {
                tag.putUUID("ConsoleId", consoleId);
            }
            tag.putInt("Group", group);
            tag.putInt("Order", order);
            tag.putInt("IndividualColor", individualColor);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            console = tag.contains("Console") ? BlockPos.of(tag.getLong("Console")) : null;
            consoleId = tag.hasUUID("ConsoleId") ? tag.getUUID("ConsoleId") : null;
            group = Mth.clamp(tag.getInt("Group"), 0, LightingSettings.GROUPS - 1);
            order = Mth.clamp(tag.getInt("Order"), 0, LightingConsoleBlock.MAX_LIGHTS - 1);
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

        public AABB getRenderBoundingBox() {
            return SableCompat.toWorld(level, new AABB(worldPosition).inflate(LightingSettings.MAX_RANGE + 1));
        }
    }
}
