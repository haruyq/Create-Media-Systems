package org.haruyq.createmediasystems.stream;

/** A source whose track may be a video, which the displays linked to it show: a Blu-ray player or a hi-fi stack. */
public interface VideoSource extends MediaSource {
    /** How brightly its displays light their surroundings, 0..1 (0: not at all); the same for everyone. */
    default float lighting() {
        return 1;
    }
}
