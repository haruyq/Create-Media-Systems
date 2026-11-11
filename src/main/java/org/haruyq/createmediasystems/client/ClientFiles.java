package org.haruyq.createmediasystems.client;

import java.nio.file.Path;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.neoforged.fml.loading.FMLPaths;

/** Where CMS keeps its files on this computer, all in the game directory. */
public final class ClientFiles {
    /** CMS's folder: its tools, VLC, WebRTC's library and cookies. */
    public static final Path DIR = FMLPaths.GAMEDIR.get().resolve(CreateMediaSystems.MOD_ID);
    /** Records' kept audio (see playback.AudioCache), beside DIR rather than in it, where it has always been. */
    public static final Path AUDIO_CACHE = FMLPaths.GAMEDIR.get().resolve(CreateMediaSystems.MOD_ID + "-cache");
    /** Displays' kept videos (see playback.VideoCache), beside DIR like the audio. */
    public static final Path VIDEO_CACHE = FMLPaths.GAMEDIR.get().resolve(CreateMediaSystems.MOD_ID + "-video-cache");

    private ClientFiles() {
    }
}
