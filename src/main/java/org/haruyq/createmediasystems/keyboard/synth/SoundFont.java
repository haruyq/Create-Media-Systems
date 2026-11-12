package org.haruyq.createmediasystems.keyboard.synth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.minecraft.client.sounds.JOrbisAudioStream;

/**
 * A SoundFont 2 bank: its presets as the {@link Region}s a key plays, and their samples. Reads .sf2 (16-bit PCM) and
 * .sf3 (each sample Ogg Vorbis, decoded the first time it is played). A region keeps its zone's generators in the
 * file's own units and its modulators, the standard's default ones among them, for the {@link Synth} to work out.
 */
public final class SoundFont {
    /** The bank of drum kits. */
    public static final int DRUMS = 128;
    /** Silence kept before and after each sample's own, for the Synth to interpolate over its ends. */
    static final int GUARD = 2;

    static final int GENERATORS = 61;
    /** Generators, by their number in the standard. Times are timecents, pitches cents, levels centibels. */
    static final int START = 0, START_COARSE = 4, LFO_TO_PITCH = 5, VIBRATO_TO_PITCH = 6, MOD_TO_PITCH = 7, CUTOFF = 8,
            RESONANCE = 9, LFO_TO_FILTER = 10, MOD_TO_FILTER = 11, LFO_TO_VOLUME = 13, CHORUS = 15, REVERB = 16, PAN = 17,
            LFO_DELAY = 21, LFO_RATE = 22, VIBRATO_DELAY = 23, VIBRATO_RATE = 24, MOD_DELAY = 25, MOD_ATTACK = 26,
            MOD_HOLD = 27, MOD_DECAY = 28, MOD_SUSTAIN = 29, MOD_RELEASE = 30, DELAY = 33, ATTACK = 34, HOLD = 35,
            DECAY = 36, SUSTAIN = 37, RELEASE = 38, KEY_TO_HOLD = 39, KEY_TO_DECAY = 40, ATTENUATION = 48,
            COARSE_TUNE = 51, FINE_TUNE = 52, SCALE_TUNING = 56;
    private static final int KEY_RANGE = 43, VELOCITY_RANGE = 44, INSTRUMENT = 41, SAMPLE = 53;
    /** Generators an instrument's zone alone may set: a preset's zone adds nothing to them. */
    private static final int[] INSTRUMENT_ONLY = {0, 1, 2, 3, 4, 12, 45, 46, 47, 50, 54, 57, 58};
    private static final int VORBIS = 0x10, ROM = 0x8000;
    /**
     * The modulators every instrument has unless it sets its own in their place: velocity to attenuation and to
     * cutoff, pressure and the modulation wheel to vibrato, volume, pan and expression, the reverb and chorus sends,
     * and the pitch wheel by its range.
     */
    private static final List<Modulator> DEFAULT_MODULATORS = List.of(new Modulator(0x0502, ATTENUATION, 960, 0),
            new Modulator(0x0102, CUTOFF, -2400, 0), new Modulator(0x000D, VIBRATO_TO_PITCH, 50, 0),
            new Modulator(0x0081, VIBRATO_TO_PITCH, 50, 0), new Modulator(0x0587, ATTENUATION, 960, 0),
            new Modulator(0x028A, PAN, 500, 0), new Modulator(0x058B, ATTENUATION, 960, 0),
            new Modulator(0x00DB, REVERB, 200, 0), new Modulator(0x00DD, CHORUS, 200, 0),
            new Modulator(0x020E, FINE_TUNE, 12700, 0x0010));

    /**
     * What moves a generator as a note is played: {@code amount} of it (in the generator's units) for a full
     * {@code source}, scaled by {@code amountSource} if there is one. A source is a controller as the standard packs
     * it: its number, whether that is a MIDI controller's, its direction, polarity and curve.
     */
    record Modulator(int source, int destination, float amount, int amountSource) {
        boolean sameAs(Modulator other) {
            return source == other.source && destination == other.destination && amountSource == other.amountSource;
        }
    }

    /** One sample as a preset plays it, over a range of keys and velocities. */
    public static final class Region {
        int keyLow, keyHigh, velocityLow, velocityHigh;
        /** Which sample (see {@link SoundFont#pcm}), and where in it it ends and loops (frames; an end of 0 or less is from its end). */
        int sample, end, loopStart, loopEnd;
        /** 0 plays it once through, 1 loops it, 3 loops it until the key is let go. */
        int loopMode;
        float sampleRate;
        /** The key it sounds as recorded at, and how far (cents) the recording is off that. */
        int rootKey, correction;
        /** Fixed key and velocity it plays at whatever was struck; -1 for those struck. */
        int fixedKey, fixedVelocity;
        /** Sounds of one class (above 0) cut each other off: an open hi-hat by a closed one. */
        int exclusiveClass;
        /** Its generators, the preset's added to the instrument's, in the file's units. */
        float[] generators;
        Modulator[] modulators;

        boolean plays(int key, int velocity) {
            return key >= keyLow && key <= keyHigh && velocity >= velocityLow && velocity <= velocityHigh;
        }
    }

    private record Header(int start, int end, int loopStart, int loopEnd, int rate, int pitch, int correction, int type) {
    }

    /**
     * A zone's generators (the values it sets, and which it does) and modulators; {@code link} is its instrument or
     * sample, -1 for a global zone.
     */
    private record Zone(short[] values, boolean[] set, int link, List<Modulator> modulators) {
        int range(int generator, boolean high) {
            return set[generator] ? (high ? values[generator] >> 8 : values[generator]) & 255 : high ? 127 : 0;
        }
    }

    private static final Executor DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CMS sound bank");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Integer, Region[]> presets = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Header[] headers;
    private final ByteBuffer samples;
    private final AtomicReferenceArray<short[]> decoded;

    private SoundFont(Header[] headers, ByteBuffer samples) {
        this.headers = headers;
        this.samples = samples;
        decoded = new AtomicReferenceArray<>(headers.length);
    }

    public static SoundFont read(ByteBuffer file) {
        ByteBuffer in = file.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (in.limit() < 12 || !id(in, 0).equals("RIFF") || !id(in, 8).equals("sfbk")) {
            throw new IllegalArgumentException("Not a SoundFont");
        }
        Map<String, ByteBuffer> chunks = new HashMap<>();
        collect(in, 12, in.limit(), chunks);
        for (String needed : List.of("smpl", "phdr", "pbag", "pgen", "inst", "ibag", "igen", "shdr")) {
            if (!chunks.containsKey(needed)) {
                throw new IllegalArgumentException("SoundFont without its " + needed + " chunk");
            }
        }
        ByteBuffer shdr = chunks.get("shdr");
        Header[] headers = new Header[Math.max(0, shdr.limit() / 46 - 1)];
        for (int i = 0; i < headers.length; i++) {
            int at = i * 46 + 20;
            headers[i] = new Header(shdr.getInt(at), shdr.getInt(at + 4), shdr.getInt(at + 8), shdr.getInt(at + 12),
                    shdr.getInt(at + 16), shdr.get(at + 20) & 255, shdr.get(at + 21), shdr.getShort(at + 24) & 0xFFFF);
        }
        SoundFont font = new SoundFont(headers, chunks.get("smpl"));
        ByteBuffer phdr = chunks.get("phdr"), inst = chunks.get("inst");
        Map<Integer, List<Zone>> instruments = new HashMap<>();
        for (int p = 0; p + 1 < phdr.limit() / 38; p++) {
            int at = p * 38;
            int key = (phdr.getShort(at + 22) & 0xFFFF) << 8 | phdr.getShort(at + 20) & 0xFFFF;
            List<Region> regions = new ArrayList<>();
            List<Zone> zones = zones(chunks.get("pbag"), chunks.get("pgen"), chunks.get("pmod"),
                    phdr.getShort(at + 24) & 0xFFFF, phdr.getShort(at + 62) & 0xFFFF, INSTRUMENT);
            Zone presetGlobal = global(zones);
            for (Zone presetZone : zones) {
                if (presetZone.link < 0 || presetZone.link + 1 >= inst.limit() / 22) {
                    continue;
                }
                List<Zone> instrument = instruments.computeIfAbsent(presetZone.link, i -> zones(chunks.get("ibag"),
                        chunks.get("igen"), chunks.get("imod"), inst.getShort(i * 22 + 20) & 0xFFFF,
                        inst.getShort(i * 22 + 42) & 0xFFFF, SAMPLE));
                Zone instrumentGlobal = global(instrument);
                for (Zone zone : instrument) {
                    Region region = zone.link < 0 || zone.link >= headers.length ? null
                            : region(headers[zone.link], zone, instrumentGlobal, presetZone, presetGlobal);
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
            font.presets.put(key, regions.toArray(Region[]::new));
            font.names.put(key, name(phdr, at));
        }
        return font;
    }

    private static void collect(ByteBuffer in, int from, int to, Map<String, ByteBuffer> chunks) {
        for (int at = from; at + 8 <= to;) {
            int size = in.getInt(at + 4);
            int body = at + 8;
            if (size < 0 || body + size > to) {
                throw new IllegalArgumentException("SoundFont cut short");
            }
            if (id(in, at).equals("LIST")) {
                collect(in, body + 4, body + size, chunks);
            } else {
                chunks.put(id(in, at), in.slice(body, size).order(ByteOrder.LITTLE_ENDIAN));
            }
            at = body + size + (size & 1);
        }
    }

    private static String id(ByteBuffer in, int at) {
        byte[] id = new byte[4];
        in.get(at, id);
        return new String(id, StandardCharsets.ISO_8859_1);
    }

    private static String name(ByteBuffer in, int at) {
        byte[] name = new byte[20];
        in.get(at, name);
        int length = 0;
        while (length < 20 && name[length] != 0) {
            length++;
        }
        return new String(name, 0, length, StandardCharsets.ISO_8859_1).strip();
    }

    private static List<Zone> zones(ByteBuffer bags, ByteBuffer generators, ByteBuffer modulators, int from, int to,
                                    int linkedBy) {
        List<Zone> zones = new ArrayList<>();
        for (int bag = from; bag < to && (bag + 1) * 4 + 4 <= bags.limit(); bag++) {
            short[] values = new short[GENERATORS];
            boolean[] set = new boolean[GENERATORS];
            int end = bags.getShort((bag + 1) * 4) & 0xFFFF;
            for (int g = bags.getShort(bag * 4) & 0xFFFF; g < end && g * 4 + 4 <= generators.limit(); g++) {
                int generator = generators.getShort(g * 4) & 0xFFFF;
                if (generator < GENERATORS) {
                    values[generator] = generators.getShort(g * 4 + 2);
                    set[generator] = true;
                }
            }
            List<Modulator> moved = new ArrayList<>();
            end = modulators == null ? 0 : bags.getShort((bag + 1) * 4 + 2) & 0xFFFF;
            for (int m = bags.getShort(bag * 4 + 2) & 0xFFFF; m < end && m * 10 + 10 <= modulators.limit(); m++) {
                int destination = modulators.getShort(m * 10 + 2) & 0xFFFF;
                // One that moves another modulator, or goes through a transform, is left out: no bank here has one.
                if (destination < GENERATORS && modulators.getShort(m * 10 + 8) == 0) {
                    override(moved, new Modulator(modulators.getShort(m * 10) & 0xFFFF, destination,
                            modulators.getShort(m * 10 + 4), modulators.getShort(m * 10 + 6) & 0xFFFF));
                }
            }
            zones.add(new Zone(values, set, set[linkedBy] ? values[linkedBy] & 0xFFFF : -1, moved));
        }
        return zones;
    }

    /** {@code modulator} takes the place of the one in {@code modulators} that is the same but for its amount. */
    private static void override(List<Modulator> modulators, Modulator modulator) {
        modulators.removeIf(modulator::sameAs);
        modulators.add(modulator);
    }

    /** The zone that sets what the others leave unset: the first, if it links to nothing. */
    private static Zone global(List<Zone> zones) {
        return !zones.isEmpty() && zones.get(0).link < 0 ? zones.get(0)
                : new Zone(new short[GENERATORS], new boolean[GENERATORS], -1, List.of());
    }

    private static Region region(Header header, Zone zone, Zone instrumentGlobal, Zone presetZone, Zone presetGlobal) {
        if ((header.type & ROM) != 0 || header.end <= header.start) {
            return null;
        }
        short[] g = new short[GENERATORS];
        g[CUTOFF] = 13500;
        for (int time : new int[]{LFO_DELAY, VIBRATO_DELAY, MOD_DELAY, MOD_ATTACK, MOD_HOLD, MOD_DECAY, MOD_RELEASE,
                DELAY, ATTACK, HOLD, DECAY, RELEASE}) {
            g[time] = -12000;
        }
        g[46] = g[47] = g[58] = -1;
        g[SCALE_TUNING] = 100;
        for (int i = 0; i < GENERATORS; i++) {
            g[i] = zone.set[i] ? zone.values[i] : instrumentGlobal.set[i] ? instrumentGlobal.values[i] : g[i];
        }
        boolean[] own = new boolean[GENERATORS];
        for (int i : INSTRUMENT_ONLY) {
            own[i] = true;
        }
        for (int i = 0; i < GENERATORS; i++) {
            if (!own[i] && i != KEY_RANGE && i != VELOCITY_RANGE && i != INSTRUMENT && i != SAMPLE) {
                g[i] += presetZone.set[i] ? presetZone.values[i] : presetGlobal.set[i] ? presetGlobal.values[i] : 0;
            }
        }
        Region r = new Region();
        Zone keys = zone.set[KEY_RANGE] ? zone : instrumentGlobal, presetKeys = presetZone.set[KEY_RANGE] ? presetZone : presetGlobal;
        Zone velocities = zone.set[VELOCITY_RANGE] ? zone : instrumentGlobal,
                presetVelocities = presetZone.set[VELOCITY_RANGE] ? presetZone : presetGlobal;
        r.keyLow = Math.max(keys.range(KEY_RANGE, false), presetKeys.range(KEY_RANGE, false));
        r.keyHigh = Math.min(keys.range(KEY_RANGE, true), presetKeys.range(KEY_RANGE, true));
        r.velocityLow = Math.max(velocities.range(VELOCITY_RANGE, false), presetVelocities.range(VELOCITY_RANGE, false));
        r.velocityHigh = Math.min(velocities.range(VELOCITY_RANGE, true), presetVelocities.range(VELOCITY_RANGE, true));
        if (r.keyLow > r.keyHigh || r.velocityLow > r.velocityHigh) {
            return null;
        }
        // An .sf3's sample is a Vorbis stream between start and end (bytes), its loop counted from its own start.
        boolean vorbis = (header.type & VORBIS) != 0;
        int length = vorbis ? Integer.MAX_VALUE : header.end - header.start;
        r.sample = zone.link;
        r.end = (int) Math.clamp((vorbis ? 0L : length) + g[1] + g[12] * 32768L, vorbis ? Integer.MIN_VALUE : 1, length);
        r.loopStart = (int) Math.clamp((vorbis ? header.loopStart : header.loopStart - header.start) + g[2] + g[45] * 32768L, 0, length);
        r.loopEnd = (int) Math.clamp((vorbis ? header.loopEnd : header.loopEnd - header.start) + g[3] + g[50] * 32768L, 0, length);
        r.loopMode = r.loopEnd - r.loopStart < 8 ? 0 : g[54] & 3;
        r.sampleRate = Math.clamp(header.rate, 400, 192000);
        r.rootKey = g[58] >= 0 && g[58] <= 127 ? g[58] : header.pitch <= 127 ? header.pitch : 60;
        r.correction = header.correction;
        r.fixedKey = g[46] > 127 ? -1 : g[46];
        r.fixedVelocity = g[47] > 127 ? -1 : g[47];
        r.exclusiveClass = g[57];
        r.generators = new float[GENERATORS];
        for (int i = 0; i < GENERATORS; i++) {
            r.generators[i] = g[i];
        }
        // As other players of these banks do: an attenuation counts for 0.4 of what it says.
        r.generators[ATTENUATION] *= 0.4f;
        // The instrument's own take the place of the defaults; the preset's come on top of both.
        List<Modulator> modulators = new ArrayList<>(DEFAULT_MODULATORS);
        instrumentGlobal.modulators.forEach(modulator -> override(modulators, modulator));
        zone.modulators.forEach(modulator -> override(modulators, modulator));
        List<Modulator> added = new ArrayList<>(presetGlobal.modulators);
        presetZone.modulators.forEach(modulator -> override(added, modulator));
        modulators.addAll(added);
        modulators.removeIf(modulator -> modulator.amount == 0 || modulator.source == 0);
        r.modulators = modulators.toArray(Modulator[]::new);
        return r;
    }

    /**
     * What {@code program} of {@code bank} plays: that preset, else the program in the first bank (a variation the
     * bank lacks), else the first program; a drum kit falls back to the standard kit. Empty for a bank without any.
     */
    public Region[] preset(int bank, int program) {
        Region[] found = presets.get(bank << 8 | program);
        if (found == null) {
            found = presets.get((bank == DRUMS ? DRUMS : 0) << 8 | (bank == DRUMS ? 0 : program));
        }
        if (found == null) {
            found = presets.get(bank == DRUMS ? DRUMS << 8 : 0);
        }
        return found == null ? new Region[0] : found;
    }

    /** The name of {@code program} of {@code bank}; null if the bank has none there. */
    public String name(int bank, int program) {
        return names.get(bank << 8 | program);
    }

    /**
     * The frames of sample {@code index}, with {@link #GUARD} frames of silence before and after; kept once made. Any
     * thread: two that ask at once both make it, and one of the two is kept.
     */
    short[] pcm(int index) {
        short[] pcm = decoded.get(index);
        if (pcm == null) {
            pcm = decode(headers[index]);
            decoded.compareAndSet(index, null, pcm);
        }
        return pcm;
    }

    private short[] decode(Header header) {
        ByteBuffer data = samples;
        int from = header.start, frames = header.end - header.start;
        if ((header.type & VORBIS) != 0) {
            byte[] ogg = new byte[Math.clamp((long) header.end - header.start, 0, Math.max(0, samples.limit() - header.start))];
            samples.get(header.start, ogg);
            try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(ogg))) {
                data = stream.readAll().order(ByteOrder.LITTLE_ENDIAN);
                from = data.position() / 2;
                frames = data.remaining() / 2;
            } catch (IOException | RuntimeException broken) {
                frames = 0;
            }
        }
        frames = Math.clamp(frames, 0, Math.max(0, data.limit() / 2 - from));
        short[] pcm = new short[frames + 2 * GUARD];
        data.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(from * 2).asShortBuffer().get(pcm, GUARD, frames);
        return pcm;
    }

    /**
     * Decodes what {@code regions} play ahead, in the background, so the first note of a program doesn't wait for it.
     * One after another: every channel of a new Synth asks for the same piano.
     */
    public void prepare(Region[] regions) {
        DECODER.execute(() -> {
            for (Region region : regions) {
                pcm(region.sample);
            }
        });
    }
}
