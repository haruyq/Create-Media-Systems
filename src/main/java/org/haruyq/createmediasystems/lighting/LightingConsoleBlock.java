package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
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

    /** How a fixture is linked to a console: its group, its id (see SourceLinks) and its family, kept while unloaded. */
    private record Link(int group, @Nullable UUID id, String family) {
        static final Link NONE = new Link(0, null, StageLightBlock.Entity.DEFAULT_FAMILY);
    }

    public static class Entity extends BlockEntity implements IHaveGoggleInformation {
        public final LightingSettings settings = new LightingSettings();
        /** Its fixtures, in their order; each has its {@link Link} in {@code links}. */
        public final List<BlockPos> fixtures = new ArrayList<>();
        private final Map<BlockPos, Link> links = new HashMap<>();
        private UUID identity;
        private UUID audioId;
        private UUID videoId;
        private BlockPos resolvedAudio;
        private BlockPos resolvedVideo;
        private boolean refreshingLinks;
        public final CompoundTag[] presets = new CompoundTag[LightingSettings.PRESETS];
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
        public float bpm = AnalysisFrame.DEFAULT_BPM;
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
                Map<BlockPos, Link> resolved = new HashMap<>();
                for (BlockPos previous : fixtures) {
                    Link link = link(previous);
                    UUID id = link.id();
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
                    String family = link.family();
                    if (level.isLoaded(pos)) {
                        if (!(level.getBlockEntity(pos) instanceof StageLightBlock.Entity light) || !light.follows(this)) {
                            updated = true;
                            continue;
                        }
                        light.follow(this);
                        updated |= !light.family().equals(family);
                        family = light.family();
                    }
                    positions.add(pos);
                    resolved.put(pos, new Link(link.group(), id, family));
                    updated |= !pos.equals(previous);
                }
                fixtures.clear();
                fixtures.addAll(positions);
                links.clear();
                links.putAll(resolved);
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
                    boolean removed = unlinkIf(pos -> level.isLoaded(pos)
                            && (!(level.getBlockEntity(pos) instanceof StageLightBlock.Entity light) || !light.follows(this)));
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
            unlinkIf(pos -> level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof StageLightBlock.Entity));
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
            links.put(light.getBlockPos().immutable(),
                    new Link(group, SourceLinks.identifyBlock(level, light.getBlockPos()), light.family()));
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
                if (!pos.equals(except) && link(pos).group() == group) {
                    return link(pos).family();
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
                int order = counts[link(pos).group()]++;
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof StageLightBlock.Entity light
                        && light.follows(this) && light.order != order) {
                    light.order = order;
                    light.changed();
                }
            }
        }

        public int groupSize(int group) {
            return Math.max(1, (int) links.values().stream().filter(link -> link.group() == group).count());
        }

        public void remove(BlockPos pos) {
            unlinkIf(pos::equals);
            reorder();
            changed();
        }

        /** Unlinks the fixtures {@code unlinked} picks; whether there were any. */
        private boolean unlinkIf(Predicate<BlockPos> unlinked) {
            return fixtures.removeIf(pos -> {
                if (!unlinked.test(pos)) {
                    return false;
                }
                links.remove(pos);
                return true;
            });
        }

        /** How {@code pos} is linked: group 0, of the default family, if it never said. */
        private Link link(BlockPos pos) {
            return links.getOrDefault(pos, Link.NONE);
        }

        public void move(BlockPos pos, int step) {
            int current = fixtures.indexOf(pos);
            if (current < 0 || step == 0) {
                return;
            }
            int group = link(pos).group();
            for (int next = current + Integer.signum(step); next >= 0 && next < fixtures.size(); next += Integer.signum(step)) {
                if (link(fixtures.get(next)).group() == group) {
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
            tooltip.add(getBlockState().getBlock().getName().withColor(LightingText.TEXT));
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
            tag.putIntArray("FixtureGroups", fixtures.stream().mapToInt(pos -> link(pos).group()).toArray());
            ListTag ids = new ListTag();
            ListTag kinds = new ListTag();
            for (BlockPos pos : fixtures) {
                if (link(pos).id() != null) {
                    CompoundTag id = new CompoundTag();
                    id.putLong("Pos", pos.asLong());
                    id.putUUID("Id", link(pos).id());
                    ids.add(id);
                }
                kinds.add(StringTag.valueOf(link(pos).family()));
            }
            tag.put("FixtureIds", ids);
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
            links.clear();
            Map<BlockPos, UUID> ids = new HashMap<>();
            for (Tag saved : tag.getList("FixtureIds", Tag.TAG_COMPOUND)) {
                CompoundTag id = (CompoundTag) saved;
                if (id.hasUUID("Id")) {
                    ids.put(BlockPos.of(id.getLong("Pos")), id.getUUID("Id"));
                }
            }
            ListTag kinds = tag.getList("FixtureFamilies", Tag.TAG_STRING);
            int[] groups = tag.getIntArray("FixtureGroups");
            int index = 0;
            for (long saved : tag.getLongArray("Fixtures")) {
                BlockPos pos = BlockPos.of(saved);
                if (fixtures.size() < MAX_LIGHTS && !fixtures.contains(pos)) {
                    fixtures.add(pos);
                    links.put(pos, new Link(index < groups.length ? Mth.clamp(groups[index], 0, LightingSettings.GROUPS - 1) : 0,
                            ids.get(pos), index < kinds.size() ? kinds.getString(index) : StageLightBlock.Entity.DEFAULT_FAMILY));
                }
                index++;
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
        }

        /**
         * The signal its audio and video sources play now (null: none), as the analysis has it at {@code gameTime}:
         * on the server each tick, and on each client as it draws.
         */
        public void receive(@Nullable AnalysisFrame audio, @Nullable AnalysisFrame video, long gameTime) {
            hasAudio = audio != null && audio.audio();
            hasVideo = video != null && video.video();
            audioLevel = audio == null ? 0 : audio.level();
            bass = audio == null ? 0 : audio.bass();
            mid = audio == null ? 0 : audio.mid();
            high = audio == null ? 0 : audio.high();
            pulse = audio == null ? 0 : audio.pulse();
            bpm = audio == null ? AnalysisFrame.DEFAULT_BPM : audio.bpm();
            if (video != null) {
                videoLeft = video.left();
                videoRight = video.right();
            }
            if (hasAudio || hasVideo) {
                signalAt = gameTime;
            }
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
