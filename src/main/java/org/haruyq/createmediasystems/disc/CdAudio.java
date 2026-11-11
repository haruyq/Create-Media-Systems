package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

/**
 * An audio CD's sound: 44.1 kHz 16-bit stereo, little-endian, in sectors of 1/75 s, from its first track to its
 * last as one piece. FFmpeg's Windows builds read no audio CD, so the sectors are read here and piped to it.
 */
public interface CdAudio extends AutoCloseable {
    int RATE = 44100;
    /** Stereo samples in a sector, each four bytes. */
    int SECTOR_SAMPLES = 588;
    int SECTOR_BYTES = SECTOR_SAMPLES * 4;

    long sectors();

    /** Sectors {@code from} to {@code from + count}, counted from the first track. */
    byte[] read(long from, int count) throws IOException;

    @Override
    void close();

    /**
     * Where the audio CD at {@code root} is: the drive itself, whose tracks Windows lists as Track01.cda and on, or
     * the image in a folder; null if it has none.
     */
    @Nullable
    static Path find(Path root) {
        if (root.getFileName() == null) {
            return Files.exists(root.resolve("Track01.cda")) ? root : null;
        }
        // ponytail: any .bin whose size is whole sectors counts as CD audio; read its .cue for AUDIO tracks if data images turn up.
        try (var files = Files.list(root)) {
            return files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bin"))
                    .filter(file -> file.toFile().length() >= SECTOR_BYTES && file.toFile().length() % SECTOR_BYTES == 0)
                    .sorted().findFirst().orElse(null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The audio CD at {@code root} (see find); null if it has none. */
    @Nullable
    static CdAudio open(Path root) throws IOException {
        Path found = find(root);
        return found == null ? null : found.equals(root) ? new Drive(root) : new Image(found);
    }

    /** A CD image: its sectors, one after another, as CD rippers write them beside a .cue. */
    final class Image implements CdAudio {
        private final FileChannel file;
        private final long sectors;

        Image(Path path) throws IOException {
            file = FileChannel.open(path);
            sectors = file.size() / SECTOR_BYTES;
        }

        @Override
        public long sectors() {
            return sectors;
        }

        @Override
        public byte[] read(long from, int count) throws IOException {
            ByteBuffer bytes = ByteBuffer.allocate(count * SECTOR_BYTES);
            long at = from * SECTOR_BYTES;
            while (bytes.hasRemaining()) {
                int read = file.read(bytes, at + bytes.position());
                if (read < 0) {
                    throw new IOException("The CD image ended before this sector");
                }
            }
            return bytes.array();
        }

        @Override
        public void close() {
            try {
                file.close();
            } catch (IOException ignored) {
                // Nothing was written.
            }
        }
    }

    /** A CD in a Windows drive, read through the drive's own controls (see ntddcdrm.h). */
    final class Drive implements CdAudio {
        private static final int IOCTL_CDROM_READ_TOC = 0x24000, IOCTL_CDROM_RAW_READ = 0x2403E;
        /** CDROM_TOC: a four-byte header, then eight bytes a track, the lead-out after the last. */
        private static final int TOC_BYTES = 4 + 100 * 8;
        /** TRACK_DATA's control bit of a data track. */
        private static final int DATA_TRACK = 4;
        /** RAW_READ_INFO's TrackMode for audio. */
        private static final int CDDA = 2;
        /** Sectors a read asks for: drivers refuse transfers much over 64 KB. */
        private static final int READ_SECTORS = 20;

        private final WinNT.HANDLE handle;
        private final long first, sectors;

        Drive(Path root) throws IOException {
            handle = Kernel32.INSTANCE.CreateFile("\\\\.\\" + root.toString().substring(0, 2), WinNT.GENERIC_READ,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                throw new IOException("The CD drive could not be opened");
            }
            Memory toc = new Memory(TOC_BYTES);
            if (!Kernel32.INSTANCE.DeviceIoControl(handle, IOCTL_CDROM_READ_TOC, null, 0, toc, TOC_BYTES,
                    new IntByReference(), null)) {
                close();
                throw new IOException("The CD's table of contents could not be read");
            }
            int tracks = (toc.getByte(3) & 255) - (toc.getByte(2) & 255) + 1;
            if (tracks < 1 || tracks > 99 || (toc.getByte(4 + 1) & DATA_TRACK) != 0) {
                close();
                throw new IOException("This CD has no audio to play");
            }
            first = address(toc, 0);
            sectors = address(toc, tracks) - first;
        }

        /** Where entry {@code entry} of the table of contents starts, in sectors: it says minutes, seconds and frames, from 2 s before the first. */
        private static long address(Memory toc, int entry) {
            int at = 4 + entry * 8 + 4;
            return ((toc.getByte(at + 1) & 255) * 60L + (toc.getByte(at + 2) & 255)) * 75 + (toc.getByte(at + 3) & 255) - 150;
        }

        @Override
        public long sectors() {
            return sectors;
        }

        @Override
        public synchronized byte[] read(long from, int count) throws IOException {
            byte[] bytes = new byte[count * SECTOR_BYTES];
            Memory request = new Memory(16), sector = new Memory((long) READ_SECTORS * SECTOR_BYTES);
            for (int done = 0; done < count; done += READ_SECTORS) {
                int now = Math.min(READ_SECTORS, count - done);
                // RAW_READ_INFO: where (counted in 2048-byte sectors whatever the track), how many, and of what kind.
                request.setLong(0, (first + from + done) * 2048);
                request.setInt(8, now);
                request.setInt(12, CDDA);
                IntByReference read = new IntByReference();
                if (!Kernel32.INSTANCE.DeviceIoControl(handle, IOCTL_CDROM_RAW_READ, request, 16, sector, now * SECTOR_BYTES,
                        read, null) || read.getValue() != now * SECTOR_BYTES) {
                    throw new IOException("The CD could not be read; check the disc and drive");
                }
                sector.read(0, bytes, done * SECTOR_BYTES, now * SECTOR_BYTES);
            }
            return bytes;
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }
}
