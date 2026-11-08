package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;


import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

class VlcFrameTest {
    @Test
    void publishesTheWaitingPictureWhenTheReaderFinishes() throws Exception {
        try (Pictures pictures = new Pictures()) {
            pictures.picture(1);
            Pointer reading = pictures.read();
            pictures.picture(2);
            assertEquals(1, reading.getInt(0));
            pictures.finish();
            assertEquals(2, pictures.latest(), "the picture that came while reading is the next read");
        }
    }

    @Test
    void overwritingAWaitingPictureCannotPublishAnOlderPicture() throws Exception {
        for (int finishAt = 0; finishAt < 4; finishAt++) {
            try (Pictures pictures = new Pictures()) {
                pictures.picture(1);
                Pointer reading = pictures.read();
                pictures.picture(2);
                assertEquals(1, reading.getInt(0));
                if (finishAt == 0) {
                    pictures.finish();
                }
                pictures.lock(3);
                if (finishAt == 1) {
                    pictures.finish();
                }
                pictures.unlock();
                if (finishAt == 2) {
                    pictures.finish();
                }
                pictures.display();
                if (finishAt == 3) {
                    pictures.finish();
                }
                assertEquals(3, pictures.latest(), "reader finishes at step " + finishAt);
            }
        }
    }

    private static final class Pictures implements AutoCloseable {
        final VlcPlayer.Frame frame = new VlcPlayer.Frame();
        final Memory planes = new Memory(Native.POINTER_SIZE);

        Pictures() {
            try (Memory width = new Memory(4); Memory height = new Memory(4); Memory chroma = new Memory(4);
                 Memory pitches = new Memory(4); Memory lines = new Memory(4)) {
                width.setInt(0, 1);
                height.setInt(0, 1);
                assertEquals(1, frame.setup.invoke(null, chroma, width, height, pitches, lines));
            }
        }

        void picture(int value) {
            lock(value);
            unlock();
            display();
        }

        void lock(int value) {
            frame.lock.invoke(null, planes);
            planes.getPointer(0).setInt(0, value);
        }

        void unlock() {
            frame.unlock.invoke(null, null, planes);
        }

        void display() {
            frame.display.invoke(null, null);
        }

        /** Starts reading the latest picture, as the render thread does. */
        Pointer read() {
            VlcPlayer.Frame.Picture picture = frame.beginRead();
            assertNotNull(picture, "a new picture to read");
            return picture.pixels();
        }

        /** The next picture read, read whole. */
        int latest() {
            int value = read().getInt(0);
            frame.endRead();
            return value;
        }

        void finish() {
            frame.endRead();
        }

        @Override
        public void close() {
            frame.freePictures();
            planes.close();
        }
    }
}
