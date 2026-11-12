package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VlcKeptTest {
    @Test
    void keepsWhatPlaybackUses() {
        assertTrue(Vlc.Parts.kept("libvlc.dll"));
        assertTrue(Vlc.Parts.kept("libvlccore.dll"));
        assertTrue(Vlc.Parts.kept("COPYING.txt"));
        assertTrue(Vlc.Parts.kept("plugins/codec/libavcodec_plugin.dll"));
        assertTrue(Vlc.Parts.kept("plugins/stream_out/libstream_out_smem_plugin.dll"));
        assertTrue(Vlc.Parts.kept("plugins/stream_out/libstream_out_transcode_plugin.dll"));
        assertFalse(Vlc.Parts.kept("vlc.exe"));
        assertFalse(Vlc.Parts.kept("plugins/gui/libqt_plugin.dll"));
        assertFalse(Vlc.Parts.kept("plugins/lua/liblua_plugin.dll"));
        assertFalse(Vlc.Parts.kept("plugins/stream_out/libstream_out_http_plugin.dll"));
    }
}
