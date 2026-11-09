package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CMS_FFMPEG_DOWNLOAD_TEST", matches = "1")
class DiscToolsIntegrationTest {
    @Test
    void downloadsAndRunsTheDiscTools() throws Exception {
        Path directory = Path.of("build", "ffmpeg-download").toAbsolutePath();
        var tools = DiscTools.ensure(directory, "ffmpeg", "ffprobe", () -> System.out.println("Downloading the FFmpeg release"));
        assertEquals(tools, DiscTools.ensure(directory, "ffmpeg", "ffprobe", () -> fail("Downloaded twice")));
        Path installed = Path.of(tools.ffmpeg()).getParent();
        assertTrue(Files.size(installed.resolve("LICENSE")) > 100);
        assertTrue(Files.size(installed.resolve("README.txt")) > 100);
        String demuxers = new String(DiscSource.run(List.of(tools.ffmpeg(), "-v", "error", "-demuxers"), 128 * 1024, 15), StandardCharsets.UTF_8);
        assertTrue(demuxers.contains("dvdvideo"));
        for (String fixture : List.of("CMS_DISC_DVD_FIXTURE", "CMS_DISC_SHORT_FIXTURE")) {
            String path = System.getenv(fixture);
            if (path != null) {
                DiscSource source = DiscSource.open(tools.ffmpeg(), tools.ffprobe(), Path.of(path), -1);
                byte[] segment = source.encode(1, new DiscSource.Quality(480, 30, 1100), "libx264");
                byte[] picture = DiscSource.run(List.of(tools.ffmpeg(), "-v", "error", "-i", "pipe:0", "-frames:v", "1",
                        "-vf", "scale=160:90,format=gray", "-f", "rawvideo", "pipe:1"), segment, 160 * 90, 15);
                assertEquals(160 * 90, picture.length);
                assertTrue(source.duration() > 8);
            }
        }
    }
}
