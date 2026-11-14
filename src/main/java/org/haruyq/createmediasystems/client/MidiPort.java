package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

public final class MidiPort implements AutoCloseable {
    public record Input(String id, String name, MidiDevice.Info info) {}
    public record Status(List<Input> inputs, String selected, String message) {}

    private static final java.util.concurrent.ExecutorService DEVICES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CMS MIDI Devices");
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<ShortMessage> receive;
    private final Runnable disconnected;
    private final Predicate<Input> wanted;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile Status status = new Status(List.of(), "", "keyboard.midi_scanning");
    private volatile String requested;
    private volatile boolean closed;
    private volatile long generation;
    private MidiDevice device;
    private Transmitter transmitter;
    private String connected = "";

    public MidiPort(String preferred, Consumer<ShortMessage> receive, Runnable disconnected) {
        this(preferred, receive, disconnected, input -> true);
    }

    /** With no {@code preferred} input, takes the first that {@code wanted} accepts, waiting for one if there is none. */
    public MidiPort(String preferred, Consumer<ShortMessage> receive, Runnable disconnected, Predicate<Input> wanted) {
        requested = preferred;
        this.receive = receive;
        this.disconnected = disconnected;
        this.wanted = wanted;
    }

    public Status status() { return status; }
    public String preference() { return requested; }

    public void select(String id) {
        requested = id;
        scan();
    }

    public void scan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        DEVICES.execute(() -> {
            try {
                if (closed) return;
                var inputs = inputs();
                if (requested == null) inputs.stream().filter(wanted).findFirst().ifPresent(input -> requested = input.id());
                String chosen = requested == null ? "" : requested;
                var selected = inputs.stream().filter(input -> input.id().equals(chosen)).findFirst().orElse(null);
                if (selected == null) {
                    disconnect();
                    status = new Status(inputs, chosen, "".equals(requested) ? "keyboard.midi_none" : "keyboard.midi_missing");
                    return;
                }
                if (chosen.equals(connected) && device != null && device.isOpen()) {
                    status = new Status(inputs, chosen, "keyboard.midi_connected");
                    return;
                }
                disconnect();
                try {
                    connect(selected);
                    status = new Status(inputs, chosen, "keyboard.midi_connected");
                } catch (MidiUnavailableException | IllegalArgumentException | SecurityException error) {
                    disconnect();
                    status = new Status(inputs, chosen, "keyboard.midi_error");
                }
            } catch (RuntimeException | LinkageError error) {
                disconnect();
                status = new Status(List.of(), "", "keyboard.midi_error");
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
        transmitter.setReceiver(new Receiver() {
            @Override public void send(MidiMessage message, long time) {
                if (closed || generation != connectedGeneration || !(message instanceof ShortMessage shortMessage)) return;
                receive.accept(shortMessage);
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
            if (!closed) disconnected.run();
        }
        connected = "";
    }

    @Override public void close() {
        closed = true;
        DEVICES.execute(this::disconnect);
    }
}
