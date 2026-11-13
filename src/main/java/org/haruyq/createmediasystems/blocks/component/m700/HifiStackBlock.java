package org.haruyq.createmediasystems.blocks.component.m700;

import org.haruyq.createmediasystems.stream.MediaSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Downloads;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;
import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.haruyq.createmediasystems.stream.VideoSource;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

/** A hi-fi system of separate units (see Unit) stacked in one block, each placed onto the stack with its own item. */
public class HifiStackBlock extends HorizontalDirectionalBlock implements EntityBlock, SableCompat.SilentAssemblyRemoval {
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
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof Entity stack) {
            for (ItemStack item : stack.everything()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), item);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return level.getBlockEntity(pos) instanceof Entity stack && !stack.units.isEmpty()
                ? new ItemStack(HifiFeature.hifiUnit(stack.units.getLast())) : ItemStack.EMPTY;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Both sides: a client counts its displays on between the server's words.
        return type != HifiFeature.HIFI_STACK_ENTITY.get() ? null : (l, p, s, be) -> ((Entity) be).tick();
    }

    public enum Input {
        DISC(Unit.DISC_PLAYER), TUNER(Unit.TUNER), TAPE(Unit.CASSETTE_DECK);

        final Unit unit;

        Input(Unit unit) {
            this.unit = unit;
        }

        static Input byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

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

    /**
     * Ticks after switching on before the speakers play, as an amplifier's protection relay clicks in: the power
     * switch pressed over and over starts nothing, instead of every listener loading the track each time.
     */
    static final int WARM_UP = 20;

    public static class Entity extends BlockEntity implements VideoSource, Remotable, IHaveGoggleInformation, MediaSource.Captured {
        /** Bottom first. */
        private final List<Unit> units = new ArrayList<>();
        /** The cables plugged in (see Cable#bit). */
        private int cables;

        private final UnitPart.Amplifier amplifier = new UnitPart.Amplifier(this);
        private final UnitPart.Equalizer equalizer = new UnitPart.Equalizer(this);
        private final UnitPart.Tuner tuner = new UnitPart.Tuner(this);
        private final UnitPart.DiscPlayer discPlayer = new UnitPart.DiscPlayer(this);
        private final UnitPart.CassetteDeck deck = new UnitPart.CassetteDeck(this);
        /** Each unit's own state, saved in that order. */
        private final Map<Unit, UnitPart> parts = new EnumMap<>(Map.of(Unit.AMPLIFIER, amplifier,
                Unit.EQUALIZER, equalizer, Unit.TUNER, tuner, Unit.DISC_PLAYER, discPlayer, Unit.CASSETTE_DECK, deck));

        /** Whether what is heard still waits for its listeners to load it: its time stands until they have. */
        private boolean loading;
        /** What its speakers were last told to play (see tick); not saved. */
        @Nullable
        private UnitPart.Playing begun;
        /** Client only: the knob this player turns (see HifiControls), which the server's word leaves as it is meanwhile. */
        @Nullable
        private Control turning;
        @Nullable
        private Player by;
        /** How much of the video its disc plays its listeners have downloaded. Not saved. */
        private final Downloads downloads = new Downloads();

        public Entity(BlockPos pos, BlockState state) {
            super(HifiFeature.HIFI_STACK_ENTITY.get(), pos, state);
        }

        public List<Unit> units() {
            return units;
        }

        UnitPart.Amplifier amplifier() {
            return amplifier;
        }

        UnitPart.Equalizer equalizer() {
            return equalizer;
        }

        UnitPart.Tuner tuner() {
            return tuner;
        }

        UnitPart.DiscPlayer discPlayer() {
            return discPlayer;
        }

        UnitPart.CassetteDeck deck() {
            return deck;
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

        public boolean live(Unit unit) {
            return has(unit) && power() && (unit == Unit.AMPLIFIER || wired(Cable.system(unit)));
        }

        public boolean power() {
            return has(Unit.AMPLIFIER) && amplifier.power;
        }

        private UnitPart.Source source(Input input) {
            return switch (input) {
                case DISC -> discPlayer;
                case TUNER -> tuner;
                case TAPE -> deck;
            };
        }

        private List<UnitPart.Source> sources() {
            return List.of(discPlayer, deck, tuner);
        }

        public boolean loading(Input input) {
            UnitPart.Playing playing = loading ? audible() : null;
            return playing != null && playing.input() == input;
        }

        /** What a knob is set to, 0 to 100. */
        public int knob(Control control) {
            return parts.get(control.unit).knob(control);
        }

        /** Sets a knob; on a client, here only, as it is turned (see HifiControls). */
        public void turn(Control control, int value) {
            parts.get(control.unit).turn(control, value);
        }

        @Nullable
        Control turning() {
            return turning;
        }

        /** The knob this client turns from now on; null once let go. */
        void turning(@Nullable Control knob) {
            turning = knob;
        }

        public boolean canAdd(Unit unit) {
            return !has(unit) && height() + unit.height <= Unit.ROOM;
        }

        public void add(Unit unit) {
            units.add(unit);
            changed();
        }

        /** Sets it up as a scene shows it (see HifiClient): switched on or not, with {@code wired} plugged in. */
        public void arrange(boolean on, Cable... wired) {
            amplifier.power = on;
            cables = 0;
            for (Cable cable : wired) {
                cables |= cable.bit();
            }
            changed();
        }

        void pop(Player player) {
            if (units.isEmpty()) {
                return;
            }
            Unit unit = units.getLast();
            List<ItemStack> items = contents(unit);
            units.removeLast();
            items.forEach(player.getInventory()::placeItemBackInInventory);
            level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1, 0.8f);
            if (units.isEmpty()) {
                level.removeBlock(worldPosition, false);
            } else {
                changed();
            }
        }

        List<ItemStack> everything() {
            List<ItemStack> items = new ArrayList<>();
            for (Unit unit : List.copyOf(units)) {
                items.addAll(contents(unit));
            }
            units.clear();
            return items;
        }

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
                items.add(new ItemStack(HifiFeature.HIFI_CABLE.get(), unplugged));
            }
            parts.get(unit).takeOut(items);
            items.add(new ItemStack(HifiFeature.hifiUnit(unit)));
            return items;
        }

        @Override
        public List<String> playlist() {
            return discPlayer.playlist();
        }

        @Override
        public int index() {
            return discPlayer.index();
        }

        @Nullable
        private UnitPart.Playing audible() {
            if (!power() || amplifier.warming > 0 || !live(amplifier.input.unit)) {
                return null;
            }
            return source(amplifier.input).playing();
        }

        /** What its speakers play, once tick has told them of it, as far in as its clock is. */
        @Override
        @Nullable
        public Streams.Source nowPlaying() {
            UnitPart.Playing playing = audible();
            if (playing == null || !playing.equals(begun)) {
                return null;
            }
            return playing.input() == Input.DISC ? discPlayer.transport.source(worldPosition, MediaItem.kindOf(discPlayer.disc))
                    : new Streams.Source(playing.url(), source(playing.input()).clock(playing).position(), null);
        }

        @Override
        public float volume() {
            return amplifier.volume / 100f;
        }

        /** Its volume knob turned up slowly at first, as a real one; silent while switched off. */
        @Override
        public float gain() {
            return power() ? (float) Math.pow(volume(), 1.5) : 0;
        }

        @Override
        public Tone tone() {
            if (!power()) {
                return Tone.FLAT;
            }
            List<Float> bands = new ArrayList<>();
            if (live(Unit.EQUALIZER) && !(amplifier.direct && amplifier.input == Input.DISC)) {
                equalizer.bandsTo(bands);
            }
            amplifier.bassTo(bands);
            if (amplifier.input == Input.TAPE) {
                bands.add(9000f);
                bands.add(20000f);
                bands.add(-6f);
            }
            float[] triples = new float[bands.size()];
            for (int i = 0; i < triples.length; i++) {
                triples[i] = bands.get(i);
            }
            return new Tone(triples, amplifier.wide ? 1.6f : 1);
        }

        void tick() {
            if (level == null) {
                return;
            }
            long now = level.getGameTime();
            if (level.isClientSide) {
                // Between the server's words, a client counts on what plays and moves what moves; what is heard
                // stands still while its listeners load it, as on the server.
                UnitPart.Playing waits = loading ? audible() : null;
                sources().forEach(source -> source.clientTick(now, waits));
                return;
            }
            if (amplifier.warming > 0) {
                amplifier.warming--;
            }
            UnitPart.Playing playing = audible();
            if (!Objects.equals(playing, begun)) {
                begun = playing;
                downloads.clear();
                if (playing != null) {
                    Streams.loading(this, by != null && !by.isRemoved() ? by : null,
                            source(playing.input()).clock(playing), now);
                }
            }
            PlaybackClock heard = playing == null ? null : source(playing.input()).clock(playing);
            for (UnitPart.Source source : sources()) {
                for (PlaybackClock clock : source.clocks()) {
                    if (clock == heard) {
                        Streams.tick(this, clock, now);
                    } else {
                        clock.tick(now);
                    }
                }
            }
            boolean moving = false;
            for (UnitPart.Source source : sources()) {
                moving |= source.tick(now);
            }
            // Clients hold their counters while it loads, and count on once it plays.
            boolean waits = playing != null && !Streams.loaded(this);
            if (waits != loading) {
                loading = waits;
                changed();
            }
            if (moving) {
                setChanged();
                if (now % 100 == 0) {
                    changed();
                }
            }
        }

        @Override
        public void tickCaptured(long now) {
            if (amplifier.warming > 0) amplifier.warming--;
            for (UnitPart.Source source : sources()) {
                for (PlaybackClock clock : source.clocks()) clock.tick(now);
                source.tick(now);
            }
            begun = audible();
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
                amplifier.switchPower();
                click(amplifier.power ? 0.9f : 0.7f);
            } else if (control == Control.DOOR_1 || control == Control.DOOR_2) {
                boolean empty = deck.tape(control.index).isEmpty();
                if (deck.door(player, control.index, held)) {
                    click(empty ? 1.2f : 1.1f);
                }
            } else if (live(control.unit)) {
                parts.get(control.unit).work(player, control, value, held);
            } else {
                player.displayClientMessage(Component.translatable(!has(Unit.AMPLIFIER) ? "create_media_systems.hifi.no_amplifier"
                        : !amplifier.power ? "create_media_systems.hifi.standby" : "create_media_systems.hifi.unplugged")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            changed();
        }

        void setVolume(int value) {
            amplifier.volume = Mth.clamp(value, 0, 100);
            if (level instanceof ServerLevel server) {
                Streams.changed(server, worldPosition);
            }
        }

        @Override
        public void control(Action action, int value, @Nullable Player by) {
            if (action == Action.VOLUME) {
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
            discPlayer.control(action, value, by, level.getGameTime());
            changed();
        }

        @Override
        public void downloaded(ServerPlayer player, String url, float fraction) {
            UnitPart.Playing playing = audible();
            if (playing != null && playing.input() == Input.DISC && playing.url().equals(url) && downloads.told(player, fraction)) {
                changed();
            }
        }

        private void plug(ServerPlayer player, Cable cable) {
            if (wired(cable)) {
                cables &= ~cable.bit();
                player.getInventory().placeItemBackInInventory(new ItemStack(HifiFeature.HIFI_CABLE.get()));
                click(0.8f);
                return;
            }
            if (!has(Unit.AMPLIFIER) || !has(cable.unit)) {
                player.displayClientMessage(Component.translatable("create_media_systems.hifi.no_other_end")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            ItemStack held = player.getMainHandItem();
            if (!held.is(HifiFeature.HIFI_CABLE.get())) {
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
            if (!has(Unit.TUNER) || index < 0 || index >= UnitPart.Tuner.STATIONS) {
                return;
            }
            url = url.trim();
            if (!url.isEmpty() && !UrlPolicy.isAllowed(url)) {
                player.displayClientMessage(Component.translatable("create_media_systems.url.rejected")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            tuner.set(index, url);
            by = player;
            changed();
        }

        private void click(float pitch) {
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.4f, pitch);
        }

        void changed() {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public Component state() {
            return discPlayer.transport.state();
        }

        /** {@code ticks} into a track, as its counter shows it (see PlaybackClock#format). */
        public static String clock(long ticks) {
            return PlaybackClock.format(ticks / (double) PlaybackClock.TICKS_PER_SECOND);
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
            tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
            for (int i = units.size() - 1; i >= 0; i--) {
                Unit unit = units.get(i);
                boolean dead = unit != Unit.AMPLIFIER && !wired(Cable.system(unit));
                tooltip.add(Component.literal("     ").append(Component.translatable(
                        HifiFeature.hifiUnit(unit).getDescriptionId()).withStyle(ChatFormatting.GOLD)));
                tooltip.add(Component.literal("       ").append(dead ? UnitPart.line("unplugged_short") : parts.get(unit).status())
                        .withStyle(dead ? ChatFormatting.RED : ChatFormatting.GRAY));
                if (unit == Unit.DISC_PLAYER && !dead && discPlayer.transport.playing()) {
                    downloads.addTo(tooltip, "       ");
                }
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putIntArray("Units", units.stream().mapToInt(Enum::ordinal).toArray());
            tag.putInt("Cables", cables);
            parts.values().forEach(part -> part.save(tag, registries));
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
            parts.values().forEach(part -> part.load(tag, registries));
            loading = tag.getBoolean("Loading");
            downloads.read(tag);
        }

        /** Every client draws all of it; whether it loads is for them alone, not kept. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveWithoutMetadata(registries);
            tag.putBoolean("Loading", loading);
            downloads.write(tag);
            return tag;
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
