package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class DriveClientTest {
    private static final List<String> MOUNTS = List.of(
            "/dev/nvme0n1p2 / ext4 rw,relatime 0 0",
            "/dev/sr0 /media/haru/MY\\040MOVIE udf ro,nosuid 0 0",
            "/dev/sdb1 /media/haru/STICK vfat rw 0 0",
            "/dev/loop3 /mnt/iso iso9660 ro 0 0");

    @Test
    void findsLinuxDiscsWhereTheyAreMountedThenTheDrivesThemselves() {
        assertEquals(List.of(Path.of("/media/haru/MY MOVIE"), Path.of("/mnt/iso"), Path.of("/dev", "sr0")),
                DriveClient.linuxDrives(MOUNTS, List.of("null", "sda", "sr0", "srx")));
    }

    @Test
    void ejectsOnlyAnOpticalDrive() {
        assertEquals("/dev/sr0", DriveClient.linuxDevice(Path.of("/media/haru/MY MOVIE"), MOUNTS));
        assertEquals("/dev/sr1", DriveClient.linuxDevice(Path.of("/dev/sr1"), MOUNTS));
        assertNull(DriveClient.linuxDevice(Path.of("/media/haru/STICK"), MOUNTS));
        assertNull(DriveClient.linuxDevice(Path.of("/mnt/iso"), MOUNTS), "an image mounted from a file has no tray");
    }
}
