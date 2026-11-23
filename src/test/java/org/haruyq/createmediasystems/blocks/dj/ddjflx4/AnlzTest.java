package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.client.Anlz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnlzTest {
    /** A tag: its code, its header (from byte 12 on, the code and the two lengths being the first twelve) and what follows. */
    private static byte[] tag(String code, byte[] header, byte[] body) {
        return ByteBuffer.allocate(12 + header.length + body.length).put(code.getBytes(StandardCharsets.ISO_8859_1))
                .putInt(12 + header.length).putInt(12 + header.length + body.length).put(header).put(body).array();
    }

    private static byte[] file(byte[]... tags) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        int length = 0x1C + Arrays.stream(tags).mapToInt(tag -> tag.length).sum();
        file.write(ByteBuffer.allocate(0x1C).put("PMAI".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x1C).putInt(length).array());
        for (byte[] tag : tags) {
            file.write(tag);
        }
        return file.toByteArray();
    }

    private static byte[] beatGrid() {
        ByteBuffer beats = ByteBuffer.allocate(3 * 8);
        for (int i = 0; i < 3; i++) {
            beats.putShort((short) (i + 1)).putShort((short) 12800).putInt(100 + i * 469);
        }
        return tag("PQTZ", ByteBuffer.allocate(12).putInt(4, 0x00800000).putInt(8, 3).array(), beats.array());
    }

    @Test
    void readsBeatsWaveAndHotCues(@TempDir Path folder) throws IOException {
        // Five columns of heights 1, 9, 31, 2, 4, each with its own colour: the taller of each two is kept, and the odd last one.
        short[] columns = {(short) (0x2000 | 1 << 2), (short) (0x4000 | 9 << 2), (short) (0x6000 | 31 << 2), (short) (0x8000 | 2 << 2),
                (short) (0xA000 | 4 << 2)};
        ByteBuffer wave = ByteBuffer.allocate(columns.length * 2);
        for (short column : columns) {
            wave.putShort(column);
        }
        // A memory cue, which is no hot cue, then hot cue C at two seconds.
        ByteBuffer cues = ByteBuffer.allocate(2 * 0x38);
        cues.put("PCP2".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x10).putInt(0x38).putInt(0).putInt(0x14, 500);
        cues.position(0x38).put("PCP2".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x10).putInt(0x38).putInt(3).putInt(0x38 + 0x14, 2000);
        Files.write(folder.resolve("ANLZ0000.DAT"), file(beatGrid()));
        Files.write(folder.resolve("ANLZ0000.EXT"), file(
                tag("PWV5", ByteBuffer.allocate(12).putInt(0, 2).putInt(4, columns.length).array(), wave.array()),
                tag("PWV3", new byte[12], new byte[7]),
                tag("PCO2", ByteBuffer.allocate(8).putInt(0, 1).putShort(4, (short) 2).array(), cues.array())));

        Anlz.Track track = Anlz.read(folder.resolve("ANLZ0000.DAT"));
        assertArrayEquals(new int[] {100, 569 | 1 << 30, 1038 | 2 << 30}, track.beats());
        assertArrayEquals(new short[] {columns[1], columns[2], columns[4]}, track.wave());
        assertArrayEquals(new int[] {-1, -1, 2000, -1, -1, -1, -1, -1}, track.cues());
    }

    @Test
    void aFileWithoutItsExtHasNoWave(@TempDir Path folder) throws IOException {
        Files.write(folder.resolve("ANLZ0000.DAT"), file(beatGrid()));
        Anlz.Track track = Anlz.read(folder.resolve("ANLZ0000.DAT"));
        assertEquals(3, track.beats().length);
        assertEquals(0, track.wave().length);
    }

    @Test
    void aCutOffOrForeignFileIsAnIoException(@TempDir Path folder) throws IOException {
        byte[] whole = file(beatGrid());
        Path dat = folder.resolve("ANLZ0000.DAT");
        for (int length = 0; length < whole.length; length++) {
            Files.write(dat, Arrays.copyOf(whole, length));
            if (length >= 0x1C && length < whole.length) {
                // Cut within the beat grid, be it in its header or its beats.
                assertEquals(length == 0x1C ? 0 : -1, beatsOrMinusOne(dat), "cut at " + length);
            } else {
                assertThrows(IOException.class, () -> Anlz.read(dat), "cut at " + length);
            }
        }
        // A grid that says it has more beats than its tag holds.
        byte[] lying = whole.clone();
        ByteBuffer.wrap(lying).putInt(0x1C + 0x14, 1_000_000);
        Files.write(dat, lying);
        assertThrows(IOException.class, () -> Anlz.read(dat));
        Files.write(dat, "ID3\3 this is some other file altogether".getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> Anlz.read(dat));
    }

    /** How many beats the file has, or -1 if it cannot be read: an IOException, and nothing else. */
    private static int beatsOrMinusOne(Path dat) {
        try {
            return Anlz.read(dat).beats().length;
        } catch (IOException unreadable) {
            return -1;
        }
    }

    /** Every analysis rekordbox keeps on this computer, where it is installed. */
    @Test
    void readsThisComputersAnalyses() throws IOException {
        String appData = System.getenv("APPDATA");
        assumeTrue(appData != null);
        Path root = Path.of(appData, "Pioneer", "rekordbox", "share", "PIONEER", "USBANLZ");
        assumeTrue(Files.isDirectory(root));
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".DAT")).toList();
        }
        for (Path dat : files) {
            Anlz.Track track = Anlz.read(dat);
            int[] beats = track.beats();
            for (int i = 1; i < beats.length; i++) {
                assertTrue((beats[i] & 0x3FFFFFFF) > (beats[i - 1] & 0x3FFFFFFF), "beats in order in " + dat);
            }
            assertEquals(Anlz.HOT_CUES, track.cues().length);
            if (beats.length > 1 && track.wave().length > 0) {
                // rekordbox lays its beat grid to the track's end, and the wave is as long as the track.
                double columns = (beats[beats.length - 1] & 0x3FFFFFFF) * Anlz.COLUMNS_PER_SECOND / 1000.0;
                assertEquals(1, track.wave().length / columns, 0.02, "wave as long as the beat grid in " + dat);
            }
        }
    }
}
