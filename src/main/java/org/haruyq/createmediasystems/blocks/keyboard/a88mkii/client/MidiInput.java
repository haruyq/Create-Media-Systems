package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

/**
 * The real MIDI keyboards plugged into this computer, played through the keyboard its player sits at: every MIDI
 * input the system has (or those the midiDevice setting names) is open while they play. A note goes to the
 * {@link Controller} on the device's own thread the moment it arrives; wheels, pedals and pressure, which a device
 * sends by the hundred, go at most every {@link #CONTINUOUS_MILLIS}, the latest of each.
 */
final class MidiInput {
    private static final long CONTINUOUS_MILLIS = 5;
    /** Opens and closes the devices: looking them up alone can take seconds. */
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CMS MIDI input");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<MidiDevice> OPEN = new ArrayList<>();
    /** What the open devices send to. */
    @Nullable
    private static In receiving;
    /** The names of the inputs open; null while they are looked up. */
    @Nullable
    private static volatile List<String> names = List.of();

    private MidiInput() {
    }

    /** The inputs open, by name; null while they are looked up. */
    @Nullable
    static List<String> names() {
        return names;
    }

    /** Opens the inputs for {@code controller}, closing those open for another; {@code only} names the ones to take, empty for all. */
    static void open(Controller controller, String only) {
        names = null;
        WORKER.execute(() -> {
            closeAll();
            List<String> opened = new ArrayList<>();
            In in = receiving = new In(controller);
            in.flushing = WORKER.scheduleAtFixedRate(in::flush, CONTINUOUS_MILLIS, CONTINUOUS_MILLIS, TimeUnit.MILLISECONDS);
            for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
                try {
                    MidiDevice device = MidiSystem.getMidiDevice(info);
                    if (device instanceof Sequencer || device instanceof Synthesizer || device.getMaxTransmitters() == 0
                            || !info.getName().toLowerCase(Locale.ROOT).contains(only.strip().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    device.open();
                    device.getTransmitter().setReceiver(in);
                    OPEN.add(device);
                    opened.add(info.getName());
                } catch (MidiUnavailableException | RuntimeException busy) {
                    LogUtils.getLogger().info("MIDI input {} can't be opened: {}", info.getName(), busy.getMessage());
                }
            }
            names = List.copyOf(opened);
        });
    }

    static void close() {
        WORKER.execute(MidiInput::closeAll);
    }

    private static void closeAll() {
        OPEN.forEach(MidiDevice::close);
        OPEN.clear();
        if (receiving != null) {
            receiving.close();
            receiving = null;
        }
        names = List.of();
    }

    /** What the devices send, on their threads. */
    static final class In implements Receiver {
        private final Controller controller;
        /** The latest value of each wheel, pedal or pressure not passed on yet, by its status and number. */
        private final Map<Integer, Integer> continuous = new ConcurrentHashMap<>();
        @Nullable
        ScheduledFuture<?> flushing;

        In(Controller controller) {
            this.controller = controller;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!(message instanceof ShortMessage played)) {
                return;
            }
            switch (played.getCommand()) {
                case ShortMessage.NOTE_ON -> controller.key(played.getData1(), played.getData2());
                case ShortMessage.NOTE_OFF -> controller.key(played.getData1(), 0);
                case ShortMessage.CONTROL_CHANGE -> {
                    // Up to the channel mode messages; a bank select would only confuse the zones' programs.
                    if (played.getData1() > 0 && played.getData1() < 120 && played.getData1() != 32) {
                        continuous.put(ShortMessage.CONTROL_CHANGE | played.getData1() << 8, played.getData2());
                    }
                }
                case ShortMessage.PITCH_BEND -> continuous.put(ShortMessage.PITCH_BEND, played.getData1() | played.getData2() << 7);
                case ShortMessage.CHANNEL_PRESSURE -> continuous.put(ShortMessage.CHANNEL_PRESSURE, played.getData1());
                default -> {
                }
            }
        }

        void flush() {
            for (Integer kind : continuous.keySet()) {
                Integer value = continuous.remove(kind);
                if (value == null) {
                    continue;
                }
                switch (kind & 0xF0) {
                    case ShortMessage.CONTROL_CHANGE -> controller.control(kind >> 8, value);
                    case ShortMessage.PITCH_BEND -> controller.bend(value);
                    default -> controller.pressure(value);
                }
            }
        }

        @Override
        public void close() {
            if (flushing != null) {
                flushing.cancel(false);
            }
        }
    }
}
