package org.haruyq.createmediasystems.p2ptest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The app's smoke mode: two native peers through a CMS-API of their own (needs uv and a CMS-API checkout). */
@EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
class SmokeTest {
    static final Path API = Path.of(System.getProperty("cms.apiDir", "../../../CMS-API"));

    @BeforeAll
    static void needsUvAndApi() throws InterruptedException {
        assumeTrue(Files.isDirectory(API.resolve("app")), "no CMS-API checkout at " + API);
        try {
            new ProcessBuilder("uv", "--version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (IOException e) {
            assumeTrue(false, "no uv on PATH");
        }
    }

    @Test
    void aViewerDecodesChangingFramesAndEverythingEnds() throws Exception {
        P2pTestApp.smoke(API, 20_000_000);
    }

    /** At 0.3 Mbps typed in, the 640×360 frames are over the budget: the host drops some, and the viewer stays current. */
    @Test
    void aLowBudgetDropsFramesRatherThanFallingBehind() throws Exception {
        assertTrue(P2pTestApp.smoke(API, 300_000) > 0, "no frame dropped");
    }
}
