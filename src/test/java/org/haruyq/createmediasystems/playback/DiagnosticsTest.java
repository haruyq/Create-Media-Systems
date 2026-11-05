package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsTest {
    @TempDir
    Path dir;

    @Test
    void logKeepsTheTailsWholeLinesWithoutChat() throws Exception {
        Path log = dir.resolve("latest.log");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            text.append("[12:00:00] [Render thread/INFO]: line ").append(i).append('\n');
        }
        text.append("[12:00:01] [Render thread/INFO]: [CHAT] <someone> hello\n");
        text.append("[12:00:02] [Render thread/WARN]: yt-dlp could not resolve www.youtube.com: bot check\n");
        Files.writeString(log, text);

        String tail = Diagnostics.log(log);
        assertNotNull(tail);
        assertTrue(tail.length() <= 128 * 1024);
        assertTrue(tail.startsWith("[12:00:00]"), "starts on a whole line");
        assertTrue(tail.endsWith("bot check"));
        assertFalse(tail.contains("[CHAT]"));
        assertFalse(tail.contains("line 0\n"), "only the tail");
        assertNull(Diagnostics.log(dir.resolve("missing.log")));
    }
}
