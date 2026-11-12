package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

/**
 * An audio CD's sound: 44.1 kHz 16-bit stereo, little-endian, in sectors of 1/75 s, from its first track to its
 * last as one piece. FFmpeg's Windows builds read no audio CD, so the sectors are read here and piped to it (from a
 * Windows or Linux drive, or the AIFF files macOS shows an audio CD as).
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
        if (Files.isRegularFile(root.resolve(Aiff.TOC))) {
            return root;
        }
        if (Platform.isLinux() && root.startsWith("/dev")) {
            return Linux.audio(root) ? root : null;
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

    @Nullable
    static CdAudio open(Path root) throws IOException {
        Path found = find(root);
        if (found == null) {
            return null;
        }
        if (!found.equals(root)) {
            return new Image(found);
        }
        return Files.isRegularFile(root.resolve(Aiff.TOC)) ? new Aiff(root) : Platform.isLinux() ? new Linux(root) : new Drive(root);
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

    /**
     * An audio CD as macOS mounts it: a volume of AIFF files, "1 Audio Track.aiff" and on, with the disc's table of
     * contents beside them. AIFF's samples are big-endian, so each one's two bytes swap.
     */
    final class Aiff implements CdAudio {
        static final String TOC = ".TOC.plist";

        private final List<FileChannel> tracks = new ArrayList<>();
        /** Where each track's sound starts in its file, and its sectors. */
        private final List<long[]> spans = new ArrayList<>();
        private final long sectors;

        Aiff(Path root) throws IOException {
            List<Path> files;
            try (var list = Files.list(root)) {
                files = list.filter(file -> file.getFileName().toString().matches("[0-9]+ .*\\.aiff"))
                        .sorted(Comparator.comparingInt(file -> Integer.parseInt(file.getFileName().toString().split(" ", 2)[0])))
                        .toList();
            }
            long total = 0;
            try {
                for (Path file : files) {
                    FileChannel channel = FileChannel.open(file);
                    tracks.add(channel);
                    long[] span = sound(channel);
                    spans.add(span);
                    total += span[1];
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
            if (total == 0) {
                close();
                throw new IOException("This CD has no audio to play");
            }
            sectors = total;
        }

        /** Where an AIFF file's sound (its SSND chunk's samples) starts, and how many whole sectors of it there are. */
        static long[] sound(FileChannel file) throws IOException {
            ByteBuffer header = ByteBuffer.allocate(16);
            file.read(header, 0);
            if (header.getInt(0) != 0x464F524D || header.getInt(8) != 0x41494646) { // "FORM", "AIFF"
                throw new IOException("A CD track is not AIFF");
            }
            for (long at = 12; at + 8 <= file.size(); ) {
                header.clear();
                file.read(header, at);
                long size = header.getInt(4) & 0xFFFFFFFFL;
                if (header.getInt(0) == 0x53534E44) { // "SSND": offset, block size, then the samples
                    long offset = header.getInt(8) & 0xFFFFFFFFL;
                    return new long[] {at + 16 + offset, (size - 8 - offset) / SECTOR_BYTES};
                }
                at += 8 + size + (size & 1);
            }
            throw new IOException("A CD track has no sound");
        }

        @Override
        public long sectors() {
            return sectors;
        }

        @Override
        public synchronized byte[] read(long from, int count) throws IOException {
            ByteBuffer bytes = ByteBuffer.allocate(count * SECTOR_BYTES);
            long sector = from;
            for (int track = 0; track < tracks.size() && bytes.hasRemaining(); track++) {
                long[] span = spans.get(track);
                if (sector >= span[1]) {
                    sector -= span[1];
                    continue;
                }
                long at = span[0] + sector * SECTOR_BYTES;
                int end = (int) Math.min(bytes.limit(), bytes.position() + (span[1] - sector) * SECTOR_BYTES);
                ByteBuffer part = bytes.slice(bytes.position(), end - bytes.position());
                while (part.hasRemaining()) {
                    if (tracks.get(track).read(part, at + part.position()) < 0) {
                        throw new IOException("The CD could not be read; check the disc and drive");
                    }
                }
                bytes.position(end);
                sector = 0;
            }
            if (bytes.hasRemaining()) {
                throw new IOException("The CD ended before this sector");
            }
            byte[] array = bytes.array();
            for (int i = 0; i < array.length; i += 2) {
                byte high = array[i];
                array[i] = array[i + 1];
                array[i + 1] = high;
            }
            return array;
        }

        @Override
        public void close() {
            for (FileChannel track : tracks) {
                try {
                    track.close();
                } catch (IOException ignored) {
                    // Nothing was written.
                }
            }
        }
    }

    /** A CD in a Linux drive ({@code /dev/srN}), read through the kernel's CD-ROM controls (linux/cdrom.h). */
    final class Linux implements CdAudio {
        private static final int O_RDONLY_NONBLOCK = 0x800;
        private static final long CDROMREADTOCHDR = 0x5305, CDROMREADTOCENTRY = 0x5306, CDROMREADAUDIO = 0x530e;
        private static final int CDROM_LBA = 1, CDROM_LEADOUT = 0xAA, CDROM_DATA_TRACK = 4;
        /** Sectors a read asks for, as the Windows drive's. */
        private static final int READ_SECTORS = 20;
        /** struct cdrom_read_audio: the address (4), its format (1), the frame count (int, at 8), then the buffer pointer. */
        private static final int BUFFER_AT = Native.POINTER_SIZE == 8 ? 16 : 12;

        private interface LibC extends Library {
            LibC C = Native.load("c", LibC.class);

            int open(String path, int flags);

            int ioctl(int fd, NativeLong request, Pointer argument);

            int close(int fd);
        }

        private final int fd;
        private final long first, sectors;

        Linux(Path device) throws IOException {
            fd = LibC.C.open(device.toString(), O_RDONLY_NONBLOCK);
            if (fd < 0) {
                throw new IOException("The CD drive could not be opened (is this user in the cdrom group?)");
            }
            long[] toc = toc(fd);
            if (toc == null) {
                close();
                throw new IOException("This CD has no audio to play");
            }
            first = toc[0];
            sectors = toc[1] - toc[0];
        }

        /** Whether the drive at {@code device} has an audio CD in it. */
        static boolean audio(Path device) {
            try {
                int fd = LibC.C.open(device.toString(), O_RDONLY_NONBLOCK);
                if (fd < 0) {
                    return false;
                }
                try {
                    return toc(fd) != null;
                } finally {
                    LibC.C.close(fd);
                }
            } catch (RuntimeException | LinkageError unknown) {
                return false;
            }
        }

        /** Where the CD's first track and its lead-out start, in sectors; null if there is no disc or its first track is data. */
        @Nullable
        private static long[] toc(int fd) {
            Memory header = new Memory(2);
            if (LibC.C.ioctl(fd, new NativeLong(CDROMREADTOCHDR), header) != 0) {
                return null;
            }
            int track = header.getByte(0) & 255;
            // struct cdrom_tocentry: track, adr (low nibble) and ctrl (high), format, then the address at 4.
            Memory entry = new Memory(12);
            entry.clear();
            entry.setByte(0, (byte) track);
            entry.setByte(2, (byte) CDROM_LBA);
            if (LibC.C.ioctl(fd, new NativeLong(CDROMREADTOCENTRY), entry) != 0
                    || ((entry.getByte(1) >> 4) & CDROM_DATA_TRACK) != 0) {
                return null;
            }
            long start = entry.getInt(4);
            entry.clear();
            entry.setByte(0, (byte) CDROM_LEADOUT);
            entry.setByte(2, (byte) CDROM_LBA);
            if (LibC.C.ioctl(fd, new NativeLong(CDROMREADTOCENTRY), entry) != 0) {
                return null;
            }
            long end = entry.getInt(4);
            return end > start ? new long[] {start, end} : null;
        }

        @Override
        public long sectors() {
            return sectors;
        }

        @Override
        public synchronized byte[] read(long from, int count) throws IOException {
            byte[] bytes = new byte[count * SECTOR_BYTES];
            Memory request = new Memory(BUFFER_AT + Native.POINTER_SIZE), sector = new Memory((long) READ_SECTORS * SECTOR_BYTES);
            for (int done = 0; done < count; done += READ_SECTORS) {
                int now = Math.min(READ_SECTORS, count - done);
                request.clear();
                request.setInt(0, (int) (first + from + done));
                request.setByte(4, (byte) CDROM_LBA);
                request.setInt(8, now);
                request.setPointer(BUFFER_AT, sector);
                if (LibC.C.ioctl(fd, new NativeLong(CDROMREADAUDIO), request) != 0) {
                    throw new IOException("The CD could not be read; check the disc and drive");
                }
                sector.read(0, bytes, done * SECTOR_BYTES, now * SECTOR_BYTES);
            }
            return bytes;
        }

        @Override
        public void close() {
            LibC.C.close(fd);
        }
    }
}
