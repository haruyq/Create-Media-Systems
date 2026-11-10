package org.haruyq.createmediasystems.hifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.audio.CustomDiscItem;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.playback.Tone;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.VideoSource;
import org.haruyq.createmediasystems.video.Remotable;
import org.haruyq.createmediasystems.video.VideoDiscItem;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A hi-fi system of separate units stacked in one block: an amplifier, a graphic equalizer, a tuner, a disc player
 * and a double cassette deck (see Unit), each placed onto the stack with its own item, in any order. The amplifier is
 * the heart of it: it picks which of the others is heard, how loud, and through which speakers (see
 * HifiSpeakerBlock), and the others only work once joined to it with cables at the back (see Cable). Everything is
 * worked by pointing at its controls (see Control); sneak and right-click with an empty hand to take the top unit off.
 */
public class HifiStackBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<HifiStackBlock> CODEC = simpleCodec(HifiStackBlock::new);
    /** Its shape for each height (in half sixteenths) and whether it faces along x. */
    private static final Map<Integer, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    public HifiStackBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<HifiStackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Drawn unit by unit by its renderer; its model only gives its particles. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        float height = level.getBlockEntity(pos) instanceof Entity stack && !stack.units.isEmpty()
                ? stack.height() : Unit.ROOM;
        boolean alongX = state.getValue(FACING).getAxis() == Direction.Axis.X;
        return SHAPES.computeIfAbsent(Math.round(height * 2) << 1 | (alongX ? 1 : 0), key -> alongX
                ? Block.box(Unit.BACK, 0, Unit.LEFT, Unit.FRONT, height, Unit.LEFT + Unit.WIDTH)
                : Block.box(Unit.LEFT, 0, Unit.BACK, Unit.LEFT + Unit.WIDTH, height, Unit.FRONT));
    }

    /** Sneaking with an empty hand takes the top unit off; its controls are worked by HifiControls. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entity stack) {
            stack.pop(player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Sable assembling a ship removes it too: data/sable/tags/block/silent_assembly_removal.json keeps that from dropping it.
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity stack) {
            for (ItemStack item : stack.everything()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), item);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** No item of its own: picking it gives its top unit. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return level.getBlockEntity(pos) instanceof Entity stack && !stack.units.isEmpty()
                ? new ItemStack(CreateMediaSystems.hifiUnit(stack.units.getLast())) : ItemStack.EMPTY;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Both sides: a client counts its displays on between the server's words.
        return type != CreateMediaSystems.HIFI_STACK_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** What the amplifier plays. */
    public enum Input {
        DISC, TUNER, TAPE;

        static Input byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /** What a deck does with its tape. */
    public enum Motion {
        STOP, PLAY, REWIND, FORWARD;

        static Motion byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /** The equalizer's presets: how much each of {@link #BAND_CENTRES} is raised (dB) at the middle effect level. */
    public enum Preset {
        FLAT(0, 0, 0, 0, 0, 0, 0),
        HEAVY(6, 4, 1, 0, 1, 3, 5),
        CLEAR(-2, -1, 0, 1, 3, 4, 5),
        SOFT(3, 2, 0, -1, -2, -3, -4),
        VISUAL(5, 3, -1, 1, 2, 2, 1),
        VOCAL(-4, -2, 2, 4, 3, 1, -2);

        public final float[] gains;

        Preset(float... gains) {
            this.gains = gains;
        }

        public static Preset byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /** The middle of each of the equalizer's seven bands (Hz); each reaches {@link #BAND_REACH} either way. */
    public static final float[] BAND_CENTRES = {63, 160, 400, 1000, 2500, 6300, 16000};
    private static final float BAND_REACH = 1.58f;
    /** The most any band is raised or lowered (dB), however far the effect level is turned. */
    public static final float MAX_GAIN = 12;
    public static final int STATIONS = 6;
    /** How many times faster than it plays a deck winds its tape. */
    static final int WIND = 30;

    public static class Entity extends BlockEntity implements VideoSource, Remotable, IHaveGoggleInformation {
        /** The client's stack whose knob is held, and which: the server's word for it waits (see HifiControls). */
        @Nullable
        public static Entity heldStack;
        @Nullable
        public static Control heldKnob;

        /** Bottom first. */
        private final List<Unit> units = new ArrayList<>();
        /** The cables plugged in (see Cable#bit). */
        private int cables;

        private boolean power;
        private Input input = Input.DISC;
        /** Knobs go from 0 to 100. */
        private int volume = 40;
        private boolean bass;
        private int bassLevel = 50;
        private boolean wide;
        private boolean direct;

        private Preset preset = Preset.FLAT;
        private int effect = 50;

        /** The link of each of the tuner's stations; empty for none. */
        private final String[] stations = new String[STATIONS];
        /** The station tuned in; -1 for none. */
        private int station = -1;
        private long tunedTicks;

        private ItemStack disc = ItemStack.EMPTY;
        private boolean trayOpen;
        private int track;
        private boolean discPlaying;
        private boolean discPaused;
        /** How far into its track the disc is (ticks). */
        private long discTicks;

        private final ItemStack[] tapes = {ItemStack.EMPTY, ItemStack.EMPTY};
        private final Motion[] motions = {Motion.STOP, Motion.STOP};
        /** Where each deck's tape is: the track, and how far into it (ticks). At its end, the track after the last. */
        private final int[] tapeTracks = new int[2];
        private final long[] tapeTicks = new long[2];

        /** Counts a restart of the same link (a track played from its top again) as a new playback. */
        private int restarts;
        /** What its speakers were last told to play (see tick), and for how many ticks; not saved. */
        @Nullable
        private Playing begun;
        private long sinceBegin;
        /** Who last worked it, to start what it plays next. */
        @Nullable
        private Player by;

        /** Client only: how far the tray is out (0 to 1) this tick and the last, and how far each deck's reels have turned. */
        public float trayOut;
        public float lastTrayOut;
        public final float[] reels = new float[2];
        public final float[] lastReels = new float[2];

        /** What is heard: which input, which deck, which link, and which start of it. */
        private record Playing(Input input, int deck, String url, int restart) {
        }

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.HIFI_STACK_ENTITY.get(), pos, state);
            Arrays.fill(stations, "");
        }

        public List<Unit> units() {
            return units;
        }

        public float height() {
            return HifiLayout.height(units);
        }

        public boolean has(Unit unit) {
            return units.contains(unit);
        }

        public boolean wired(Cable cable) {
            return (cables & cable.bit()) != 0;
        }

        /** Whether {@code unit} works: in the stack, and (but for the amplifier) joined to an amplifier switched on. */
        public boolean live(Unit unit) {
            return has(unit) && has(Unit.AMPLIFIER) && power && (unit == Unit.AMPLIFIER || wired(Cable.system(unit)));
        }

        public boolean power() {
            return has(Unit.AMPLIFIER) && power;
        }

        public Input input() {
            return input;
        }

        public boolean bass() {
            return bass;
        }

        public boolean wide() {
            return wide;
        }

        public boolean direct() {
            return direct;
        }

        public Preset preset() {
            return preset;
        }

        public int station() {
            return station;
        }

        public String station(int index) {
            return stations[index];
        }

        public ItemStack disc() {
            return disc;
        }

        public boolean trayOpen() {
            return trayOpen;
        }

        public boolean discPlaying() {
            return discPlaying;
        }

        public boolean discPaused() {
            return discPaused;
        }

        public long discTicks() {
            return discTicks;
        }

        public ItemStack tape(int deck) {
            return tapes[deck];
        }

        public Motion motion(int deck) {
            return motions[deck];
        }

        public int tapeTrack(int deck) {
            return tapeTracks[deck];
        }

        public long tapeTicks(int deck) {
            return tapeTicks[deck];
        }

        /** What a knob is set to, 0 to 100. */
        public int knob(Control control) {
            return switch (control) {
                case VOLUME -> volume;
                case BASS_LEVEL -> bassLevel;
                case EFFECT -> effect;
                default -> 0;
            };
        }

        /** Sets a knob; on a client, here only, as it is turned (see HifiControls). */
        public void turn(Control control, int value) {
            value = Mth.clamp(value, 0, 100);
            switch (control) {
                case VOLUME -> volume = value;
                case BASS_LEVEL -> bassLevel = value;
                case EFFECT -> effect = value;
                default -> {
                }
            }
        }

        /** Whether {@code unit} can be put on top: one of each, and no taller than the block. */
        public boolean canAdd(Unit unit) {
            return !has(unit) && height() + unit.height <= Unit.ROOM;
        }

        public void add(Unit unit) {
            units.add(unit);
            changed();
        }

        /** Sets it up as a scene shows it (see CreateMediaSystemsPonder): switched on or not, with {@code wired} plugged in. */
        public void arrange(boolean on, Cable... wired) {
            power = on;
            cables = 0;
            for (Cable cable : wired) {
                cables |= cable.bit();
            }
            changed();
        }

        /** Takes the top unit off for {@code player}, with what is in it and its cables; the last one takes the block. */
        void pop(Player player) {
            if (units.isEmpty()) {
                return;
            }
            Unit unit = units.getLast();
            List<ItemStack> items = new ArrayList<>(contents(unit));
            items.add(new ItemStack(CreateMediaSystems.hifiUnit(unit)));
            units.removeLast();
            if (unit == Unit.AMPLIFIER) {
                power = false;
            }
            items.forEach(player.getInventory()::placeItemBackInInventory);
            level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1, 0.8f);
            if (units.isEmpty()) {
                level.removeBlock(worldPosition, false);
            } else {
                changed();
            }
        }

        /** Every item it is made of and holds. */
        List<ItemStack> everything() {
            List<ItemStack> items = new ArrayList<>();
            for (Unit unit : List.copyOf(units)) {
                items.addAll(contents(unit));
                items.add(new ItemStack(CreateMediaSystems.hifiUnit(unit)));
            }
            units.clear();
            return items;
        }

        /** Takes out what {@code unit} holds: its disc or tapes, and the cables plugged into it. */
        private List<ItemStack> contents(Unit unit) {
            List<ItemStack> items = new ArrayList<>();
            int unplugged = 0;
            for (Cable cable : Cable.values()) {
                if (wired(cable) && (unit == Unit.AMPLIFIER || cable.unit == unit)) {
                    cables &= ~cable.bit();
                    unplugged++;
                }
            }
            if (unplugged > 0) {
                items.add(new ItemStack(CreateMediaSystems.HIFI_CABLE.get(), unplugged));
            }
            if (unit == Unit.DISC_PLAYER) {
                if (!disc.isEmpty()) {
                    items.add(disc);
                }
                disc = ItemStack.EMPTY;
                discPlaying = discPaused = trayOpen = false;
                track = 0;
                discTicks = 0;
            } else if (unit == Unit.CASSETTE_DECK) {
                for (int deck = 0; deck < 2; deck++) {
                    if (!tapes[deck].isEmpty()) {
                        items.add(eject(deck));
                    }
                }
            } else if (unit == Unit.TUNER) {
                station = -1;
            }
            return items;
        }

        /** The links of the disc in the player. */
        @Override
        public List<String> playlist() {
            if (disc.getItem() instanceof VideoDiscItem) {
                return disc.getOrDefault(CreateMediaSystems.PLAYLIST.get(), List.of());
            }
            String url = disc.get(CreateMediaSystems.URL.get());
            return url == null ? List.of() : List.of(url);
        }

        public List<String> tracks(int deck) {
            return tapes[deck].getOrDefault(CreateMediaSystems.PLAYLIST.get(), List.of());
        }

        @Override
        public int index() {
            return track;
        }

        /** The deck whose tape plays; -1 for neither. */
        public int playingDeck() {
            for (int deck = 0; deck < 2; deck++) {
                if (motions[deck] == Motion.PLAY && tapeTracks[deck] < tracks(deck).size()) {
                    return deck;
                }
            }
            return -1;
        }

        /** What its speakers play now: what the amplifier's input is set to, if that plays and is joined to it. */
        @Nullable
        private Playing audible() {
            if (!power()) {
                return null;
            }
            switch (input) {
                case DISC -> {
                    List<String> playlist = playlist();
                    if (live(Unit.DISC_PLAYER) && wired(Cable.LINE) && discPlaying && !discPaused && !trayOpen
                            && track < playlist.size()) {
                        return new Playing(Input.DISC, 0, playlist.get(track), restarts);
                    }
                }
                case TAPE -> {
                    int deck = playingDeck();
                    if (live(Unit.CASSETTE_DECK) && deck >= 0) {
                        return new Playing(Input.TAPE, deck, tracks(deck).get(tapeTracks[deck]), restarts);
                    }
                }
                case TUNER -> {
                    if (live(Unit.TUNER) && station >= 0 && !stations[station].isEmpty()) {
                        return new Playing(Input.TUNER, 0, stations[station], restarts);
                    }
                }
            }
            return null;
        }

        private long position(Playing playing) {
            return switch (playing.input) {
                case DISC -> discTicks;
                case TAPE -> tapeTicks[playing.deck];
                case TUNER -> tunedTicks;
            };
        }

        /**
         * What its speakers play, once tick has told them of it: the ticks since then, which Streams counts on from
         * where it was (see Streams#loading), so a tape or a paused disc plays on from there.
         */
        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            Playing playing = audible();
            if (playing == null || !playing.equals(begun)) {
                return null;
            }
            Streams.Video video = null;
            if (playing.input == Input.DISC && disc.getItem() instanceof VideoDiscItem videoDisc && videoDisc.maxHeight > 0) {
                List<String> playlist = playlist();
                video = new Streams.Video(worldPosition, videoDisc.maxHeight,
                        track + 1 < playlist.size() ? Optional.of(playlist.get(track + 1)) : Optional.empty());
            }
            return new Streams.Source(playing.url, sinceBegin, video);
        }

        /** The amplifier's volume knob, turned up slowly at first as a real one. */
        @Override
        public float volume() {
            return power() ? (float) Math.pow(volume / 100.0, 1.5) : 0;
        }

        @Override
        public float remoteVolume() {
            return volume / 100f;
        }

        /**
         * How it sounds: the equalizer's preset at its effect level (unless the amplifier plays a disc direct), the
         * amplifier's bass boost and wide stereo, and a tape's duller top.
         */
        public Tone tone() {
            if (!power()) {
                return Tone.FLAT;
            }
            List<Float> bands = new ArrayList<>();
            if (live(Unit.EQUALIZER) && !(direct && input == Input.DISC)) {
                for (int band = 0; band < BAND_CENTRES.length; band++) {
                    float gain = Mth.clamp(preset.gains[band] * effect / 50f, -MAX_GAIN, MAX_GAIN);
                    if (gain != 0) {
                        bands.add(BAND_CENTRES[band] / BAND_REACH);
                        bands.add(BAND_CENTRES[band] * BAND_REACH);
                        bands.add(gain);
                    }
                }
            }
            if (bass) {
                bands.add(25f);
                bands.add(110f);
                bands.add(2 + bassLevel / 100f * 8);
            }
            if (input == Input.TAPE) {
                bands.add(9000f);
                bands.add(20000f);
                bands.add(-6f);
            }
            float[] triples = new float[bands.size()];
            for (int i = 0; i < triples.length; i++) {
                triples[i] = bands.get(i);
            }
            return new Tone(triples, wide ? 1.6f : 1);
        }

        void tick() {
            if (level == null) {
                return;
            }
            if (level.isClientSide) {
                clientTick();
                return;
            }
            // Whatever is heard now that wasn't a tick ago starts for its speakers, from where it is.
            Playing playing = audible();
            if (!Objects.equals(playing, begun)) {
                begun = playing;
                if (playing != null) {
                    sinceBegin = 0;
                    Streams.loading(this, by != null && !by.isRemoved() ? by : null, position(playing), true);
                }
            }
            boolean heard = playing != null;
            boolean moving = false;
            if (discPlaying && !discPaused) {
                moving = true;
                discTicks = heard && playing.input == Input.DISC ? Streams.played(this, ++sinceBegin) : discTicks + 1;
                List<String> playlist = playlist();
                if (track >= playlist.size()) {
                    stopDisc();
                } else if (level.getGameTime() % Streams.RELAY_TICKS == 0 && Streams.endedAt(playlist.get(track), discTicks)) {
                    if (track + 1 < playlist.size()) {
                        playTrack(track + 1);
                    } else {
                        stopDisc();
                    }
                }
            }
            for (int deck = 0; deck < 2; deck++) {
                List<String> tracks = tracks(deck);
                switch (motions[deck]) {
                    case PLAY -> {
                        moving = true;
                        if (tapeTracks[deck] >= tracks.size()) {
                            motions[deck] = Motion.STOP;
                            changed();
                            break;
                        }
                        tapeTicks[deck] = heard && playing.input == Input.TAPE && playing.deck == deck
                                ? Streams.played(this, ++sinceBegin) : tapeTicks[deck] + 1;
                        if (level.getGameTime() % Streams.RELAY_TICKS == 0
                                && Streams.endedAt(tracks.get(tapeTracks[deck]), tapeTicks[deck])) {
                            tapeTracks[deck]++;
                            tapeTicks[deck] = 0;
                            restarts++;
                            changed();
                        }
                    }
                    case REWIND, FORWARD -> {
                        moving = true;
                        wind(deck, tracks);
                    }
                    case STOP -> {
                    }
                }
            }
            if (station >= 0) {
                tunedTicks = heard && playing.input == Input.TUNER ? Streams.played(this, ++sinceBegin) : tunedTicks + 1;
            }
            if (moving) {
                setChanged();
                if (level.getGameTime() % 100 == 0) {
                    changed();
                }
            }
        }

        /** Winds the tape of {@code deck} on or back, a track of unknown length taking two seconds; stops at either end. */
        private void wind(int deck, List<String> tracks) {
            if (motions[deck] == Motion.FORWARD) {
                if (tapeTracks[deck] >= tracks.size()) {
                    motions[deck] = Motion.STOP;
                    changed();
                    return;
                }
                tapeTicks[deck] += WIND;
                double duration = Streams.duration(tracks.get(tapeTracks[deck]));
                if (duration > 0 ? tapeTicks[deck] >= duration * 20 : tapeTicks[deck] >= 40 * WIND) {
                    tapeTracks[deck]++;
                    tapeTicks[deck] = 0;
                    changed();
                }
                return;
            }
            tapeTicks[deck] -= WIND;
            if (tapeTicks[deck] >= 0) {
                return;
            }
            if (tapeTracks[deck] == 0 || tracks.isEmpty()) {
                tapeTracks[deck] = 0;
                tapeTicks[deck] = 0;
                motions[deck] = Motion.STOP;
            } else {
                tapeTracks[deck] = Math.min(tapeTracks[deck], tracks.size()) - 1;
                double duration = Streams.duration(tracks.get(tapeTracks[deck]));
                tapeTicks[deck] = duration > 0 ? (long) (duration * 20) : 0;
            }
            changed();
        }

        /** Between the server's words, a client counts on what plays and moves what moves. */
        private void clientTick() {
            if (discPlaying && !discPaused) {
                discTicks++;
            }
            lastTrayOut = trayOut;
            trayOut = Mth.clamp(trayOut + (trayOpen ? 0.12f : -0.12f), 0, 1);
            for (int deck = 0; deck < 2; deck++) {
                lastReels[deck] = reels[deck];
                switch (motions[deck]) {
                    case PLAY -> {
                        tapeTicks[deck]++;
                        reels[deck] += 9;
                    }
                    case FORWARD -> {
                        tapeTicks[deck] += WIND;
                        reels[deck] += 55;
                    }
                    case REWIND -> {
                        tapeTicks[deck] = Math.max(0, tapeTicks[deck] - WIND);
                        reels[deck] -= 55;
                    }
                    case STOP -> {
                    }
                }
            }
        }

        private void playTrack(int entry) {
            if (entry < 0 || entry >= playlist().size()) {
                return;
            }
            track = entry;
            discTicks = 0;
            discPlaying = true;
            discPaused = false;
            restarts++;
            changed();
        }

        private void stopDisc() {
            discPlaying = false;
            discPaused = false;
            track = 0;
            discTicks = 0;
            changed();
        }

        private static boolean playable(ItemStack stack) {
            return stack.getItem() instanceof CustomDiscItem
                    || stack.getItem() instanceof VideoDiscItem && !stack.is(CreateMediaSystems.CASSETTE.get());
        }

        /** Takes the tape out of {@code deck}, which remembers where it was stopped. */
        private ItemStack eject(int deck) {
            ItemStack tape = tapes[deck];
            tape.set(CreateMediaSystems.TAPE_POSITION.get(), new TapePosition(tapeTracks[deck], (int) tapeTicks[deck]));
            tapes[deck] = ItemStack.EMPTY;
            motions[deck] = Motion.STOP;
            tapeTracks[deck] = 0;
            tapeTicks[deck] = 0;
            return tape;
        }

        /** {@code player} worked {@code control}; {@code value} is what a knob was turned to. */
        public void press(ServerPlayer player, Control control, int value) {
            if (!has(control.unit)) {
                return;
            }
            by = player;
            ItemStack held = player.getMainHandItem();
            if (control.cable != null) {
                plug(player, control.cable);
            } else if (control.kind == Control.Kind.TERMINAL) {
                HifiCableItem.pick(player, this, control == Control.SPEAKER_LEFT ? Streams.Channel.LEFT : Streams.Channel.RIGHT);
                return;
            } else if (control == Control.POWER) {
                power = !power;
                click(power ? 0.9f : 0.7f);
            } else if (control == Control.DOOR_1 || control == Control.DOOR_2) {
                // A door is opened by hand, live or not.
                int deck = control.index;
                if (tapes[deck].isEmpty() && held.is(CreateMediaSystems.CASSETTE.get())) {
                    tapes[deck] = held.split(1);
                    TapePosition at = tapes[deck].getOrDefault(CreateMediaSystems.TAPE_POSITION.get(), TapePosition.START);
                    tapeTracks[deck] = Mth.clamp(at.track(), 0, tracks(deck).size());
                    tapeTicks[deck] = Math.max(0, at.ticks());
                    click(1.2f);
                } else if (!tapes[deck].isEmpty() && held.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(eject(deck));
                    click(1.1f);
                }
            } else if (live(control.unit)) {
                work(player, control, value, held);
            } else {
                player.displayClientMessage(Component.translatable(!has(Unit.AMPLIFIER) ? "create_media_systems.hifi.no_amplifier"
                        : !power ? "create_media_systems.hifi.standby" : "create_media_systems.hifi.unplugged")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            changed();
        }

        /** A control of a unit that works. */
        private void work(ServerPlayer player, Control control, int value, ItemStack held) {
            switch (control) {
                case DIRECT -> direct = !direct;
                case WIDE -> wide = !wide;
                case BASS -> bass = !bass;
                case VOLUME -> setVolume(value);
                case BASS_LEVEL, EFFECT -> turn(control, value);
                case INPUT_DISC, INPUT_TUNER, INPUT_TAPE -> input = Input.byId(control.index);
                case PRESET_1, PRESET_2, PRESET_3, PRESET_4, PRESET_5, PRESET_6 -> preset = Preset.byId(control.index);
                case STATION_1, STATION_2, STATION_3, STATION_4, STATION_5, STATION_6 -> {
                    station = control.index;
                    tunedTicks = 0;
                    restarts++;
                }
                case TRAY, OPEN -> {
                    if (!trayOpen) {
                        trayOpen = true;
                        discPlaying = discPaused = false;
                    } else if (control == Control.TRAY && disc.isEmpty() && playable(held)) {
                        disc = held.split(1);
                    } else if (control == Control.TRAY && !disc.isEmpty() && held.isEmpty()) {
                        player.getInventory().placeItemBackInInventory(disc);
                        disc = ItemStack.EMPTY;
                    } else {
                        trayOpen = false;
                        track = 0;
                        playTrack(0);
                    }
                }
                case PLAY -> control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, player);
                case STOP -> control(VideoPlayerBlock.Action.STOP, 0, player);
                case PREVIOUS -> control(VideoPlayerBlock.Action.PREVIOUS, 0, player);
                case NEXT -> control(VideoPlayerBlock.Action.NEXT, 0, player);
                case PLAY_1, PLAY_2 -> {
                    int deck = control.index;
                    if (tapeTracks[deck] < tracks(deck).size()) {
                        motions[deck] = Motion.PLAY;
                        // One tape at a time: the amplifier has one tape input.
                        if (motions[1 - deck] == Motion.PLAY) {
                            motions[1 - deck] = Motion.STOP;
                        }
                    }
                }
                case REWIND_1, REWIND_2 -> wind(control.index, Motion.REWIND);
                case FORWARD_1, FORWARD_2 -> wind(control.index, Motion.FORWARD);
                case STOP_1, STOP_2 -> motions[control.index] = Motion.STOP;
                default -> {
                }
            }
        }

        private void wind(int deck, Motion motion) {
            if (!tapes[deck].isEmpty()) {
                motions[deck] = motion;
            }
        }

        private void setVolume(int value) {
            volume = Mth.clamp(value, 0, 100);
            if (level instanceof ServerLevel server) {
                for (BlockEntity emitter : Streams.emitters(server, worldPosition)) {
                    if (emitter instanceof SpeakerBlock.Entity speaker) {
                        speaker.refresh(server);
                    }
                }
            }
        }

        /** A remote's buttons work the disc player, and its slider the amplifier. */
        @Override
        public void control(VideoPlayerBlock.Action action, int value, @Nullable Player by) {
            if (action == VideoPlayerBlock.Action.VOLUME) {
                if (power()) {
                    setVolume(value);
                    changed();
                }
                return;
            }
            if (!live(Unit.DISC_PLAYER)) {
                return;
            }
            this.by = by;
            int last = playlist().size() - 1;
            switch (action) {
                case PLAY_PAUSE -> {
                    if (trayOpen) {
                        trayOpen = false;
                        playTrack(0);
                    } else if (!discPlaying) {
                        playTrack(Math.min(track, last));
                    } else {
                        discPaused = !discPaused;
                    }
                }
                case STOP -> stopDisc();
                // Stopped, they pick the track to play; playing, a moment into a track the first goes back to its top.
                case PREVIOUS -> {
                    if (!discPlaying) {
                        track = Math.max(track - 1, 0);
                    } else {
                        playTrack(discTicks > 60 ? track : Math.max(track - 1, 0));
                    }
                }
                case NEXT -> {
                    if (!discPlaying) {
                        track = Math.max(Math.min(track + 1, last), 0);
                    } else {
                        playTrack(track + 1);
                    }
                }
                case SELECT -> playTrack(value - 1);
                default -> {
                }
            }
            changed();
        }

        /** Plugs {@code cable} in with the audio cable {@code player} holds, or pulls it out for them. */
        private void plug(ServerPlayer player, Cable cable) {
            if (wired(cable)) {
                cables &= ~cable.bit();
                player.getInventory().placeItemBackInInventory(new ItemStack(CreateMediaSystems.HIFI_CABLE.get()));
                click(0.8f);
                return;
            }
            if (!has(Unit.AMPLIFIER) || !has(cable.unit)) {
                player.displayClientMessage(Component.translatable("create_media_systems.hifi.no_other_end")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            ItemStack held = player.getMainHandItem();
            if (!held.is(CreateMediaSystems.HIFI_CABLE.get())) {
                player.displayClientMessage(Component.translatable("create_media_systems.hifi.needs_cable")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            held.consume(1, player);
            cables |= cable.bit();
            click(1.3f);
        }

        /** The tuner's station {@code index} is set to {@code url} (empty: to nothing) by {@code player}. */
        void setStation(ServerPlayer player, int index, String url) {
            if (!has(Unit.TUNER) || index < 0 || index >= STATIONS) {
                return;
            }
            url = url.trim();
            if (!url.isEmpty() && !ServerConfig.isAllowed(url)) {
                player.displayClientMessage(Component.translatable("create_media_systems.url.rejected")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            stations[index] = url;
            station = index;
            tunedTicks = 0;
            restarts++;
            by = player;
            changed();
        }

        private void click(float pitch) {
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.4f, pitch);
        }

        /** Saved, and sent to clients, who draw it. */
        private void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public Component state() {
            return Component.translatable(playlist().isEmpty() ? "create_media_systems.video_player.empty"
                    : !discPlaying ? "create_media_systems.video_player.stopped"
                    : discPaused ? "create_media_systems.video_player.paused" : "create_media_systems.video_player.playing");
        }

        /** {@code ticks} as m:ss. */
        public static String clock(long ticks) {
            long seconds = Math.max(0, ticks) / 20;
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            for (int i = units.size() - 1; i >= 0; i--) {
                Unit unit = units.get(i);
                Component status = switch (unit) {
                    case AMPLIFIER -> !power ? line("standby")
                            : line("amplifier", Component.translatable("create_media_systems.hifi.input."
                            + input.name().toLowerCase(Locale.ROOT)), volume + "%");
                    case EQUALIZER -> line("equalizer", preset.name(), effect * 2 + "%");
                    case TUNER -> station < 0 || stations[station].isEmpty() ? line("no_station")
                            : line("tuner", station + 1);
                    case DISC_PLAYER -> playlist().isEmpty() ? state()
                            : Component.translatable("create_media_systems.video_player.track",
                            Math.min(track, playlist().size() - 1) + 1, playlist().size()).append("  ").append(state());
                    case CASSETTE_DECK -> line("deck", tapeLine(0), tapeLine(1));
                };
                boolean dead = unit != Unit.AMPLIFIER && !wired(Cable.system(unit));
                tooltip.add(Component.literal("     ").append(Component.translatable(
                        CreateMediaSystems.hifiUnit(unit).getDescriptionId()).withStyle(ChatFormatting.GOLD)));
                tooltip.add(Component.literal("       ").append(dead ? line("unplugged_short") : status)
                        .withStyle(dead ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
            return true;
        }

        private String tapeLine(int deck) {
            return tapes[deck].isEmpty() ? "—" : motions[deck].name().charAt(0) + " "
                    + Math.min(tapeTracks[deck] + 1, Math.max(1, tracks(deck).size())) + " " + clock(tapeTicks[deck]);
        }

        private static Component line(String key, Object... args) {
            return Component.translatable("create_media_systems.hifi.goggles." + key, args);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putIntArray("Units", units.stream().mapToInt(Enum::ordinal).toArray());
            tag.putInt("Cables", cables);
            tag.putBoolean("Power", power);
            tag.putInt("Input", input.ordinal());
            tag.putInt("Volume", volume);
            tag.putBoolean("Bass", bass);
            tag.putInt("BassLevel", bassLevel);
            tag.putBoolean("Wide", wide);
            tag.putBoolean("Direct", direct);
            tag.putInt("Preset", preset.ordinal());
            tag.putInt("Effect", effect);
            ListTag links = new ListTag();
            for (String link : stations) {
                links.add(StringTag.valueOf(link));
            }
            tag.put("Stations", links);
            tag.putInt("Station", station);
            tag.putLong("TunedTicks", tunedTicks);
            if (!disc.isEmpty()) {
                tag.put("Disc", disc.save(registries));
            }
            tag.putBoolean("TrayOpen", trayOpen);
            tag.putInt("Track", track);
            tag.putBoolean("DiscPlaying", discPlaying);
            tag.putBoolean("DiscPaused", discPaused);
            tag.putLong("DiscTicks", discTicks);
            for (int deck = 0; deck < 2; deck++) {
                if (!tapes[deck].isEmpty()) {
                    tag.put("Tape" + deck, tapes[deck].save(registries));
                }
                tag.putInt("Motion" + deck, motions[deck].ordinal());
                tag.putInt("TapeTrack" + deck, tapeTracks[deck]);
                tag.putLong("TapeTicks" + deck, tapeTicks[deck]);
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            units.clear();
            for (int id : tag.getIntArray("Units")) {
                Unit unit = Unit.byId(id);
                if (!units.contains(unit)) {
                    units.add(unit);
                }
            }
            cables = tag.getInt("Cables");
            power = tag.getBoolean("Power");
            input = Input.byId(tag.getInt("Input"));
            boolean held = level != null && level.isClientSide && heldStack == this;
            if (!held || heldKnob != Control.VOLUME) {
                volume = Mth.clamp(tag.getInt("Volume"), 0, 100);
            }
            bass = tag.getBoolean("Bass");
            if (!held || heldKnob != Control.BASS_LEVEL) {
                bassLevel = Mth.clamp(tag.getInt("BassLevel"), 0, 100);
            }
            wide = tag.getBoolean("Wide");
            direct = tag.getBoolean("Direct");
            preset = Preset.byId(tag.getInt("Preset"));
            if (!held || heldKnob != Control.EFFECT) {
                effect = Mth.clamp(tag.getInt("Effect"), 0, 100);
            }
            ListTag links = tag.getList("Stations", Tag.TAG_STRING);
            for (int i = 0; i < STATIONS; i++) {
                stations[i] = i < links.size() ? links.getString(i) : "";
            }
            station = Mth.clamp(tag.getInt("Station"), -1, STATIONS - 1);
            tunedTicks = tag.getLong("TunedTicks");
            disc = ItemStack.parseOptional(registries, tag.getCompound("Disc"));
            trayOpen = tag.getBoolean("TrayOpen");
            track = Math.max(0, tag.getInt("Track"));
            discPlaying = tag.getBoolean("DiscPlaying");
            discPaused = tag.getBoolean("DiscPaused");
            discTicks = tag.getLong("DiscTicks");
            for (int deck = 0; deck < 2; deck++) {
                tapes[deck] = ItemStack.parseOptional(registries, tag.getCompound("Tape" + deck));
                motions[deck] = Motion.byId(tag.getInt("Motion" + deck));
                tapeTracks[deck] = Math.max(0, tag.getInt("TapeTrack" + deck));
                tapeTicks[deck] = Math.max(0, tag.getLong("TapeTicks" + deck));
            }
        }

        /** Every client draws all of it. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Client → server: the control {@code control} of the stack at {@code pos} was worked, a knob to {@code value}. */
    public record Press(BlockPos pos, int control, int value) implements CustomPacketPayload {
        public static final Type<Press> TYPE = new Type<>(CreateMediaSystems.id("hifi_press"));
        public static final StreamCodec<ByteBuf, Press> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Press::pos,
                ByteBufCodecs.VAR_INT, Press::control,
                ByteBufCodecs.VAR_INT, Press::value,
                Press::new);

        @Override
        public Type<Press> type() {
            return TYPE;
        }
    }

    /** Client → server: station {@code index} of the tuner of the stack at {@code pos} is {@code url}. */
    public record SetStation(BlockPos pos, int index, String url) implements CustomPacketPayload {
        public static final Type<SetStation> TYPE = new Type<>(CreateMediaSystems.id("hifi_station"));
        public static final StreamCodec<ByteBuf, SetStation> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetStation::pos,
                ByteBufCodecs.VAR_INT, SetStation::index,
                StreamPackets.URL, SetStation::url,
                SetStation::new);

        @Override
        public Type<SetStation> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Press.TYPE, Press.CODEC, (payload, context) -> {
            Entity stack = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND, Entity.class);
            if (stack != null) {
                stack.press((ServerPlayer) context.player(), Control.byId(payload.control()), payload.value());
            }
        });
        registrar.playToServer(SetStation.TYPE, SetStation.CODEC, (payload, context) -> {
            Entity stack = Reach.blockEntity(context.player(), payload.pos(), Reach.HAND, Entity.class);
            if (stack != null) {
                stack.setStation((ServerPlayer) context.player(), payload.index(), payload.url());
            }
        });
    }
}
