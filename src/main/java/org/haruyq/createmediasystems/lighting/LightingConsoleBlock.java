package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class LightingConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<LightingConsoleBlock> CODEC = simpleCodec(LightingConsoleBlock::new);
    public static final IntegerProperty COLUMN = IntegerProperty.create("column", 0, 3);
    public static final BooleanProperty BACK = BooleanProperty.create("back");
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    public static final int MAX_LIGHTS = 64;
    private static final List<Consumer<Entity>> TICKERS = new CopyOnWriteArrayList<>();

    /**
     * Runs {@code ticker} on the server each tick for every formed console, on its root (the controller): for
     * another mod's settings that move with time, kept in the console's extras (a fade, a timer). It runs while the
     * console is loaded, whether or not its fixtures are.
     */
    public static void onTick(Consumer<Entity> ticker) {
        TICKERS.add(ticker);
    }

    public LightingConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(COLUMN, 0)
                .setValue(BACK, false).setValue(FORMED, false));
    }

    @Override
    protected MapCodec<LightingConsoleBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLUMN, BACK, FORMED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        double height = state.getValue(BACK) ? 1 : 0.9;
        return Shapes.box(0, 0, 0, 1, height, 1);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateMediaSystems.LIGHTING_CONSOLE_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!state.is(oldState.getBlock())) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            recheck(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Checks the structure the part at {@code pos} belongs to next tick, rather than at the next second's poll. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof Entity entity) {
            entity.updateStructure();
        }
    }

    /** After a part of a formed console ({@code state}, at {@code pos}) left, has every desk of it check next tick. */
    static void recheck(Level level, BlockPos pos, BlockState state) {
        if (!state.getValue(FORMED)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        boolean monitor = state.getBlock() instanceof LightingMonitorBlock;
        BlockPos origin = at(monitor ? pos.below() : pos, facing, -state.getValue(COLUMN), 0,
                monitor || state.getValue(BACK) ? -1 : 0);
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos part = at(origin, facing, x, 0, z);
                if (level.getBlockState(part).getBlock() instanceof LightingConsoleBlock desk) {
                    level.scheduleTick(part, desk, 1);
                }
            }
        }
    }

    public static BlockPos at(BlockPos origin, Direction facing, int x, int y, int z) {
        return origin.relative(facing.getCounterClockWise(), x).relative(facing.getOpposite(), z).above(y);
    }

    public static Vec3 toLocal(BlockPos origin, Direction facing, Vec3 point) {
        Vec3 offset = point.subtract(Vec3.atCenterOf(origin));
        Direction right = facing.getCounterClockWise();
        Direction back = facing.getOpposite();
        return new Vec3(0.5 + offset.x * right.getStepX() + offset.z * right.getStepZ(),
                point.y - origin.getY(), 0.5 + offset.x * back.getStepX() + offset.z * back.getStepZ());
    }

    @Nullable
    public static Entity find(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        if (level.getBlockState(pos).getBlock() instanceof LightingMonitorBlock) {
            pos = pos.below();
        }
        return level.getBlockEntity(pos) instanceof Entity entity ? entity.controller() : null;
    }

    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        public final LightingSettings settings = new LightingSettings();
        public final List<BlockPos> fixtures = new ArrayList<>();
        private final Map<BlockPos, Integer> assignments = new HashMap<>();
        private final Map<BlockPos, UUID> fixtureIds = new HashMap<>();
        /** Each fixture's family (StageLightBlock.Entity.family), kept for fixtures not loaded. */
        private final Map<BlockPos, String> families = new HashMap<>();
        private UUID identity;
        private UUID audioId;
        private UUID videoId;
        private BlockPos resolvedAudio;
        private BlockPos resolvedVideo;
        private boolean refreshingLinks;
        public final CompoundTag[] presets = new CompoundTag[6];
        /** Other mods' settings for this console (see extras), by their mod id. */
        private final CompoundTag extras = new CompoundTag();
        public boolean formed;
        public BlockPos audioSource;
        public BlockPos videoSource;
        public float audioLevel;
        public float bass;
        public float mid;
        public float high;
        public float pulse;
        public float bpm = 120;
        public double motionPhase;
        public int videoLeft = 0x20C0FF;
        public int videoRight = 0xA040FF;
        public boolean hasAudio;
        public boolean hasVideo;
        public long signalAt = -100;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.LIGHTING_CONSOLE_ENTITY.get(), pos, state);
        }

        @Nullable
        /**
         * {@code owner}'s own settings for this console (a LightingConsolePage's, say): saved with the console, and
         * sent to players with it on {@link #changed}. Changed on the server; the client's copy is replaced on sync.
         */
        public CompoundTag extras(String owner) {
            if (!extras.contains(owner, Tag.TAG_COMPOUND)) {
                extras.put(owner, new CompoundTag());
            }
            return extras.getCompound(owner);
        }

        public UUID identity() {
            if (level != null && !level.isClientSide) {
                identity = SourceLinks.identifyBlock(level, worldPosition);
            }
            return identity;
        }

        public void setAudioSource(@Nullable BlockPos pos) {
            audioSource = pos == null ? null : pos.immutable();
            audioId = level == null ? null : SourceLinks.identify(level, audioSource);
            resolvedAudio = audioSource;
            hasAudio = false;
        }

        public void setVideoSource(@Nullable BlockPos pos) {
            videoSource = pos == null ? null : pos.immutable();
            videoId = level == null ? null : SourceLinks.identify(level, videoSource);
            resolvedVideo = videoSource;
            hasVideo = false;
        }

        public void refreshLinks() {
            if (level == null || level.isClientSide || refreshingLinks || !isRoot()) {
                return;
            }
            refreshingLinks = true;
            boolean updated = false;
            try {
                UUID before = identity;
                identity();
                updated = !Objects.equals(before, identity);
                List<BlockPos> positions = new ArrayList<>();
                Map<BlockPos, Integer> groups = new HashMap<>();
                Map<BlockPos, UUID> ids = new HashMap<>();
                Map<BlockPos, String> kinds = new HashMap<>();
                for (BlockPos previous : fixtures) {
                    UUID id = fixtureIds.get(previous);
                    if (id == null && level.isLoaded(previous)
                            && level.getBlockEntity(previous) instanceof StageLightBlock.Entity light && light.follows(this)) {
                        id = SourceLinks.identifyBlock(level, previous);
                        updated |= id != null;
                    }
                    BlockPos pos = SourceLinks.resolve(level, id, previous);
                    if (pos == null || positions.contains(pos)) {
                        updated = true;
                        continue;
                    }
                    if (level.isLoaded(pos)) {
                        if (!(level.getBlockEntity(pos) instanceof StageLightBlock.Entity light) || !light.follows(this)) {
                            updated = true;
                            continue;
                        }
                        light.follow(this);
                        updated |= !light.family().equals(families.get(previous));
                        kinds.put(pos, light.family());
                    } else {
                        kinds.put(pos, families.getOrDefault(previous, StageLightBlock.Entity.DEFAULT_FAMILY));
                    }
                    positions.add(pos);
                    groups.put(pos, assignments.getOrDefault(previous, 0));
                    if (id != null) {
                        ids.put(pos, id);
                    }
                    updated |= !pos.equals(previous);
                }
                fixtures.clear();
                fixtures.addAll(positions);
                assignments.clear();
                assignments.putAll(groups);
                fixtureIds.clear();
                fixtureIds.putAll(ids);
                families.clear();
                families.putAll(kinds);
                BlockPos previousAudio = audioSource;
                BlockPos previousVideo = videoSource;
                if (!Objects.equals(audioSource, resolvedAudio)) {
                    setAudioSource(audioSource);
                }
                if (!Objects.equals(videoSource, resolvedVideo)) {
                    setVideoSource(videoSource);
                }
                if (audioId == null) {
                    audioId = SourceLinks.identify(level, audioSource);
                    updated |= audioId != null;
                }
                if (videoId == null) {
                    videoId = SourceLinks.identify(level, videoSource);
                    updated |= videoId != null;
                }
                audioSource = SourceLinks.resolve(level, audioId, audioSource);
                videoSource = SourceLinks.resolve(level, videoId, videoSource);
                resolvedAudio = audioSource;
                resolvedVideo = videoSource;
                if (!Objects.equals(previousAudio, audioSource)) {
                    hasAudio = false;
                    updated = true;
                }
                if (!Objects.equals(previousVideo, videoSource)) {
                    hasVideo = false;
                    updated = true;
                }
                if (updated) {
                    reorder();
                    changed();
                }
            } finally {
                refreshingLinks = false;
            }
        }

        @Nullable
        public Entity controller() {
            if (level == null || !getBlockState().getValue(FORMED)) {
                return null;
            }
            BlockState state = getBlockState();
            BlockPos origin = at(worldPosition, state.getValue(FACING), -state.getValue(COLUMN), 0,
                    state.getValue(BACK) ? -1 : 0);
            return level.isLoaded(origin) && level.getBlockEntity(origin) instanceof Entity entity && entity.formed
                    ? entity : null;
        }

        public boolean isRoot() {
            return getBlockState().getValue(COLUMN) == 0 && !getBlockState().getValue(BACK);
        }

        private void tick() {
            if (level.getGameTime() % 20 == 0) {
                if (isRoot()) {
                    refreshLinks();
                }
                updateStructure();
                if (formed && isRoot()) {
                    boolean removed = fixtures.removeIf(pos -> {
                        if (level.isLoaded(pos) && (!(level.getBlockEntity(pos) instanceof StageLightBlock.Entity light)
                                || !light.follows(this))) {
                            assignments.remove(pos);
                            fixtureIds.remove(pos);
                            families.remove(pos);
                            return true;
                        }
                        return false;
                    });
                    reorder();
                    if (removed) {
                        changed();
                    }
                }
            }
            if (formed && isRoot()) {
                AnalysisCoordinator.use(this);
                for (Consumer<Entity> ticker : TICKERS) {
                    ticker.accept(this);
                }
            }
        }

        public void updateStructure() {
            if (level == null || level.isClientSide) {
                return;
            }
            Entity current = controller();
            if (current != null && current != this) {
                return;
            }
            Direction facing = getBlockState().getValue(FACING);
            if (getBlockState().getValue(FORMED)) {
                BlockPos origin = at(worldPosition, facing, -getBlockState().getValue(COLUMN), 0,
                        getBlockState().getValue(BACK) ? -1 : 0);
                for (int x = 0; x < 4; x++) {
                    for (int z = 0; z < 2; z++) {
                        if (!level.isLoaded(at(origin, facing, x, 0, z))
                                || !level.isLoaded(at(origin, facing, x, 1, 1))) {
                            return;
                        }
                    }
                }
                if (current == null) {
                    form(origin, facing, false);
                }
            }
            if (formed && isRoot()) {
                if (!complete(worldPosition, facing)) {
                    form(worldPosition, facing, false);
                }
                return;
            }
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 4; x++) {
                    BlockPos origin = at(worldPosition, facing, -x, 0, -z);
                    if (complete(origin, facing)) {
                        form(origin, facing, true);
                        return;
                    }
                }
            }
            if (getBlockState().getValue(FORMED)) {
                formed = false;
                level.setBlock(worldPosition, getBlockState().setValue(FORMED, false), Block.UPDATE_CLIENTS);
                changed();
            }
        }

        private boolean complete(BlockPos origin, Direction facing) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos pos = at(origin, facing, x, 0, z);
                    if (!matches(pos, facing, false)) {
                        return false;
                    }
                    Entity other = (Entity) level.getBlockEntity(pos);
                    Entity owner = other == null ? null : other.controller();
                    if (owner != null && !owner.getBlockPos().equals(origin)) {
                        return false;
                    }
                }
                if (!matches(at(origin, facing, x, 1, 1), facing, true)) {
                    return false;
                }
            }
            return true;
        }

        private boolean matches(BlockPos pos, Direction facing, boolean monitor) {
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            return (monitor ? state.getBlock() instanceof LightingMonitorBlock : state.getBlock() instanceof LightingConsoleBlock)
                    && state.getValue(FACING) == facing;
        }

        private void form(BlockPos origin, Direction facing, boolean value) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos pos = at(origin, facing, x, 0, z);
                    if (matches(pos, facing, false) && level.getBlockEntity(pos) instanceof Entity part) {
                        part.formed = value;
                        level.setBlock(pos, part.getBlockState().setValue(COLUMN, x).setValue(BACK, z == 1)
                                .setValue(FORMED, value), Block.UPDATE_CLIENTS);
                        part.changed();
                    }
                }
                BlockPos monitor = at(origin, facing, x, 1, 1);
                if (matches(monitor, facing, true)) {
                    level.setBlock(monitor, level.getBlockState(monitor).setValue(COLUMN, x).setValue(FORMED, value),
                            Block.UPDATE_CLIENTS);
                }
            }
        }

        public boolean bind(StageLightBlock.Entity light, int group) {
            if (!formed || group < 0 || group >= LightingSettings.GROUPS
                    || SableCompat.distance(level, worldPosition, light.getBlockPos()) > 128) {
                return false;
            }
            refreshLinks();
            fixtures.removeIf(pos -> {
                if (level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof StageLightBlock.Entity)) {
                    assignments.remove(pos);
                    fixtureIds.remove(pos);
                    families.remove(pos);
                    return true;
                }
                return false;
            });
            if (!fixtures.contains(light.getBlockPos()) && fixtures.size() >= MAX_LIGHTS || !accepts(light, group)) {
                return false;
            }
            Entity previous = light.linkedController();
            if (previous != null) {
                previous.remove(light.getBlockPos());
            }
            fixtures.remove(light.getBlockPos());
            light.follow(this);
            light.group = group;
            fixtures.add(light.getBlockPos().immutable());
            assignments.put(light.getBlockPos().immutable(), group);
            fixtureIds.put(light.getBlockPos().immutable(), SourceLinks.identifyBlock(level, light.getBlockPos()));
            families.put(light.getBlockPos().immutable(), light.family());
            reorder();
            light.changed();
            changed();
            return true;
        }

        /** The family of {@code group}'s fixtures (StageLightBlock.Entity.family), or null while it has none. */
        @Nullable
        public String family(int group) {
            return family(group, null);
        }

        @Nullable
        private String family(int group, @Nullable BlockPos except) {
            for (BlockPos pos : fixtures) {
                if (!pos.equals(except) && assignments.getOrDefault(pos, 0) == group) {
                    return families.getOrDefault(pos, StageLightBlock.Entity.DEFAULT_FAMILY);
                }
            }
            return null;
        }

        /** Whether {@code light} may join {@code group}: one family to a group, so its fixtures share one page. */
        public boolean accepts(StageLightBlock.Entity light, int group) {
            String family = family(group, light.getBlockPos());
            return family == null || family.equals(light.family());
        }

        public void reorder() {
            int[] counts = new int[LightingSettings.GROUPS];
            for (BlockPos pos : fixtures) {
                int group = assignments.getOrDefault(pos, 0);
                int order = counts[group]++;
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof StageLightBlock.Entity light
                        && light.follows(this) && light.order != order) {
                    light.order = order;
                    light.changed();
                }
            }
        }

        public int groupSize(int group) {
            return Math.max(1, (int) assignments.values().stream().filter(value -> value == group).count());
        }

        public void remove(BlockPos pos) {
            fixtures.remove(pos);
            assignments.remove(pos);
            fixtureIds.remove(pos);
            families.remove(pos);
            reorder();
            changed();
        }

        public void move(BlockPos pos, int step) {
            int current = fixtures.indexOf(pos);
            if (current < 0 || step == 0) {
                return;
            }
            int group = assignments.getOrDefault(pos, 0);
            for (int next = current + Integer.signum(step); next >= 0 && next < fixtures.size(); next += Integer.signum(step)) {
                if (assignments.getOrDefault(fixtures.get(next), 0) == group) {
                    java.util.Collections.swap(fixtures, current, next);
                    reorder();
                    changed();
                    return;
                }
            }
        }

        public void changed() {
            setChanged();
            sync();
        }

        public void sync() {
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            Entity root = controller();
            tooltip.add(Component.translatable("block.create_media_systems.lighting_console").withColor(LightingText.TEXT));
            if (root == null) {
                tooltip.add(LightingText.message("assembly").withColor(LightingText.WARNING));
            } else {
                tooltip.add(LightingText.group(root.settings, root.settings.selectedGroup));
                tooltip.add(LightingText.message("connected", LightingText.number(root.fixtures.size())));
                tooltip.add(LightingText.message("controls"));
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (identity != null) {
                tag.putUUID("Identity", identity);
            }
            tag.put("Settings", settings.save());
            tag.putBoolean("Formed", formed);
            if (audioSource != null) {
                tag.putLong("AudioSource", audioSource.asLong());
            }
            if (videoSource != null) {
                tag.putLong("VideoSource", videoSource.asLong());
            }
            if (audioId != null) {
                tag.putUUID("AudioId", audioId);
            }
            if (videoId != null) {
                tag.putUUID("VideoId", videoId);
            }
            tag.putLongArray("Fixtures", fixtures.stream().mapToLong(BlockPos::asLong).toArray());
            tag.putIntArray("FixtureGroups", fixtures.stream().mapToInt(pos -> assignments.getOrDefault(pos, 0)).toArray());
            ListTag links = new ListTag();
            for (BlockPos pos : fixtures) {
                UUID id = fixtureIds.get(pos);
                if (id != null) {
                    CompoundTag link = new CompoundTag();
                    link.putLong("Pos", pos.asLong());
                    link.putUUID("Id", id);
                    links.add(link);
                }
            }
            tag.put("FixtureIds", links);
            ListTag kinds = new ListTag();
            for (BlockPos pos : fixtures) {
                kinds.add(net.minecraft.nbt.StringTag.valueOf(families.getOrDefault(pos, StageLightBlock.Entity.DEFAULT_FAMILY)));
            }
            tag.put("FixtureFamilies", kinds);
            ListTag saved = new ListTag();
            for (CompoundTag preset : presets) {
                saved.add(preset == null ? new CompoundTag() : preset.copy());
            }
            tag.put("Presets", saved);
            if (!extras.isEmpty()) {
                tag.put("Extras", extras.copy());
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            if (tag.contains("Settings")) {
                settings.load(tag.getCompound("Settings"));
            }
            formed = tag.getBoolean("Formed");
            identity = tag.hasUUID("Identity") ? tag.getUUID("Identity") : null;
            audioSource = tag.contains("AudioSource") ? BlockPos.of(tag.getLong("AudioSource")) : null;
            videoSource = tag.contains("VideoSource") ? BlockPos.of(tag.getLong("VideoSource")) : null;
            audioId = tag.hasUUID("AudioId") ? tag.getUUID("AudioId") : null;
            videoId = tag.hasUUID("VideoId") ? tag.getUUID("VideoId") : null;
            resolvedAudio = audioSource;
            resolvedVideo = videoSource;
            fixtures.clear();
            assignments.clear();
            fixtureIds.clear();
            families.clear();
            ListTag kinds = tag.getList("FixtureFamilies", Tag.TAG_STRING);
            int[] groups = tag.getIntArray("FixtureGroups");
            int index = 0;
            for (long pos : tag.getLongArray("Fixtures")) {
                if (fixtures.size() < MAX_LIGHTS && !fixtures.contains(BlockPos.of(pos))) {
                    fixtures.add(BlockPos.of(pos));
                    assignments.put(BlockPos.of(pos), index < groups.length ? Math.max(0, Math.min(5, groups[index])) : 0);
                    families.put(BlockPos.of(pos), index < kinds.size() ? kinds.getString(index)
                            : StageLightBlock.Entity.DEFAULT_FAMILY);
                }
                index++;
            }
            for (Tag saved : tag.getList("FixtureIds", Tag.TAG_COMPOUND)) {
                CompoundTag link = (CompoundTag) saved;
                BlockPos pos = BlockPos.of(link.getLong("Pos"));
                if (fixtures.contains(pos) && link.hasUUID("Id")) {
                    fixtureIds.put(pos, link.getUUID("Id"));
                }
            }
            for (String owner : List.copyOf(extras.getAllKeys())) {
                extras.remove(owner);
            }
            CompoundTag loaded = tag.getCompound("Extras");
            for (String owner : loaded.getAllKeys()) {
                extras.put(owner, loaded.getCompound(owner).copy());
            }
            ListTag saved = tag.getList("Presets", Tag.TAG_COMPOUND);
            for (int i = 0; i < presets.length; i++) {
                presets[i] = i < saved.size() && !saved.getCompound(i).isEmpty() ? saved.getCompound(i) : null;
            }
            if (tag.contains("Signal")) {
                CompoundTag signal = tag.getCompound("Signal");
                audioLevel = signal.getFloat("Level");
                bass = signal.getFloat("Bass");
                mid = signal.getFloat("Mid");
                high = signal.getFloat("High");
                pulse = signal.getFloat("Pulse");
                bpm = signal.getFloat("Bpm");
                videoLeft = signal.getInt("Left");
                videoRight = signal.getInt("Right");
                hasAudio = signal.getBoolean("Audio");
                hasVideo = signal.getBoolean("Video");
                signalAt = signal.getLong("At");
            }
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveWithoutMetadata(registries);
            CompoundTag signal = new CompoundTag();
            signal.putFloat("Level", audioLevel);
            signal.putFloat("Bass", bass);
            signal.putFloat("Mid", mid);
            signal.putFloat("High", high);
            signal.putFloat("Pulse", pulse);
            signal.putFloat("Bpm", bpm);
            signal.putInt("Left", videoLeft);
            signal.putInt("Right", videoRight);
            signal.putBoolean("Audio", hasAudio);
            signal.putBoolean("Video", hasVideo);
            signal.putLong("At", signalAt);
            tag.put("Signal", signal);
            return tag;
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }

        public AABB getRenderBoundingBox() {
            Direction facing = getBlockState().getValue(FACING);
            AABB box = new AABB(worldPosition).minmax(new AABB(at(worldPosition, facing, 3, 1, 1)));
            return SableCompat.toWorld(level, box.inflate(0.25));
        }
    }
}
