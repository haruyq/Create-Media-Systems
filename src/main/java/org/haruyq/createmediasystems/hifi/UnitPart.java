package org.haruyq.createmediasystems.hifi;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.DiscTransport;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.PlaylistView;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One unit's own state in a hi-fi stack. A stack keeps one of each unit, on it or not (a unit taken off keeps its
 * settings, as its item carries none), and saves them all in its own tag, under the keys they always had.
 */
abstract class UnitPart {
    final HifiStackBlock.Entity stack;

    UnitPart(HifiStackBlock.Entity stack) {
        this.stack = stack;
    }

    /** Its line in the stack's goggles, while it is plugged in. */
    abstract Component status();

    /** {@code player} worked {@code control} of it while it works; {@code value} is what a knob was turned to. */
    void work(ServerPlayer player, Control control, int value, ItemStack held) {
    }

    /** What its knob {@code control} is set to, 0 to 100. */
    int knob(Control control) {
        return 0;
    }

    /** Sets its knob {@code control}; on a client, here only, as it is turned (see HifiControls). */
    void turn(Control control, int value) {
    }

    /** It comes off the stack: what it holds goes into {@code items}, and what it plays stops. */
    void takeOut(List<ItemStack> items) {
    }

    abstract void save(CompoundTag tag, HolderLookup.Provider registries);

    abstract void load(CompoundTag tag, HolderLookup.Provider registries);

    static Component line(String key, Object... args) {
        return Component.translatable("create_media_systems.hifi.goggles." + key, args);
    }

    int knob(CompoundTag tag, String key, Control knob, int now) {
        return stack.turning() == knob ? now : Mth.clamp(tag.getInt(key), 0, 100);
    }

    /**
     * What a source unit plays for the amplifier's input (see HifiStackBlock.Input): which, which deck, which link, and
     * which run of its clock (see PlaybackClock#revision): a track played from its top again, or sought, is a new
     * playback.
     */
    record Playing(HifiStackBlock.Input input, int deck, String url, int run) {
    }

    abstract static class Source extends UnitPart {
        Source(HifiStackBlock.Entity stack) {
            super(stack);
        }

        /** What it plays now if the amplifier is set to it; null for nothing. */
        @Nullable
        abstract Playing playing();

        abstract PlaybackClock clock(Playing playing);

        /** Every clock it has: they all run on, heard or not. */
        abstract List<PlaybackClock> clocks();

        /** A server tick at {@code now}, after its clocks'; whether something of it moves. */
        boolean tick(long now) {
            return false;
        }

        /** A client tick: it counts on between the server's words, standing what {@code waits} on its listeners. */
        abstract void clientTick(long now, @Nullable Playing waits);

        boolean waits(@Nullable Playing waits, HifiStackBlock.Input input, int deck) {
            return waits != null && waits.input() == input && waits.deck() == deck;
        }
    }

    static final class Amplifier extends UnitPart {
        boolean power;
        /** Ticks left of WARM_UP; server only. */
        int warming;
        HifiStackBlock.Input input = HifiStackBlock.Input.DISC;
        /** Knobs go from 0 to 100. */
        int volume = 40;
        boolean bass;
        int bassLevel = 50;
        boolean wide;
        boolean direct;

        Amplifier(HifiStackBlock.Entity stack) {
            super(stack);
        }

        void switchPower() {
            power = !power;
            warming = power ? HifiStackBlock.WARM_UP : 0;
        }

        @Override
        Component status() {
            return !power ? line("standby") : line("amplifier", Component.translatable("create_media_systems.hifi.input."
                    + input.name().toLowerCase(Locale.ROOT)), volume + "%");
        }

        @Override
        void work(ServerPlayer player, Control control, int value, ItemStack held) {
            switch (control) {
                case DIRECT -> direct = !direct;
                case WIDE -> wide = !wide;
                case BASS -> bass = !bass;
                case VOLUME -> stack.setVolume(value);
                case BASS_LEVEL -> turn(control, value);
                case INPUT_DISC, INPUT_TUNER, INPUT_TAPE -> input = HifiStackBlock.Input.byId(control.index);
                default -> {
                }
            }
        }

        @Override
        int knob(Control control) {
            return control == Control.VOLUME ? volume : control == Control.BASS_LEVEL ? bassLevel : 0;
        }

        @Override
        void turn(Control control, int value) {
            value = Mth.clamp(value, 0, 100);
            if (control == Control.VOLUME) {
                volume = value;
            } else if (control == Control.BASS_LEVEL) {
                bassLevel = value;
            }
        }

        /** Its bass boost, as a band (Hz, Hz, dB) of the stack's tone. */
        void bassTo(List<Float> bands) {
            if (bass) {
                bands.add(25f);
                bands.add(110f);
                bands.add(2 + bassLevel / 100f * 8);
            }
        }

        @Override
        void takeOut(List<ItemStack> items) {
            power = false;
        }

        @Override
        void save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("Power", power);
            tag.putInt("Input", input.ordinal());
            tag.putInt("Volume", volume);
            tag.putBoolean("Bass", bass);
            tag.putInt("BassLevel", bassLevel);
            tag.putBoolean("Wide", wide);
            tag.putBoolean("Direct", direct);
        }

        @Override
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            power = tag.getBoolean("Power");
            input = HifiStackBlock.Input.byId(tag.getInt("Input"));
            volume = knob(tag, "Volume", Control.VOLUME, volume);
            bass = tag.getBoolean("Bass");
            bassLevel = knob(tag, "BassLevel", Control.BASS_LEVEL, bassLevel);
            wide = tag.getBoolean("Wide");
            direct = tag.getBoolean("Direct");
        }
    }

    static final class Equalizer extends UnitPart {
        /** The middle of each of its seven bands (Hz); each reaches {@link #BAND_REACH} either way. */
        static final float[] BAND_CENTRES = {63, 160, 400, 1000, 2500, 6300, 16000};
        private static final float BAND_REACH = 1.58f;

        HifiStackBlock.Preset preset = HifiStackBlock.Preset.FLAT;
        int effect = 50;

        Equalizer(HifiStackBlock.Entity stack) {
            super(stack);
        }

        @Override
        Component status() {
            return line("equalizer", preset.name(), effect * 2 + "%");
        }

        @Override
        void work(ServerPlayer player, Control control, int value, ItemStack held) {
            switch (control) {
                case EFFECT -> turn(control, value);
                case PRESET_1, PRESET_2, PRESET_3, PRESET_4, PRESET_5, PRESET_6 ->
                        preset = HifiStackBlock.Preset.byId(control.index);
                default -> {
                }
            }
        }

        @Override
        int knob(Control control) {
            return control == Control.EFFECT ? effect : 0;
        }

        @Override
        void turn(Control control, int value) {
            if (control == Control.EFFECT) {
                effect = Mth.clamp(value, 0, 100);
            }
        }

        /** Its preset at its effect level, as bands (Hz, Hz, dB) of the stack's tone. */
        void bandsTo(List<Float> bands) {
            for (int band = 0; band < BAND_CENTRES.length; band++) {
                float gain = Mth.clamp(preset.gains[band] * effect / 50f, -Tone.MAX_GAIN, Tone.MAX_GAIN);
                if (gain != 0) {
                    bands.add(BAND_CENTRES[band] / BAND_REACH);
                    bands.add(BAND_CENTRES[band] * BAND_REACH);
                    bands.add(gain);
                }
            }
        }

        @Override
        void save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("Preset", preset.ordinal());
            tag.putInt("Effect", effect);
        }

        @Override
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            preset = HifiStackBlock.Preset.byId(tag.getInt("Preset"));
            effect = knob(tag, "Effect", Control.EFFECT, effect);
        }
    }

    static final class Tuner extends Source {
        static final int STATIONS = 6;

        private final String[] stations = new String[STATIONS];
        /** The station tuned in; -1 for none. */
        int station = -1;
        final PlaybackClock clock = PlaybackClock.unattended();

        Tuner(HifiStackBlock.Entity stack) {
            super(stack);
            Arrays.fill(stations, "");
        }

        String station(int index) {
            return stations[index];
        }

        void set(int index, String url) {
            stations[index] = url;
            tune(index);
        }

        private void tune(int index) {
            station = index;
            clock.start(0);
        }

        @Override
        @Nullable
        Playing playing() {
            return station >= 0 && !stations[station].isEmpty()
                    ? new Playing(HifiStackBlock.Input.TUNER, 0, stations[station], clock.revision()) : null;
        }

        @Override
        PlaybackClock clock(Playing playing) {
            return clock;
        }

        @Override
        List<PlaybackClock> clocks() {
            return List.of(clock);
        }

        @Override
        void clientTick(long now, @Nullable Playing waits) {
            clock.tick(now, !waits(waits, HifiStackBlock.Input.TUNER, 0));
        }

        @Override
        Component status() {
            return station < 0 || stations[station].isEmpty() ? line("no_station") : line("tuner", station + 1);
        }

        @Override
        void work(ServerPlayer player, Control control, int value, ItemStack held) {
            switch (control) {
                case STATION_1, STATION_2, STATION_3, STATION_4, STATION_5, STATION_6 -> tune(control.index);
                default -> {
                }
            }
        }

        @Override
        void takeOut(List<ItemStack> items) {
            station = -1;
            clock.stop();
        }

        @Override
        void save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag links = new ListTag();
            for (String link : stations) {
                links.add(StringTag.valueOf(link));
            }
            tag.put("Stations", links);
            tag.putInt("Station", station);
            tag.putLong("TunedTicks", clock.position());
        }

        @Override
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag links = tag.getList("Stations", Tag.TAG_STRING);
            for (int i = 0; i < STATIONS; i++) {
                stations[i] = i < links.size() ? links.getString(i) : "";
            }
            station = Mth.clamp(tag.getInt("Station"), -1, STATIONS - 1);
            clock.load(tag.getLong("TunedTicks"), station >= 0);
        }
    }

    static final class DiscPlayer extends Source implements DiscTransport.Deck, PlaylistView {
        private static final DiscTransport.Keys KEYS =
                new DiscTransport.Keys("Track", "DiscPlaying", "DiscPaused", "DiscTicks");

        ItemStack disc = ItemStack.EMPTY;
        boolean trayOpen;
        final DiscTransport transport = new DiscTransport(this, true, PlaybackClock.unattended());
        /** Client only: how far the tray is out (0 to 1) this tick and the last. */
        float trayOut;
        float lastTrayOut;

        DiscPlayer(HifiStackBlock.Entity stack) {
            super(stack);
        }

        @Override
        public List<String> playlist() {
            return MediaItem.linksOf(disc);
        }

        @Override
        public int index() {
            return transport.index();
        }

        @Override
        public void changed() {
            stack.changed();
        }

        void close(@Nullable ServerPlayer by) {
            trayOpen = false;
            transport.insert(by);
        }

        void control(Remotable.Action action, int value, @Nullable Player by, long now) {
            if (action == Remotable.Action.PLAY_PAUSE && trayOpen) {
                trayOpen = false;
                transport.insert(by);
            } else {
                transport.control(action, value, by, now);
            }
        }

        @Override
        @Nullable
        Playing playing() {
            List<String> playlist = playlist();
            int track = transport.index();
            return stack.wired(Cable.LINE) && transport.playing() && !transport.paused() && !trayOpen
                    && track < playlist.size()
                    ? new Playing(HifiStackBlock.Input.DISC, 0, playlist.get(track), transport.clock().revision()) : null;
        }

        @Override
        PlaybackClock clock(Playing playing) {
            return transport.clock();
        }

        @Override
        List<PlaybackClock> clocks() {
            return List.of(transport.clock());
        }

        @Override
        boolean tick(long now) {
            if (!transport.playing() || transport.paused()) {
                return false;
            }
            transport.advance(now);
            return true;
        }

        @Override
        void clientTick(long now, @Nullable Playing waits) {
            transport.clock().tick(now, !waits(waits, HifiStackBlock.Input.DISC, 0));
            lastTrayOut = trayOut;
            trayOut = Mth.clamp(trayOut + (trayOpen ? 0.12f : -0.12f), 0, 1);
        }

        @Override
        Component status() {
            return playlist().isEmpty() ? transport.state() : track().append("  ").append(transport.state());
        }

        @Override
        void work(ServerPlayer player, Control control, int value, ItemStack held) {
            switch (control) {
                case TRAY, OPEN -> {
                    if (!trayOpen) {
                        trayOpen = true;
                        transport.stop();
                    } else if (control == Control.TRAY && disc.isEmpty() && MediaItem.fits(held, DiscKind.Slot.DISC_TRAY)) {
                        disc = held.split(1);
                    } else if (control == Control.TRAY && !disc.isEmpty() && held.isEmpty()) {
                        player.getInventory().placeItemBackInInventory(disc);
                        disc = ItemStack.EMPTY;
                    } else {
                        close(player);
                    }
                }
                case PLAY -> stack.control(Remotable.Action.PLAY_PAUSE, 0, player);
                case STOP -> stack.control(Remotable.Action.STOP, 0, player);
                case PREVIOUS -> stack.control(Remotable.Action.PREVIOUS, 0, player);
                case NEXT -> stack.control(Remotable.Action.NEXT, 0, player);
                default -> {
                }
            }
        }

        @Override
        void takeOut(List<ItemStack> items) {
            if (!disc.isEmpty()) {
                items.add(disc);
            }
            disc = ItemStack.EMPTY;
            trayOpen = false;
            transport.stop();
        }

        @Override
        void save(CompoundTag tag, HolderLookup.Provider registries) {
            if (!disc.isEmpty()) {
                tag.put("Disc", disc.save(registries));
            }
            tag.putBoolean("TrayOpen", trayOpen);
            transport.save(tag, KEYS);
        }

        @Override
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            disc = ItemStack.parseOptional(registries, tag.getCompound("Disc"));
            trayOpen = tag.getBoolean("TrayOpen");
            transport.load(tag, KEYS);
        }
    }

    static final class CassetteDeck extends Source {
        /** How many times faster than it plays a deck winds its tape. */
        static final int WIND = 30;

        private final ItemStack[] tapes = {ItemStack.EMPTY, ItemStack.EMPTY};
        private final HifiStackBlock.Motion[] motions = {HifiStackBlock.Motion.STOP, HifiStackBlock.Motion.STOP};
        /**
         * Where each deck's tape is: the track (at its end, the track after the last), and how far into it, on a
         * clock that runs while the deck plays.
         */
        private final int[] tracks = new int[2];
        private final PlaybackClock[] clocks = {PlaybackClock.unattended(), PlaybackClock.unattended()};
        /** Client only: how far each deck's reels have turned, this tick and the last. */
        final float[] reels = new float[2];
        final float[] lastReels = new float[2];

        CassetteDeck(HifiStackBlock.Entity stack) {
            super(stack);
        }

        ItemStack tape(int deck) {
            return tapes[deck];
        }

        HifiStackBlock.Motion motion(int deck) {
            return motions[deck];
        }

        int track(int deck) {
            return tracks[deck];
        }

        long ticks(int deck) {
            return clocks[deck].position();
        }

        List<String> tracks(int deck) {
            return MediaItem.linksOf(tapes[deck]);
        }

        /** The deck whose tape plays; -1 for neither. */
        int playingDeck() {
            for (int deck = 0; deck < 2; deck++) {
                if (motions[deck] == HifiStackBlock.Motion.PLAY && tracks[deck] < tracks(deck).size()) {
                    return deck;
                }
            }
            return -1;
        }

        @Override
        @Nullable
        Playing playing() {
            int deck = playingDeck();
            return deck < 0 ? null
                    : new Playing(HifiStackBlock.Input.TAPE, deck, tracks(deck).get(tracks[deck]), clocks[deck].revision());
        }

        @Override
        PlaybackClock clock(Playing playing) {
            return clocks[playing.deck()];
        }

        @Override
        List<PlaybackClock> clocks() {
            return List.of(clocks);
        }

        /**
         * A door, opened by hand whether the deck works or not: a tape {@code player} holds goes in, where it was
         * stopped; an empty hand takes the one there out. Whether it did either.
         */
        boolean door(ServerPlayer player, int deck, ItemStack held) {
            if (tapes[deck].isEmpty() && MediaItem.fits(held, DiscKind.Slot.TAPE_DECK)) {
                tapes[deck] = held.split(1);
                TapePosition at = tapes[deck].getOrDefault(HifiFeature.TAPE_POSITION.get(), TapePosition.START);
                tracks[deck] = Mth.clamp(at.track(), 0, tracks(deck).size());
                clocks[deck].seek(at.ticks());
                return true;
            }
            if (!tapes[deck].isEmpty() && held.isEmpty()) {
                player.getInventory().placeItemBackInInventory(eject(deck));
                return true;
            }
            return false;
        }

        private ItemStack eject(int deck) {
            ItemStack tape = tapes[deck];
            tape.set(HifiFeature.TAPE_POSITION.get(), new TapePosition(tracks[deck], (int) clocks[deck].position()));
            tapes[deck] = ItemStack.EMPTY;
            motions[deck] = HifiStackBlock.Motion.STOP;
            tracks[deck] = 0;
            clocks[deck].stop();
            return tape;
        }

        @Override
        void work(ServerPlayer player, Control control, int value, ItemStack held) {
            int deck = control.index;
            switch (control) {
                case PLAY_1, PLAY_2 -> {
                    if (tracks[deck] < tracks(deck).size()) {
                        move(deck, HifiStackBlock.Motion.PLAY);
                        if (motions[1 - deck] == HifiStackBlock.Motion.PLAY) {
                            move(1 - deck, HifiStackBlock.Motion.STOP);
                        }
                    }
                }
                case REWIND_1, REWIND_2 -> wind(deck, HifiStackBlock.Motion.REWIND);
                case FORWARD_1, FORWARD_2 -> wind(deck, HifiStackBlock.Motion.FORWARD);
                case STOP_1, STOP_2 -> move(deck, HifiStackBlock.Motion.STOP);
                default -> {
                }
            }
        }

        private void wind(int deck, HifiStackBlock.Motion motion) {
            if (!tapes[deck].isEmpty()) {
                move(deck, motion);
            }
        }

        private void move(int deck, HifiStackBlock.Motion motion) {
            motions[deck] = motion;
            if (motion == HifiStackBlock.Motion.PLAY) {
                clocks[deck].resume();
            } else {
                clocks[deck].pause(stack.getLevel().getGameTime());
            }
        }

        @Override
        boolean tick(long now) {
            boolean moving = false;
            for (int deck = 0; deck < 2; deck++) {
                List<String> tracks = tracks(deck);
                switch (motions[deck]) {
                    case PLAY -> {
                        moving = true;
                        if (this.tracks[deck] >= tracks.size()) {
                            move(deck, HifiStackBlock.Motion.STOP);
                            stack.changed();
                        } else if (clocks[deck].ended(tracks.get(this.tracks[deck]), now)) {
                            this.tracks[deck]++;
                            clocks[deck].start(0);
                            stack.changed();
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
            return moving;
        }

        /** Winds the tape of {@code deck} on or back, a track of unknown length taking two seconds; stops at either end. */
        private void wind(int deck, List<String> tracks) {
            PlaybackClock clock = clocks[deck];
            if (motions[deck] == HifiStackBlock.Motion.FORWARD) {
                if (this.tracks[deck] >= tracks.size()) {
                    move(deck, HifiStackBlock.Motion.STOP);
                    stack.changed();
                    return;
                }
                long at = clock.position() + WIND;
                double duration = Streams.duration(tracks.get(this.tracks[deck]));
                if (duration > 0 ? at >= duration * PlaybackClock.TICKS_PER_SECOND : at >= 40 * WIND) {
                    this.tracks[deck]++;
                    clock.seek(0);
                    stack.changed();
                } else {
                    clock.seek(at);
                }
                return;
            }
            long at = clock.position() - WIND;
            if (at >= 0) {
                clock.seek(at);
                return;
            }
            if (this.tracks[deck] == 0 || tracks.isEmpty()) {
                this.tracks[deck] = 0;
                clock.seek(0);
                move(deck, HifiStackBlock.Motion.STOP);
            } else {
                this.tracks[deck] = Math.min(this.tracks[deck], tracks.size()) - 1;
                double duration = Streams.duration(tracks.get(this.tracks[deck]));
                clock.seek(duration > 0 ? (long) (duration * PlaybackClock.TICKS_PER_SECOND) : 0);
            }
            stack.changed();
        }

        @Override
        void clientTick(long now, @Nullable Playing waits) {
            for (int deck = 0; deck < 2; deck++) {
                lastReels[deck] = reels[deck];
                PlaybackClock clock = clocks[deck];
                switch (motions[deck]) {
                    case PLAY -> {
                        boolean counts = !waits(waits, HifiStackBlock.Input.TAPE, deck);
                        clock.tick(now, counts);
                        if (counts) {
                            reels[deck] += 9;
                        }
                    }
                    case FORWARD -> {
                        clock.seek(clock.position() + WIND);
                        reels[deck] += 55;
                    }
                    case REWIND -> {
                        clock.seek(Math.max(0, clock.position() - WIND));
                        reels[deck] -= 55;
                    }
                    case STOP -> {
                    }
                }
            }
        }

        @Override
        Component status() {
            return line("deck", tapeLine(0), tapeLine(1));
        }

        private String tapeLine(int deck) {
            return tapes[deck].isEmpty() ? "—" : motions[deck].name().charAt(0) + " "
                    + Math.min(tracks[deck] + 1, Math.max(1, tracks(deck).size())) + " "
                    + HifiStackBlock.Entity.clock(ticks(deck));
        }

        @Override
        void takeOut(List<ItemStack> items) {
            for (int deck = 0; deck < 2; deck++) {
                if (!tapes[deck].isEmpty()) {
                    items.add(eject(deck));
                }
            }
        }

        @Override
        void save(CompoundTag tag, HolderLookup.Provider registries) {
            for (int deck = 0; deck < 2; deck++) {
                if (!tapes[deck].isEmpty()) {
                    tag.put("Tape" + deck, tapes[deck].save(registries));
                }
                tag.putInt("Motion" + deck, motions[deck].ordinal());
                tag.putInt("TapeTrack" + deck, tracks[deck]);
                tag.putLong("TapeTicks" + deck, clocks[deck].position());
            }
        }

        @Override
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            for (int deck = 0; deck < 2; deck++) {
                tapes[deck] = ItemStack.parseOptional(registries, tag.getCompound("Tape" + deck));
                motions[deck] = HifiStackBlock.Motion.byId(tag.getInt("Motion" + deck));
                tracks[deck] = Math.max(0, tag.getInt("TapeTrack" + deck));
                clocks[deck].load(tag.getLong("TapeTicks" + deck), motions[deck] == HifiStackBlock.Motion.PLAY);
            }
        }
    }
}
