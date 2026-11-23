package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads what rekordbox's analysis of a track says of it, from the ANLZ files it keeps: the .DAT has the beat grid,
 * the .EXT beside it the coloured waveform and the hot cues. The layout is the one Deep Symmetry's "rekordbox export
 * structure analysis" documents: a PMAI header, then tags of a four-letter code, a header length and a whole length,
 * all numbers big-endian.
 */
public final class Anlz {
    /** Columns of {@link Track#wave} in a second: half of what the file has. */
    public static final int COLUMNS_PER_SECOND = 75;
    public static final int HOT_CUES = 8;

    /**
     * @param beats every beat: its time in ms in bits 0-29, its place in the bar less one (0..3) in bits 30-31
     * @param wave  {@link #COLUMNS_PER_SECOND} columns a second, each as the file has them: bits 15-13 red, 12-10
     *              green, 9-7 blue, 6-2 height
     * @param cues  hot cues A to H in ms; -1 where there is none
     */
    public record Track(int[] beats, short[] wave, int[] cues) {
    }

    private Anlz() {
    }

    /** The analysis whose .DAT file is {@code dat}. Without an .EXT beside it the wave is empty. */
    public static Track read(Path dat) throws IOException {
        Found found = new Found();
        scan(Files.readAllBytes(dat), found);
        String name = dat.getFileName().toString();
        Path ext = dat.resolveSibling(name.substring(0, name.lastIndexOf('.') + 1) + "EXT");
        if (name.contains(".") && Files.isRegularFile(ext)) {
            scan(Files.readAllBytes(ext), found);
        }
        // The newer list holds all the older one does, where there is one.
        return new Track(found.beats, found.wave, Arrays.stream(found.cues).anyMatch(cue -> cue >= 0) ? found.cues : found.oldCues);
    }

    private static final class Found {
        int[] beats = {};
        short[] wave = {};
        final int[] cues = none(), oldCues = none();

        private static int[] none() {
            int[] cues = new int[HOT_CUES];
            Arrays.fill(cues, -1);
            return cues;
        }
    }

    /** Takes from the file {@code bytes} what its tags have of {@code found}. */
    static void scan(byte[] bytes, Found found) throws IOException {
        ByteBuffer file = ByteBuffer.wrap(bytes);
        need(bytes.length >= 12 && code(file, 0).equals("PMAI"), "not an analysis file");
        for (long at = length(file, 4, 12, bytes.length); at < bytes.length; ) {
            need(bytes.length - at >= 12, "cut off in a tag's header");
            int start = (int) at;
            int whole = length(file, start + 8, 12, bytes.length - start);
            int header = length(file, start + 4, 12, whole);
            ByteBuffer tag = file.slice(start, whole);
            switch (code(file, start)) {
                case "PQTZ" -> found.beats = beats(tag, header);
                case "PWV5" -> found.wave = wave(tag, header);
                case "PCOB" -> cues(tag, header, 0x12, "PCPT", 0x20, found.oldCues);
                case "PCO2" -> cues(tag, header, 0x10, "PCP2", 0x14, found.cues);
                default -> {
                }
            }
            at += whole;
        }
    }

    /** Beats are eight bytes: place in the bar (1..4), tempo, time in ms. */
    private static int[] beats(ByteBuffer tag, int header) throws IOException {
        need(header >= 0x18, "short beat grid");
        int count = tag.getInt(0x14);
        need(count >= 0 && count <= (tag.capacity() - header) / 8, "beat grid longer than its tag");
        int[] beats = new int[count];
        for (int i = 0; i < count; i++) {
            int at = header + i * 8;
            beats[i] = tag.getInt(at + 4) & 0x3FFFFFFF | (tag.getShort(at) - 1 & 3) << 30;
        }
        return beats;
    }

    /** The colour waveform has 150 columns of two bytes a second: of each two, the taller is kept. */
    private static short[] wave(ByteBuffer tag, int header) throws IOException {
        need(header >= 0x14 && tag.getInt(0x0C) == 2, "unknown waveform");
        int count = tag.getInt(0x10);
        need(count >= 0 && count <= (tag.capacity() - header) / 2, "waveform longer than its tag");
        short[] wave = new short[(count + 1) / 2];
        for (int i = 0; i < count; i++) {
            short column = tag.getShort(header + i * 2);
            if ((i & 1) == 0 || (column & 0x7C) > (wave[i / 2] & 0x7C)) {
                wave[i / 2] = column;
            }
        }
        return wave;
    }

    /**
     * A cue list's entries are tags themselves, {@code entry} their code: each has which hot cue it is at 0x0C (1 for
     * A; 0 for a memory cue, which is no hot cue) and its time in ms at {@code time}.
     */
    private static void cues(ByteBuffer tag, int header, int countAt, String entry, int time, int[] cues) throws IOException {
        need(header >= countAt + 2, "short cue list");
        int count = tag.getShort(countAt) & 0xFFFF;
        for (int at = header, i = 0; i < count; i++) {
            need(tag.capacity() - at >= time + 4 && code(tag, at).equals(entry), "cue list longer than its tag");
            int hotCue = tag.getInt(at + 0x0C);
            if (hotCue >= 1 && hotCue <= HOT_CUES) {
                cues[hotCue - 1] = tag.getInt(at + time);
            }
            at += length(tag, at + 8, time + 4, tag.capacity() - at);
        }
    }

    private static String code(ByteBuffer from, int at) {
        byte[] code = new byte[4];
        from.get(at, code);
        return new String(code, StandardCharsets.ISO_8859_1);
    }

    /** The length at {@code at}, which is to be {@code least} at least and {@code most} at most. */
    private static int length(ByteBuffer from, int at, int least, int most) throws IOException {
        int length = from.getInt(at);
        need(length >= least && length <= most, "a length of " + length + " where " + least + " to " + most + " fit");
        return length;
    }

    private static void need(boolean holds, String otherwise) throws IOException {
        if (!holds) {
            throw new IOException("Unreadable rekordbox analysis: " + otherwise);
        }
    }
}
