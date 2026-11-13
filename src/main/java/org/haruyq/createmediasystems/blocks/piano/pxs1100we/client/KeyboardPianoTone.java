package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.Arrays;
import javax.sound.midi.MidiChannel;

import org.haruyq.gervill.ModelConnectionBlock;
import org.haruyq.gervill.ModelDestination;
import org.haruyq.gervill.ModelDirectedPlayer;
import org.haruyq.gervill.ModelDirector;
import org.haruyq.gervill.ModelIdentifier;
import org.haruyq.gervill.ModelInstrument;
import org.haruyq.gervill.ModelPerformer;
import org.haruyq.gervill.ModelSource;

/** A continuous response for the default piano's single sample per key range. */
final class KeyboardPianoTone extends ModelInstrument {
    private static final int REFERENCE_VELOCITY = 75;
    private static final double MIN_GAIN = .16, BRIGHTNESS_RANGE = 840;
    private final ModelInstrument original;
    private final ModelPerformer[] performers;
    private final ThreadLocal<ModelConnectionBlock[]> response = new ThreadLocal<>();

    KeyboardPianoTone(ModelInstrument original) {
        super(original.getSoundbank(), original.getPatch(), original.getName(), original.getDataClass());
        this.original = original;
        // These zones share their samples across velocity bands. Keep the middle envelope as the baseline.
        performers = Arrays.stream(original.getPerformers())
                .filter(performer -> performer.getVelFrom() <= REFERENCE_VELOCITY && performer.getVelTo() >= REFERENCE_VELOCITY)
                .toArray(ModelPerformer[]::new);
        for (var performer : performers) smooth(performer);
    }

    private static void smooth(ModelPerformer performer) {
        performer.setVelFrom(0);
        performer.setVelTo(127);
        var connections = performer.getConnectionBlocks();
        connections.removeIf(connection -> {
            var destination = connection.getDestination().getIdentifier();
            var sources = connection.getSources();
            return (ModelDestination.DESTINATION_GAIN.equals(destination)
                    || ModelDestination.DESTINATION_FILTER_FREQ.equals(destination))
                    && sources.length == 1 && ModelSource.SOURCE_NOTEON_VELOCITY.equals(sources[0].getIdentifier());
        });
        // Replace Gervill's default velocity attenuation. play() supplies the full-resolution response.
        connections.add(new ModelConnectionBlock(new ModelSource(ModelSource.SOURCE_NOTEON_VELOCITY),
                0, new ModelDestination(ModelDestination.DESTINATION_GAIN)));
    }

    void play(MidiChannel channel, int note, double velocity) {
        double touch = velocity / 127;
        response.set(new ModelConnectionBlock[] {
                offset(ModelDestination.DESTINATION_GAIN, 200 * Math.log10(MIN_GAIN + (1 - MIN_GAIN) * touch)),
                offset(ModelDestination.DESTINATION_FILTER_FREQ, BRIGHTNESS_RANGE * touch)
        });
        try {
            channel.noteOn(note, (int) Math.round(velocity));
        } finally {
            response.remove();
        }
    }

    private static ModelConnectionBlock offset(ModelIdentifier target, double value) {
        var destination = new ModelDestination(target);
        // Extended Gervill connections apply their destination transform to the summed inputs.
        destination.setTransform(ignored -> value);
        return new ModelConnectionBlock(0, destination);
    }

    @Override public ModelDirector getDirector(ModelPerformer[] performers, MidiChannel channel, ModelDirectedPlayer player) {
        return super.getDirector(performers, channel, (index, blocks) -> {
            var offsets = response.get();
            player.play(index, offsets == null ? blocks : offsets);
        });
    }

    @Override public Object getData() { return original.getData(); }
    @Override public ModelPerformer[] getPerformers() { return performers; }
}
