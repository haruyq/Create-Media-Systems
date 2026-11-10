package org.haruyq.createmediasystems.playback;

/** Recover stalled music without mistaking the natural Doppler phase of a moving source for a decoder error. */
public final class PlaybackSync {
    private PlaybackSync(){}
    public static boolean realign(double audible,double server,int age,double doppler) {
        return age>=40 && Math.abs(doppler-1)<=.01 && Double.isFinite(audible) && audible>=0
                && Double.isFinite(server) && Math.abs(audible-server)>.25;
    }
}
