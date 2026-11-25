package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

import net.minecraft.network.chat.Component;

public final class MidiPort implements AutoCloseable {
    public record Input(String id, String name, MidiDevice.Info info) {}
    public record Status(List<Input> inputs, String selected, String message) {}
    /** The midiDevice setting that turns MIDI input off; any other is an {@link Input#id}, or empty for none picked. */
    public static final String OFF = "off";

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
    private boolean fallback;

    /** An instrument's: the input midiDevice names, or the first there is while none is picked or it is unplugged. */
    public MidiPort(Consumer<ShortMessage> receive, Runnable disconnected) {
        this(ClientConfig.MIDI_DEVICE.get().isEmpty() ? null : ClientConfig.MIDI_DEVICE.get(), receive, disconnected, input -> true);
        fallback = true;
    }

    /** Takes {@code preferred}, or while there is none the first that {@code wanted} accepts, and keeps to it, waiting while it is unplugged. */
    public MidiPort(String preferred, Consumer<ShortMessage> receive, Runnable disconnected, Predicate<Input> wanted) {
        requested = preferred;
        this.receive = receive;
        this.disconnected = disconnected;
        this.wanted = wanted;
    }

    /** Runs {@code task} on the one thread that works MIDI devices: javax.sound.midi lists and opens them unsafely from two. */
    public static void onDevices(Runnable task) {
        DEVICES.execute(task);
    }

    public Status status() { return status; }

    /** What an instrument's MIDI button says: the input played from, or why none is. */
    public Component label() { return label(status); }

    public static Component label(Status status) {
        if (!status.message().equals("keyboard.midi_connected")) return Component.translatable(status.message());
        return Component.translatable("keyboard.midi_input", status.selected().isEmpty() ? Component.translatable("keyboard.midi_all")
                : status.inputs().stream().filter(input -> input.id().equals(status.selected())).findFirst()
                        .map(input -> Component.literal(input.name())).orElse(Component.literal(status.selected())));
    }

    /** An instrument's MIDI button pressed: the next input, then off, then the first again; looks for inputs if there are none. */
    public void next() {
        var now = status;
        if (now.inputs().isEmpty()) scan();
        else {
            requested = cycle(now.inputs(), now.selected(), false);
            scan();
        }
    }

    /** The choice after {@code picked}, saved as midiDevice for every instrument: all ({@code all}, as empty), each input, off. */
    public static String cycle(List<Input> inputs, String picked, boolean all) {
        String next = next(inputs, picked, all);
        ClientConfig.MIDI_DEVICE.set(next);
        ClientConfig.MIDI_DEVICE.save();
        return next;
    }

    static String next(List<Input> inputs, String picked, boolean all) {
        var choices = new ArrayList<String>();
        if (all) choices.add("");
        inputs.forEach(input -> choices.add(input.id()));
        choices.add(OFF);
        return choices.get((choices.indexOf(picked) + 1) % choices.size());
    }

    /** The input to open for {@code picked}: the one it names, or while that is unplugged the first {@code wanted}; none for off. */
    static Input choose(List<Input> inputs, String picked, Predicate<Input> wanted) {
        if (OFF.equals(picked)) return null;
        return inputs.stream().filter(input -> picks(picked, input)).findFirst()
                .or(() -> inputs.stream().filter(wanted).findFirst()).orElse(null);
    }

    /** Whether {@code picked} names {@code input}: by its id, or as midiDevice once did, by a part of its name. */
    public static boolean picks(String picked, Input input) {
        return input.id().equals(picked) || !picked.isEmpty() && !picked.equals(OFF) && picked.indexOf('|') < 0
                && input.name().toLowerCase(Locale.ROOT).contains(picked.toLowerCase(Locale.ROOT));
    }

    public void scan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        DEVICES.execute(() -> {
            try {
                if (closed) return;
                var inputs = inputs();
                if (!fallback && requested == null) inputs.stream().filter(wanted).findFirst().ifPresent(input -> requested = input.id());
                String chosen = requested == null ? "" : requested;
                var selected = fallback ? choose(inputs, chosen, wanted)
                        : inputs.stream().filter(input -> input.id().equals(chosen)).findFirst().orElse(null);
                if (selected == null) {
                    disconnect();
                    status = new Status(inputs, chosen, OFF.equals(chosen) ? "keyboard.midi_none" : "keyboard.midi_missing");
                    return;
                }
                // The input actually open, which the label names; requested stays, for when the picked one is plugged in.
                String id = selected.id();
                if (id.equals(connected) && device != null && device.isOpen()) {
                    status = new Status(inputs, id, "keyboard.midi_connected");
                    return;
                }
                disconnect();
                try {
                    connect(selected);
                    status = new Status(inputs, id, "keyboard.midi_connected");
                } catch (MidiUnavailableException | IllegalArgumentException | SecurityException error) {
                    disconnect();
                    status = new Status(inputs, id, "keyboard.midi_error");
                }
            } catch (RuntimeException | LinkageError error) {
                disconnect();
                status = new Status(List.of(), "", "keyboard.midi_error");
            } finally {
                scanning.set(false);
            }
        });
    }

    public static List<Input> inputs() {
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
