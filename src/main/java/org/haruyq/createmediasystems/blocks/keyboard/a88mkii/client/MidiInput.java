package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import java.util.ArrayList;
import java.util.List;
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
import javax.sound.midi.ShortMessage;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;
import org.haruyq.createmediasystems.client.MidiPort;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

/**
 * The real MIDI keyboards plugged into this computer, played through the keyboard its player sits at: every MIDI
 * input the system has (or the one the midiDevice setting names) is open while they play. A note goes to the
 * {@link Controller} on the device's own thread the moment it arrives; wheels, pedals and pressure, which a device
 * sends by the hundred, go at most every {@link #CONTINUOUS_MILLIS}, the latest of each.
 */
final class MidiInput {
    private static final long CONTINUOUS_MILLIS = 5;
    /** Passes on the wheels, pedals and pressure; MidiPort's thread opens and closes the devices. */
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CMS MIDI input");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<MidiDevice> OPEN = new ArrayList<>();
    /** What the open devices send to. */
    @Nullable
    private static In receiving;
    /** The inputs there are, which is picked (empty for all) and whether it is open, as an instrument's MidiPort says. */
    private static volatile MidiPort.Status status = new MidiPort.Status(List.of(), "", "keyboard.midi_scanning");

    private MidiInput() {
    }

    static MidiPort.Status status() {
        return status;
    }

    /** Opens the inputs for {@code controller}, closing those open for another; {@code picked} names the one to take, empty (or unplugged) for all. */
    static void open(Controller controller, String picked) {
        status = new MidiPort.Status(status.inputs(), picked, "keyboard.midi_scanning");
        MidiPort.onDevices(() -> {
            closeAll();
            In in = receiving = new In(controller);
            in.flushing = WORKER.scheduleAtFixedRate(in::flush, CONTINUOUS_MILLIS, CONTINUOUS_MILLIS, TimeUnit.MILLISECONDS);
            List<MidiPort.Input> inputs = List.of();
            try {
                inputs = MidiPort.inputs();
            } catch (RuntimeException | LinkageError broken) {
                LogUtils.getLogger().info("MIDI inputs can't be listed: {}", broken.getMessage());
            }
            boolean failed = false;
            boolean all = picked.isEmpty()
                    || !MidiPort.OFF.equals(picked) && inputs.stream().noneMatch(input -> MidiPort.picks(picked, input));
            for (MidiPort.Input input : inputs) {
                if (!all && !MidiPort.picks(picked, input)) {
                    continue;
                }
                try {
                    MidiDevice device = MidiSystem.getMidiDevice(input.info());
                    device.open();
                    device.getTransmitter().setReceiver(in);
                    OPEN.add(device);
                } catch (MidiUnavailableException | RuntimeException busy) {
                    failed = true;
                    LogUtils.getLogger().info("MIDI input {} can't be opened: {}", input.name(), busy.getMessage());
                }
            }
            status = new MidiPort.Status(inputs, all ? "" : picked, !OPEN.isEmpty() ? "keyboard.midi_connected" : failed ? "keyboard.midi_error"
                    : MidiPort.OFF.equals(picked) ? "keyboard.midi_none" : "keyboard.midi_missing");
        });
    }

    static void close() {
        MidiPort.onDevices(MidiInput::closeAll);
    }

    private static void closeAll() {
        OPEN.forEach(MidiDevice::close);
        OPEN.clear();
        if (receiving != null) {
            receiving.close();
            receiving = null;
        }
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
