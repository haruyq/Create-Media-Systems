package org.haruyq.createmediasystems.piano.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

import org.haruyq.createmediasystems.piano.PianoEvent;

import static org.haruyq.createmediasystems.piano.PianoEvent.*;

final class MidiKeyboard implements AutoCloseable {
    record Input(String id, String name, MidiDevice.Info info) {}
    record Status(List<Input> inputs, String selected, String message) {}

    private static final java.util.concurrent.ExecutorService DEVICES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CMS MIDI Devices");
        thread.setDaemon(true);
        return thread;
    });

    private final MidiDecoder decoder = new MidiDecoder();
    private final Consumer<List<PianoEvent>> receive;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile Status status = new Status(List.of(), "", "piano.midi_scanning");
    private volatile String requested;
    private volatile boolean closed;
    private volatile long generation;
    private volatile int channel = -1;
    private MidiDevice device;
    private Transmitter transmitter;
    private String connected = "";

    MidiKeyboard(String preferred, Consumer<List<PianoEvent>> receive) {
        requested = preferred;
        this.receive = receive;
    }

    Status status() { return status; }
    String preference() { return requested; }

    void select(String id) {
        requested = id;
        scan();
    }

    void channel(int channel) {
        this.channel = channel;
        decoder.channel(channel);
        reset();
    }

    void panic() {
        decoder.channel(channel);
        reset();
    }

    void scan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        DEVICES.execute(() -> {
            try {
                if (closed) return;
                var inputs = inputs();
                if (requested == null && !inputs.isEmpty()) requested = inputs.getFirst().id();
                String chosen = requested == null ? "" : requested;
                var selected = inputs.stream().filter(input -> input.id().equals(chosen)).findFirst().orElse(null);
                if (selected == null) {
                    disconnect();
                    status = new Status(inputs, chosen, "".equals(requested) ? "piano.midi_none" : "piano.midi_missing");
                    return;
                }
                if (chosen.equals(connected) && device != null && device.isOpen()) {
                    status = new Status(inputs, chosen, "piano.midi_connected");
                    return;
                }
                disconnect();
                try {
                    connect(selected);
                    status = new Status(inputs, chosen, "piano.midi_connected");
                } catch (MidiUnavailableException | IllegalArgumentException | SecurityException error) {
                    disconnect();
                    status = new Status(inputs, chosen, "piano.midi_error");
                }
            } catch (RuntimeException | LinkageError error) {
                disconnect();
                status = new Status(List.of(), "", "piano.midi_error");
            } finally {
                scanning.set(false);
            }
        });
    }

    static List<Input> inputs() {
        var inputs = new ArrayList<Input>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                var device = MidiSystem.getMidiDevice(info);
                if (device.getMaxTransmitters() == 0 || device instanceof Sequencer || device instanceof Synthesizer) continue;
                String name = info.getName().strip();
                String id = name + "|" + info.getVendor() + "|" + info.getDescription();
                String prefix = id + "|";
                long duplicate = inputs.stream().filter(input -> input.id().startsWith(prefix)).count();
                inputs.add(new Input(prefix + duplicate, duplicate == 0 ? name : name + " (" + (duplicate + 1) + ")", info));
            } catch (MidiUnavailableException ignored) {
                // A port can disappear while its descriptor is being read.
            }
        }
        return List.copyOf(inputs);
    }

    private void connect(Input input) throws MidiUnavailableException {
        MidiDevice port = MidiSystem.getMidiDevice(input.info());
        port.open();
        device = port;
        transmitter = port.getTransmitter();
        long connectedGeneration = ++generation;
        decoder.channel(channel);
        transmitter.setReceiver(new Receiver() {
            @Override public void send(MidiMessage message, long time) {
                if (closed || generation != connectedGeneration || !(message instanceof ShortMessage shortMessage)) return;
                var events = decoder.decode(shortMessage);
                if (!events.isEmpty() && !closed && generation == connectedGeneration) receive.accept(events);
            }
            @Override public void close() {}
        });
        connected = input.id();
    }

    private void disconnect() {
        generation++;
        if (transmitter != null) transmitter.close();
        transmitter = null;
        if (device != null) {
            device.close();
            device = null;
            decoder.channel(channel);
            if (!closed) reset();
        }
        connected = "";
    }

    private void reset() {
        receive.accept(List.of(control(ALL_SOUND_OFF, 0), control(RESET_CONTROLLERS, 0), control(VOLUME, 127)));
    }

    @Override public void close() {
        closed = true;
        DEVICES.execute(this::disconnect);
    }
}
