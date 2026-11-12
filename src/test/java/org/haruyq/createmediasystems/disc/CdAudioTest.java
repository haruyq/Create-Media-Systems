package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.haruyq.createmediasystems.stream.DiscKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CdAudioTest {
    @Test
    void aFolderWithACdImageIsACdReadBySectors(@TempDir Path folder) throws Exception {
        assertNull(CdAudio.open(folder), "an empty folder is no CD");
        byte[] image = new byte[3 * CdAudio.SECTOR_BYTES];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) (i / CdAudio.SECTOR_BYTES * 16 + i % 7);
        }
        Files.write(folder.resolve("data.bin"), new byte[CdAudio.SECTOR_BYTES + 1]);
        assertNull(CdAudio.open(folder), "a .bin that is no whole sectors is no CD");
        Files.write(folder.resolve("album.bin"), image);
        try (CdAudio cd = CdAudio.open(folder)) {
            assertNotNull(cd);
            assertEquals(3, cd.sectors());
            assertArrayEquals(Arrays.copyOfRange(image, CdAudio.SECTOR_BYTES, image.length), cd.read(1, 2));
        }
    }

    @Test
    void macOsAudioCdTracksAreReadAcrossFilesInCdByteOrder(@TempDir Path volume) throws Exception {
        Files.writeString(volume.resolve(".TOC.plist"), "<plist/>");
        // Track 10 sorts after track 2 by number, not by name.
        byte[][] tracks = {sectors(2, 1), sectors(1, 50), sectors(3, 100)};
        Files.write(volume.resolve("1 Audio Track.aiff"), aiff(tracks[0]));
        Files.write(volume.resolve("2 Audio Track.aiff"), aiff(tracks[1]));
        Files.write(volume.resolve("10 Audio Track.aiff"), aiff(tracks[2]));
        assertEquals(DiscKind.CD, DiscSource.kind(volume));
        try (CdAudio cd = CdAudio.open(volume)) {
            assertInstanceOf(CdAudio.Aiff.class, cd);
            assertEquals(6, cd.sectors());
            byte[] got = cd.read(1, 4);
            byte[] want = new byte[4 * CdAudio.SECTOR_BYTES];
            System.arraycopy(tracks[0], CdAudio.SECTOR_BYTES, want, 0, CdAudio.SECTOR_BYTES);
            System.arraycopy(tracks[1], 0, want, CdAudio.SECTOR_BYTES, CdAudio.SECTOR_BYTES);
            System.arraycopy(tracks[2], 0, want, 2 * CdAudio.SECTOR_BYTES, 2 * CdAudio.SECTOR_BYTES);
            for (int i = 0; i < want.length; i += 2) {
                byte high = want[i];
                want[i] = want[i + 1];
                want[i + 1] = high;
            }
            assertArrayEquals(want, got);
            assertThrows(java.io.IOException.class, () -> cd.read(5, 2));
        }
    }

    private static byte[] sectors(int count, int seed) {
        byte[] bytes = new byte[count * CdAudio.SECTOR_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i * 7);
        }
        return bytes;
    }

    /** An AIFF file as macOS writes a CD track: FORM, a COMM chunk, then SSND with an offset of 0. */
    private static byte[] aiff(byte[] samples) {
        java.nio.ByteBuffer file = java.nio.ByteBuffer.allocate(12 + 26 + 16 + samples.length);
        file.putInt(0x464F524D).putInt(file.capacity() - 8).putInt(0x41494646);
        file.putInt(0x434F4D4D).putInt(18).putShort((short) 2).putInt(samples.length / 4).putShort((short) 16)
                .put(new byte[] {0x40, 0x0E, (byte) 0xAC, 0x44, 0, 0, 0, 0, 0, 0});
        file.putInt(0x53534E44).putInt(8 + samples.length).putInt(0).putInt(0).put(samples);
        return file.array();
    }
}
