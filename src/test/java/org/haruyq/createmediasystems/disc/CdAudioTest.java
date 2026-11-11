package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
}
