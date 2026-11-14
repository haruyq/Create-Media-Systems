package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.api.lighting.ConsolePlayhead;
import org.haruyq.createmediasystems.api.lighting.ConsoleSetting;
import org.haruyq.createmediasystems.api.lighting.ConsoleView;
import org.haruyq.createmediasystems.api.lighting.GroupView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Link;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class LightingConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock,
        SableCompat.SilentAssemblyRemoval {
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
        // Both sides: a client samples its consoles' signal (see AnalysisPlayback#sample).
        return type != LightingFeature.LIGHTING_CONSOLE_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
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
        for (ConsoleLayout.Part part : ConsoleLayout.parts(ConsoleLayout.origin(pos, state), state.getValue(FACING))) {
            if (!part.monitor() && level.getBlockState(part.pos()).getBlock() instanceof LightingConsoleBlock desk) {
                level.scheduleTick(part.pos(), desk, 1);
            }
        }
    }

    @Nullable
    public static Entity find(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(ConsoleLayout.desk(pos, level.getBlockState(pos))) instanceof Entity entity
                ? entity.controller() : null;
    }

    /** A fixture linked to a console: its link, the console group it is in, and its family, kept while it is unloaded. */
    private record Fixture(Link link, int group, String family) {
    }

    /**
     * Where a console's media is, as a client last sampled it (see AnalysisPlayback#sample): its clock (s) and its
     * audio's motion phase, each NaN for none, as of {@code at} (System.nanoTime); drawn running on from there.
     */
    public record Playhead(double seconds, double phase, double phaseSpeed, long at) implements ConsolePlayhead {
        public static final Playhead NONE = new Playhead(Double.NaN, Double.NaN, 0, 0);

        /** Its clock at {@code now} (System.nanoTime); {@code fallback} without one. */
        public double seconds(long now, double fallback) {
            return Double.isFinite(seconds) ? seconds + since(now) : fallback;
        }

        /** Its motion phase at {@code now}; {@code fallback} without one. */
        public double phase(long now, double fallback) {
            return Double.isFinite(phase) ? phase + since(now) * phaseSpeed : fallback;
        }

        private double since(long now) {
            return Math.max(0, now - at) / 1e9;
        }
    }

    public static class Entity extends BlockEntity implements IHaveGoggleInformation, ConsoleView {
        public final LightingSettings settings = new LightingSettings();
        /** Its fixtures, in their order: a fixture's order is its place among those of its group. */
        private final List<Fixture> fixtures = new ArrayList<>();
        private Link audio = Link.NONE;
        private Link video = Link.NONE;
        public final CompoundTag[] presets = new CompoundTag[LightingSettings.PRESETS];
        /** Other mods' settings for this console (see extras), by their mod id. */
        private final CompoundTag extras = new CompoundTag();
        public boolean formed;
        /** The signal its audio and video sources play, as last sampled (see receive); null for none. */
        @Nullable
        private AnalysisFrame audioSignal;
        @Nullable
        private AnalysisFrame videoSignal;
        /** When it last had a signal (game time). */
        private long signalAt = -100;
        /** Where its media is, on a client (see Playhead). */
        public Playhead playhead = Playhead.NONE;

        public Entity(BlockPos pos, BlockState state) {
            super(LightingFeature.LIGHTING_CONSOLE_ENTITY.get(), pos, state);
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

        /** Its identity (see Link), on the server once anything linked to it; null before that, and on a client. */
        @Nullable
        public UUID identity() {
            return level == null ? null : BlockIdentity.of(level, worldPosition);
        }

        @Nullable
        public BlockPos audioSource() {
            return audio.pos();
        }

        @Nullable
        public BlockPos videoSource() {
            return video.pos();
        }

        public void setAudioSource(@Nullable BlockPos pos) {
            audio = linkTo(pos);
            audioSignal = null;
        }

        public void setVideoSource(@Nullable BlockPos pos) {
            video = linkTo(pos);
            videoSignal = null;
        }

        private Link linkTo(@Nullable BlockPos pos) {
            return level == null ? new Link(pos == null ? null : pos.immutable(), null) : Link.to(level, pos);
        }

        /** Its fixtures' positions, in their order. */
        public List<BlockPos> fixtures() {
            return fixtures.stream().map(fixture -> fixture.link().pos()).toList();
        }

        private int indexOf(BlockPos pos) {
            for (int i = 0; i < fixtures.size(); i++) {
                if (pos.equals(fixtures.get(i).link().pos())) {
                    return i;
                }
            }
            return -1;
        }

        /** Whether the fixture at {@code pos} is linked to it. */
        public boolean links(BlockPos pos) {
            return indexOf(pos) >= 0;
        }

        /** The group of the fixture at {@code pos}; 0 if it is not linked. */
        public int group(BlockPos pos) {
            int index = indexOf(pos);
            return index < 0 ? 0 : fixtures.get(index).group();
        }

        /**
         * The fixture at {@code pos}'s place among its group's, from 0: left to right as the console's player sees
         * them (see ConsoleLayout.compareFixtures) wherever Sable has it and them, in the order they were linked where
         * that doesn't tell; 0 if it is not linked.
         */
        public int order(BlockPos pos) {
            int index = indexOf(pos);
            if (index < 0) {
                return 0;
            }
            Direction facing = getBlockState().getValue(FACING);
            BlockPos seen = seen(pos);
            int order = 0;
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture other = fixtures.get(i);
                if (i != index && other.group() == fixtures.get(index).group()) {
                    int place = other.link().pos() == null ? 1
                            : ConsoleLayout.compareFixtures(facing, seen(other.link().pos()), seen);
                    order += place < 0 || place == 0 && i < index ? 1 : 0;
                }
            }
            return order;
        }

        /**
         * The block of the console's own block space the fixture at {@code pos} is in now: itself, unless one of the
         * two is on a Sable sub-level the other is not on, whose blocks keep their positions in a far-off plot.
         */
        private BlockPos seen(BlockPos pos) {
            return level == null || SableCompat.sameSubLevel(level, worldPosition, pos) ? pos : BlockPos.containing(
                    SableCompat.toBlockSpace(level, worldPosition, SableCompat.toWorld(level, pos, pos.getCenter())));
        }

        /**
         * Follows its fixtures and media to where Sable moved them, and drops the fixtures replaced, gone, or linked
         * to another console since; each loaded fixture it keeps follows it in turn. On the server's root desk: every
         * second, and before links change.
         */
        public void refreshLinks() {
            if (level == null || level.isClientSide || !isRoot()) {
                return;
            }
            Link self = Link.to(level, worldPosition);
            boolean updated = false;
            List<Fixture> kept = new ArrayList<>();
            for (Fixture fixture : fixtures) {
                Link link = fixture.link().follow(level);
                BlockPos pos = link.pos();
                if (pos == null || kept.stream().anyMatch(other -> pos.equals(other.link().pos()))) {
                    updated = true;
                    continue;
                }
                String family = fixture.family();
                if (level.isLoaded(pos)) {
                    if (!(level.getBlockEntity(pos) instanceof StageLightBlock.Entity light) || !light.follows(this)) {
                        updated = true;
                        continue;
                    }
                    light.follow(self);
                    family = light.family();
                }
                Fixture now = new Fixture(link, fixture.group(), family);
                updated |= !now.equals(fixture);
                kept.add(now);
            }
            fixtures.clear();
            fixtures.addAll(kept);
            Link followedAudio = audio.follow(level);
            Link followedVideo = video.follow(level);
            if (!followedAudio.equals(audio)) {
                if (!Objects.equals(followedAudio.pos(), audio.pos())) {
                    audioSignal = null;
                }
                audio = followedAudio;
                updated = true;
            }
            if (!followedVideo.equals(video)) {
                if (!Objects.equals(followedVideo.pos(), video.pos())) {
                    videoSignal = null;
                }
                video = followedVideo;
                updated = true;
            }
            if (updated) {
                changed();
            }
        }

        @Nullable
        public Entity controller() {
            if (level == null || !getBlockState().getValue(FORMED)) {
                return null;
            }
            BlockPos origin = ConsoleLayout.origin(worldPosition, getBlockState());
            return level.isLoaded(origin) && level.getBlockEntity(origin) instanceof Entity entity && entity.formed
                    ? entity : null;
        }

        public boolean isRoot() {
            return getBlockState().getValue(COLUMN) == 0 && !getBlockState().getValue(BACK);
        }

        private void tick() {
            if (level.isClientSide) {
                if (formed && isRoot()) {
                    AnalysisPlayback.sample(this);
                }
                return;
            }
            if (level.getGameTime() % 20 == 0) {
                refreshLinks();
                updateStructure();
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
                BlockPos origin = ConsoleLayout.origin(worldPosition, getBlockState());
                for (ConsoleLayout.Part part : ConsoleLayout.parts(origin, facing)) {
                    if (!level.isLoaded(part.pos())) {
                        return;
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
            for (BlockPos origin : ConsoleLayout.origins(worldPosition, facing)) {
                if (complete(origin, facing)) {
                    form(origin, facing, true);
                    return;
                }
            }
            if (getBlockState().getValue(FORMED)) {
                formed = false;
                level.setBlock(worldPosition, getBlockState().setValue(FORMED, false), Block.UPDATE_CLIENTS);
                changed();
            }
        }

        private boolean complete(BlockPos origin, Direction facing) {
            for (ConsoleLayout.Part part : ConsoleLayout.parts(origin, facing)) {
                if (!matches(part.pos(), facing, part.monitor())) {
                    return false;
                }
                if (!part.monitor()) {
                    Entity other = (Entity) level.getBlockEntity(part.pos());
                    Entity owner = other == null ? null : other.controller();
                    if (owner != null && !owner.getBlockPos().equals(origin)) {
                        return false;
                    }
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
            for (ConsoleLayout.Part part : ConsoleLayout.parts(origin, facing)) {
                if (!matches(part.pos(), facing, part.monitor())) {
                    continue;
                }
                if (part.monitor()) {
                    level.setBlock(part.pos(), level.getBlockState(part.pos()).setValue(COLUMN, part.column())
                            .setValue(FORMED, value), Block.UPDATE_CLIENTS);
                } else if (level.getBlockEntity(part.pos()) instanceof Entity desk) {
                    desk.formed = value;
                    level.setBlock(part.pos(), desk.getBlockState().setValue(COLUMN, part.column())
                            .setValue(BACK, part.back()).setValue(FORMED, value), Block.UPDATE_CLIENTS);
                    desk.changed();
                }
            }
        }

        public boolean bind(StageLightBlock.Entity light, int group) {
            BlockPos pos = light.getBlockPos();
            if (!formed || group < 0 || group >= LightingSettings.GROUPS || !Link.inReach(level, worldPosition, pos)) {
                return false;
            }
            refreshLinks();
            if (!links(pos) && fixtures.size() >= MAX_LIGHTS || !accepts(light, group)) {
                return false;
            }
            Entity previous = light.linked();
            if (previous != null && previous != this) {
                previous.remove(pos);
            }
            unlinkIf(pos::equals);
            fixtures.add(new Fixture(Link.to(level, pos), group, light.family()));
            light.follow(Link.to(level, worldPosition));
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
            for (Fixture fixture : fixtures) {
                if (!fixture.link().pos().equals(except) && fixture.group() == group) {
                    return fixture.family();
                }
            }
            return null;
        }

        /** Whether {@code light} may join {@code group}: one family to a group, so its fixtures share one page. */
        public boolean accepts(StageLightBlock.Entity light, int group) {
            String family = family(group, light.getBlockPos());
            return family == null || family.equals(light.family());
        }

        @Override
        public boolean formed() {
            return formed;
        }

        @Override
        public ConsolePlayhead playhead() {
            return playhead;
        }

        @Override
        public boolean enabled() {
            return settings.enabled;
        }

        @Override
        public float master() {
            return settings.master;
        }

        @Override
        public int selectedGroup() {
            return settings.selectedGroup;
        }

        @Override
        public int selectedColor() {
            return settings.selectedColor;
        }

        @Override
        public GroupView groupSettings(int group) {
            return settings.groups[group];
        }

        @Override
        public void set(ConsoleSetting setting, int group, int index, float value, float other) {
            Entity root = controller();
            if (root == null) {
                return;
            }
            LightingPackets.Control control = LightingPackets.Control.valueOf(setting.name());
            if (level.isClientSide) {
                LightingConsolePage.send(root, control, group, index, value, other);
            } else if (LightingPackets.apply(root, new LightingPackets.Change(root.getBlockPos(), group, control, index,
                    value, other), null)) {
                root.changed();
            }
        }

        @Override
        public void track(int group, @Nullable UUID player) {
            Entity root = controller();
            if (root != null && !level.isClientSide && group >= 0 && group < LightingSettings.GROUPS) {
                root.settings.groups[group].target = player;
                root.changed();
            }
        }

        public int groupSize(int group) {
            return Math.max(1, (int) fixtures.stream().filter(fixture -> fixture.group() == group).count());
        }

        public void remove(BlockPos pos) {
            unlinkIf(pos::equals);
            changed();
        }

        /** Unlinks the fixtures {@code unlinked} picks; whether there were any. */
        private boolean unlinkIf(Predicate<BlockPos> unlinked) {
            return fixtures.removeIf(fixture -> unlinked.test(fixture.link().pos()));
        }

        /** Moves the fixture at {@code pos} {@code step} places within its group (by its sign). */
        public void move(BlockPos pos, int step) {
            int current = indexOf(pos);
            if (current < 0 || step == 0) {
                return;
            }
            int group = fixtures.get(current).group();
            for (int next = current + Integer.signum(step); next >= 0 && next < fixtures.size(); next += Integer.signum(step)) {
                if (fixtures.get(next).group() == group) {
                    Collections.swap(fixtures, current, next);
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
                tooltip.add(LightingText.message(LightingText.WARNING, "assembly"));
            } else {
                tooltip.add(LightingText.group(root.settings, root.settings.selectedGroup));
                tooltip.add(LightingText.message("connected", LightingText.number(root.fixtures.size())));
                tooltip.add(LightingText.message(LightingText.MUTED, "controls"));
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("Settings", settings.save());
            tag.putBoolean("Formed", formed);
            audio.save(tag, "AudioSource", "AudioId");
            video.save(tag, "VideoSource", "VideoId");
            tag.putLongArray("Fixtures", fixtures.stream().mapToLong(fixture -> fixture.link().pos().asLong()).toArray());
            tag.putIntArray("FixtureGroups", fixtures.stream().mapToInt(Fixture::group).toArray());
            ListTag ids = new ListTag();
            ListTag families = new ListTag();
            for (Fixture fixture : fixtures) {
                if (fixture.link().id() != null) {
                    CompoundTag id = new CompoundTag();
                    id.putLong("Pos", fixture.link().pos().asLong());
                    id.putUUID("Id", fixture.link().id());
                    ids.add(id);
                }
                families.add(StringTag.valueOf(fixture.family()));
            }
            tag.put("FixtureIds", ids);
            tag.put("FixtureFamilies", families);
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
            audio = Link.load(tag, "AudioSource", "AudioId");
            video = Link.load(tag, "VideoSource", "VideoId");
            fixtures.clear();
            Map<BlockPos, UUID> ids = new HashMap<>();
            for (Tag saved : tag.getList("FixtureIds", Tag.TAG_COMPOUND)) {
                CompoundTag id = (CompoundTag) saved;
                if (id.hasUUID("Id")) {
                    ids.put(BlockPos.of(id.getLong("Pos")), id.getUUID("Id"));
                }
            }
            ListTag families = tag.getList("FixtureFamilies", Tag.TAG_STRING);
            int[] groups = tag.getIntArray("FixtureGroups");
            long[] positions = tag.getLongArray("Fixtures");
            for (int i = 0; i < positions.length; i++) {
                BlockPos pos = BlockPos.of(positions[i]);
                if (fixtures.size() < MAX_LIGHTS && !links(pos)) {
                    fixtures.add(new Fixture(new Link(pos, ids.get(pos)),
                            i < groups.length ? Mth.clamp(groups[i], 0, LightingSettings.GROUPS - 1) : 0,
                            i < families.size() ? families.getString(i) : StageLightBlock.Entity.DEFAULT_FAMILY));
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
        }

        /**
         * The signal its audio and video sources play now (null: none), as the analysis has it at {@code gameTime}:
         * each tick, on the server and on each client.
         */
        public void receive(@Nullable AnalysisFrame audio, @Nullable AnalysisFrame video, long gameTime) {
            audioSignal = audio;
            videoSignal = video;
            if (hasAudio() || hasVideo()) {
                signalAt = gameTime;
            }
        }

        /** Whether its audio source's sound reached it when last sampled. */
        public boolean hasAudio() {
            return audioSignal != null && audioSignal.audio();
        }

        /** Whether its video source's picture reached it when last sampled. */
        public boolean hasVideo() {
            return videoSignal != null && videoSignal.video();
        }

        /** Whether it had a signal lately, as of {@code gameTime}: a console no longer sampled goes dark. */
        public boolean fresh(long gameTime) {
            return gameTime - signalAt < 30;
        }

        /** Its audio source's signal as of {@code gameTime}; null for none, or none lately. */
        @Nullable
        public AnalysisFrame audio(long gameTime) {
            return hasAudio() && fresh(gameTime) ? audioSignal : null;
        }

        /** Its video source's signal as of {@code gameTime}; null for none, or none lately. */
        @Nullable
        public AnalysisFrame video(long gameTime) {
            return hasVideo() && fresh(gameTime) ? videoSignal : null;
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
