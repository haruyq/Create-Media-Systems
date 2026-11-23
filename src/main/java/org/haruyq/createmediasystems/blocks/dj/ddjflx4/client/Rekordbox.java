package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * What a running rekordbox 7 has on its two decks, read from its memory (Windows only, and only ever read): rekordbox
 * tells nobody else. Two addresses in its program lead to everything: one to the tracks loaded, one to the players that
 * play them. They move with every build of rekordbox, so those of the builds known are kept here, and those of any
 * other are looked for (see {@link #discover}) and then kept in a file. One thread at a time may use it.
 */
public final class Rekordbox {
    /**
     * @param anlz     the track's analysis file (see {@link Anlz}), which also tells one track from another; empty
     *                 with no track loaded
     * @param bpm100   the track's own tempo in hundredths of a beat a minute; 0 if rekordbox found none
     * @param seconds  the track's length
     * @param position where it plays, in seconds of the track
     * @param bpm      the tempo it plays at now
     */
    public record Deck(String title, String artist, String key, int bpm100, int seconds, String anlz, double position, float bpm) {
        public boolean loaded() {
            return !anlz.isEmpty();
        }
    }

    /** The two addresses, from the program's base, of the builds known by {@link #build}: tracks, players. */
    private static final Map<String, long[]> KNOWN = Map.of(
            "6A97B2E4-6573000", new long[] {0x5F53DA8, 0x5E09790}); // 7.2.19
    // ponytail: where things are within a track and a player is taken to be as in 7.2.19, in every build; only the two
    // addresses are looked for. A build that moves these needs them found again (they moved once between 7.2.2 and
    // 7.2.19), and they would then belong in the offsets file too.
    /** In a track: pointers to its texts, and its numbers. */
    private static final int TITLE = 0x20, ARTIST = 0xD0, KEY = 0x210, BPM_100 = 0x3A8, SECONDS = 0x3D8, ANLZ = 0x3F0, TRACK_BYTES = 0x3F8;
    /** In a deck's player: the pointer to what plays, and in that the tempo, the position in samples and seconds, the tempo now. */
    private static final int PLAYING = 0x2C0, TEMPO = 0x120, SAMPLES = 0x130, POSITION = 0x138, BPM = 0x1A0, PLAYING_BYTES = 0x1A4;
    private static final String ANLZ_FOLDER = "/PIONEER/USBANLZ/";
    private static final int DECKS = 2, TEXT_BYTES = 512;
    private static final String[] NO_TEXTS = {"", "", "", ""};

    /** What jna-platform's Psapi leaves out. */
    private interface MemoryInfo extends StdCallLibrary {
        MemoryInfo INSTANCE = Native.load("psapi", MemoryInfo.class);

        boolean GetProcessMemoryInfo(WinNT.HANDLE process, Pointer counters, int size);
    }

    private final WinNT.HANDLE process;
    private final int pid;
    private final Memory buffer = new Memory(0x1000);
    private final IntByReference got = new IntByReference();
    /** Where the program is in its memory and how long it is there, and the two addresses. */
    private long base, image, tracks, players;
    /** Each deck's analysis file, title, artist and key as last read: read again when the first changes. */
    private final String[][] texts = {NO_TEXTS, NO_TEXTS};

    private Rekordbox(WinNT.HANDLE process, int pid) {
        this.process = process;
        this.pid = pid;
    }

    /**
     * The rekordbox running on this computer; null if none is, or if where it keeps its decks cannot be told yet:
     * of a build not known that takes a track loaded, with a tempo, to find. May take seconds. {@code offsets} is the
     * file that keeps what was found, which may also be written by hand: {@code build} (see {@link #locate}), and
     * {@code tracks} and {@code playback} in hexadecimal from the program's base.
     */
    @Nullable
    public static Rekordbox attach(Path offsets) {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8) {
            return null;
        }
        Rekordbox largest = null;
        long most = -1;
        for (ProcessHandle each : ProcessHandle.allProcesses().filter(p -> p.info().command()
                .map(command -> command.toLowerCase(Locale.ROOT).endsWith("\\rekordbox.exe")).orElse(false)).toList()) {
            WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false,
                    (int) each.pid());
            if (handle == null) {
                continue;
            }
            // Should helpers ever share its name: the one that plays is the largest by far.
            Memory counters = new Memory(72);
            long size = MemoryInfo.INSTANCE.GetProcessMemoryInfo(handle, counters, 72) ? counters.getLong(16) : 0;
            if (size > most) {
                if (largest != null) {
                    largest.close();
                }
                largest = new Rekordbox(handle, (int) each.pid());
                most = size;
            } else {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        }
        if (largest != null && largest.locate(offsets) && largest.read() != null) {
            return largest;
        }
        if (largest != null) {
            largest.close();
        }
        return null;
    }

    /** Finds the program and the two addresses. A build is named by its link time and its size in memory, as in {@link #KNOWN}. */
    private boolean locate(Path offsets) {
        WinDef.HMODULE[] modules = new WinDef.HMODULE[1];
        if (!Psapi.INSTANCE.EnumProcessModules(process, modules, Native.POINTER_SIZE, new IntByReference()) || modules[0] == null) {
            return false;
        }
        base = Pointer.nativeValue(modules[0].getPointer());
        ByteBuffer head = bytes(base, 0x1000);
        if (head == null) {
            return false;
        }
        int pe = head.getInt(0x3C);
        if (pe < 0 || pe > 0x1000 - 0x58) {
            return false;
        }
        image = head.getInt(pe + 0x50) & 0xFFFFFFFFL;
        String build = String.format(Locale.ROOT, "%08X-%X", head.getInt(pe + 8), image);
        long[] found = kept(offsets, build);
        if (found == null) {
            found = KNOWN.get(build);
        }
        if (found == null) {
            found = discover(head, pe);
            if (found != null) {
                keep(offsets, build, found);
            }
        }
        if (found != null) {
            tracks = base + found[0];
            players = base + found[1];
        }
        return found != null;
    }

    @Nullable
    private static long[] kept(Path offsets, String build) {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(offsets)) {
            properties.load(in);
            return !build.equals(properties.getProperty("build")) ? null : new long[] {
                    Long.parseLong(properties.getProperty("tracks", ""), 16), Long.parseLong(properties.getProperty("playback", ""), 16)};
        } catch (IOException | IllegalArgumentException none) {
            return null;
        }
    }

    private static void keep(Path offsets, String build, long[] found) {
        Properties properties = new Properties();
        properties.setProperty("build", build);
        properties.setProperty("tracks", Long.toHexString(found[0]).toUpperCase(Locale.ROOT));
        properties.setProperty("playback", Long.toHexString(found[1]).toUpperCase(Locale.ROOT));
        try (Writer out = Files.newBufferedWriter(offsets)) {
            properties.store(out, "Where this build of rekordbox.exe keeps its decks, from the program's base");
        } catch (IOException unwritable) {
            // Found again the next time.
        }
    }

    /**
     * Looks through the program's own data for the two addresses, by what they lead to. The tracks: a pointer to an
     * object whose second and third pointers are tracks, known by the path of their analysis file. The players: a
     * pointer to two pointers, one for each deck, whose player plays at about the tempo of that deck's track. Null
     * without a track loaded whose tempo rekordbox knows.
     */
    @Nullable
    private long[] discover(ByteBuffer head, int pe) {
        int sections = head.getShort(pe + 6) & 0xFFFF, table = pe + 24 + (head.getShort(pe + 20) & 0xFFFF);
        long at = 0;
        ByteBuffer data = null;
        for (int i = 0; i < sections && table + i * 40 + 40 <= 0x1000 && data == null; i++) {
            int section = table + i * 40;
            if (head.getLong(section) == 0x617461642EL) { // ".data"
                at = head.getInt(section + 12) & 0xFFFFFFFFL;
                data = bytes(base + at, head.getInt(section + 8) & ~7);
            }
        }
        if (data == null) {
            return null;
        }
        long foundTracks = -1;
        for (int i = 0; i + 8 <= data.capacity() && foundTracks < 0; i += 8) {
            long holder = data.getLong(i);
            for (int deck = 0; deck < DECKS && heap(holder); deck++) {
                long track = u64(holder + 8 + 8 * deck);
                if (heap(track) && text(u64(track + ANLZ)).contains(ANLZ_FOLDER)) {
                    foundTracks = at + i;
                    break;
                }
            }
        }
        if (foundTracks < 0) {
            return null;
        }
        tracks = base + foundTracks;
        long holder = u64(tracks);
        float[] tempo = new float[DECKS];
        for (int deck = 0; deck < DECKS; deck++) {
            long track = u64(holder + 8 + 8 * deck);
            if (heap(track) && text(u64(track + ANLZ)).contains(ANLZ_FOLDER)) {
                tempo[deck] = (int) u64(track + BPM_100) / 100f;
            }
        }
        if (tempo[0] <= 0 && tempo[1] <= 0) {
            return null;
        }
        for (int i = 0; i + 8 <= data.capacity(); i += 8) {
            long decks = data.getLong(i);
            boolean plays = heap(decks);
            for (int deck = 0; deck < DECKS && plays; deck++) {
                plays = tempo[deck] <= 0 || playsAt(u64(u64(decks + 8 * deck) + PLAYING), tempo[deck]);
            }
            if (plays) {
                return new long[] {foundTracks, at + i};
            }
        }
        return null;
    }

    /** Whether {@code playing} looks like what plays a track of {@code tempo}: about that tempo twice, and one position twice. */
    private boolean playsAt(long playing, float tempo) {
        if (!heap(playing) || !read(playing, PLAYING_BYTES)) {
            return false;
        }
        double exact = buffer.getDouble(TEMPO), seconds = buffer.getDouble(POSITION);
        long samples = buffer.getLong(SAMPLES);
        float now = buffer.getFloat(BPM);
        // The tempo fader reaches from standing still to twice as fast; a sample rate is 8 to 192 kHz.
        return exact > tempo * 0.4 && exact < tempo * 2.2 && now > tempo * 0.4 && now < tempo * 2.2
                && (samples == 0 ? seconds == 0 : samples > 0 && seconds > 0 && samples / seconds > 7_000 && samples / seconds < 200_000);
    }

    /** Whether {@code address} may be an object rekordbox made: aligned, within what a program may use, not in the program itself. */
    private boolean heap(long address) {
        return address > 0x10000 && address < 0x7FFF_FFFF_0000L && (address & 7) == 0 && (address < base || address >= base + image);
    }

    /** Both decks as they are now; null once rekordbox has gone. A dozen small reads: cheap enough for every tick. */
    @Nullable
    public Deck[] read() {
        if (!read(tracks, 8)) {
            return null;
        }
        long holder = buffer.getLong(0), decks = u64(players);
        Deck[] read = new Deck[DECKS];
        for (int deck = 0; deck < DECKS; deck++) {
            long track = heap(holder) ? u64(holder + 8 + 8 * deck) : 0;
            int bpm100 = 0, seconds = 0;
            if (heap(track) && read(track, TRACK_BYTES)) {
                bpm100 = buffer.getInt(BPM_100);
                seconds = buffer.getShort(SECONDS) & 0xFFFF;
                long title = buffer.getLong(TITLE), artist = buffer.getLong(ARTIST), key = buffer.getLong(KEY);
                String anlz = text(buffer.getLong(ANLZ));
                if (!anlz.equals(texts[deck][0])) {
                    texts[deck] = new String[] {anlz, text(title), text(artist), text(key)};
                }
            } else {
                texts[deck] = NO_TEXTS;
            }
            double position = 0;
            float bpm = 0;
            long playing = heap(decks) ? u64(u64(decks + 8 * deck) + PLAYING) : 0;
            if (heap(playing) && read(playing, PLAYING_BYTES)) {
                position = buffer.getDouble(POSITION);
                bpm = buffer.getFloat(BPM);
            }
            String[] of = texts[deck];
            read[deck] = new Deck(of[1], of[2], of[3], bpm100, seconds, of[0], position, bpm);
        }
        return read;
    }

    public int pid() {
        return pid;
    }

    public void close() {
        Kernel32.INSTANCE.CloseHandle(process);
    }

    /** Fills the buffer's first {@code size} bytes from {@code address} of rekordbox; false if they cannot all be read. */
    private boolean read(long address, int size) {
        return Kernel32.INSTANCE.ReadProcessMemory(process, new Pointer(address), buffer, size, got) && got.getValue() == size;
    }

    /** The pointer at {@code address}; 0 if it cannot be read. */
    private long u64(long address) {
        return address > 0x10000 && read(address, 8) ? buffer.getLong(0) : 0;
    }

    /** The text (UTF-8, ended by a zero) at {@code address}; empty if there is none. */
    private String text(long address) {
        // A text near the end of what can be read is read as far as its page goes.
        int length = TEXT_BYTES;
        if (address <= 0x10000 || !read(address, length) && !read(address, length = (int) (0x1000 - (address & 0xFFF)))) {
            return "";
        }
        byte[] bytes = buffer.getByteArray(0, length);
        int end = 0;
        while (end < length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** {@code size} bytes from {@code address} of rekordbox; null if they cannot all be read. */
    @Nullable
    private ByteBuffer bytes(long address, int size) {
        Memory block = new Memory(size);
        return Kernel32.INSTANCE.ReadProcessMemory(process, new Pointer(address), block, size, got) && got.getValue() == size
                ? ByteBuffer.wrap(block.getByteArray(0, size)).order(ByteOrder.LITTLE_ENDIAN) : null;
    }
}
