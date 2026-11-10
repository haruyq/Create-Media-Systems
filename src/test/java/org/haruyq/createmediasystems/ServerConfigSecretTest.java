package org.haruyq.createmediasystems;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.toml.TomlFormat;

class ServerConfigSecretTest {
    private static final String TOKEN = "a3f9c0d17e2b4c58a3f9c0d17e2b4c58";

    @Test
    void playersGetTheConfigWithItsTokenEmptied() {
        String file = """
                # Base URL of the audio API
                apiUrl = "https://api.example"
                allowedHosts = ["youtube.com"]
                remoteRange = 64
                # Direct P2P's secret
                p2pServerToken = "%s"
                """.formatted(TOKEN);
        String sent = new String(ServerConfig.withoutSecrets(file.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertFalse(sent.contains(TOKEN), sent);
        var config = TomlFormat.instance().createParser().parse(sent);
        assertEquals("", config.get("p2pServerToken"), "emptied, not removed, so the config stays valid for players");
        assertEquals("https://api.example", config.get("apiUrl"));
        assertEquals(64, (int) config.getInt("remoteRange"));
    }

    @Test
    void anUnreadableFileIsNotSent() {
        assertEquals("", ServerConfig.withoutSecrets("p2pServerToken = \"" + TOKEN + "\"\napiUrl = "));
        var untouched = TomlFormat.instance().createParser().parse(ServerConfig.withoutSecrets("apiUrl = \"x\""));
        assertEquals("x", untouched.get("apiUrl"));
        assertFalse(untouched.contains("p2pServerToken"), "without a token, nothing is added");
    }
}
