package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.List;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.client.MidiPort;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;

final class MidiKeyboard implements AutoCloseable {
    private final MidiDecoder decoder = new MidiDecoder();
    private final Consumer<List<PianoEvent>> receive;
    final MidiPort port;
    private volatile int channel = -1;

    MidiKeyboard(Consumer<List<PianoEvent>> receive) {
        this.receive = receive;
        port = new MidiPort(message -> {
            var events = decoder.decode(message);
            if (!events.isEmpty()) receive.accept(events);
        }, this::panic);
    }

    void scan() { port.scan(); }

    void channel(int channel) {
        this.channel = channel;
        decoder.channel(channel);
        reset();
    }

    void panic() {
        decoder.channel(channel);
        reset();
    }

    private void reset() {
        receive.accept(List.of(control(ALL_SOUND_OFF, 0), control(RESET_CONTROLLERS, 0), control(VOLUME, 127)));
    }

    @Override public void close() { port.close(); }
}
