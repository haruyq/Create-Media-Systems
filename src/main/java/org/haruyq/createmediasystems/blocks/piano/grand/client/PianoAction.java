package org.haruyq.createmediasystems.blocks.piano.grand.client;

import net.minecraft.util.Mth;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;

/**
 * Where every moving part of the action is between the events that set it going. Events arrive in bursts and frames
 * fall wherever they fall, so strikes are kept as times and everything else eases towards what {@link PianoState} says.
 */
final class PianoAction {
    /** The share of its blow distance at which the backcheck holds a hammer while its key stays down. */
    static final float CHECK = .56f;
    private static final float REBOUND = .05f, FALL = .11f, KEY_RISE = .075f;
    private static final long KEY_HOLD = 24_000_000;
    private static final int[] PEDALS = {SOFT, SOSTENUTO, DAMPER};
    /** An action nothing has touched: the item, and a piano nobody is playing. */
    static final PianoAction REST = new PianoAction();

    private final float[] key = new float[KEYS], hammer = new float[KEYS], damper = new float[KEYS], ring = new float[KEYS];
    private final float[] force = new float[KEYS], pedal = new float[PEDALS.length];
    private final long[] struck = new long[KEYS], released = new long[KEYS];
    private final long[] keyStarted = new long[KEYS], keyRelease = new long[KEYS];
    private final long[] keyAttack = new long[KEYS];
    private final boolean[] keyMoving = new boolean[KEYS];
    private final boolean[] thrown = new boolean[KEYS], held = new boolean[KEYS], sounding = new boolean[KEYS];
    private long last;
    private boolean started;

    /** How far down a key is, from 0 at rest to 1 on the keybed. */
    float key(int note) { return key[note - FIRST_NOTE]; }
    /** How far a hammer has travelled, from 0 on its rest rail to 1 against the string. */
    float hammer(int note) { return hammer[note - FIRST_NOTE]; }
    /** How far a damper is lifted, from 0 on the string to 1 clear of it. */
    float damper(int note) { return damper[note - FIRST_NOTE]; }
    /** How strongly a string is still moving, from 0 to 1. */
    float ring(int note) { return ring[note - FIRST_NOTE]; }
    /** How far down a pedal is, from 0 to 1. */
    float pedal(int controller) {
        for (int i = 0; i < PEDALS.length; i++) if (PEDALS[i] == controller) return pedal[i];
        return 0;
    }

    /** Seconds from a key starting down to its hammer meeting the string: a hard blow lands several times sooner. */
    static float flight(float force) { return .016f + (1 - force) * (1 - force) * .085f; }

    void accept(PianoEvent event, long now) {
        switch (event.kind()) {
            case ON -> {
                int i = event.number() - FIRST_NOTE;
                struck[i] = now;
                force[i] = Mth.clamp(event.value() / (float) MAX_VELOCITY, .02f, 1);
                float before = keyTravel(i, now);
                keyAttack[i] = (long) (Math.max(.012f, flight(force[i]) * .8f) * 1_000_000_000);
                keyStarted[i] = now - Math.round(before * keyAttack[i]);
                keyMoving[i] = true;
                thrown[i] = held[i] = true;
                sounding[i] = false;
            }
            case OFF -> release(event.number() - FIRST_NOTE, now);
            case CONTROL -> {
                if (event.number() == ALL_NOTES_OFF || event.number() == ALL_SOUND_OFF) {
                    for (int i = 0; i < KEYS; i++) release(i, now);
                }
            }
        }
    }

    private void release(int i, long now) {
        if (!held[i]) return;
        held[i] = false;
        released[i] = now;
        keyRelease[i] = Math.max(now, keyStarted[i] + keyAttack[i] + KEY_HOLD);
    }

    /** Moves every part on to {@code now}. The keys and pedals follow {@code state}; the hammers follow their strikes. */
    void advance(PianoState state, long now) {
        float dt = started ? Mth.clamp((now - last) * 1e-9f, 0, .1f) : 0;
        started = true;
        last = now;
        for (int i = 0; i < PEDALS.length; i++) {
            pedal[i] = Mth.approach(pedal[i], state.pedal(PEDALS[i]) / 127f, dt / .05f);
        }
        // The dampers start to leave the strings a little before the pedal reaches the point where the notes sustain.
        float feathered = Mth.clamp((state.pedal(DAMPER) - 24) / 40f, 0, 1) * .45f;
        for (int i = 0; i < KEYS; i++) {
            int note = FIRST_NOTE + i;
            boolean free = state.rings(note);
            key[i] = keyTravel(i, now);
            if (!held[i] && key[i] == 0) keyMoving[i] = false;
            float lifted = free ? 1 : feathered;
            damper[i] = Mth.approach(damper[i], lifted, dt / (lifted > damper[i] ? .03f : .07f));
            if (thrown[i] && !sounding[i] && (now - struck[i]) * 1e-9f >= flight(force[i])) {
                sounding[i] = true;
                ring[i] = Math.max(ring[i], .35f + .65f * force[i]);
            }
            hammer[i] = thrown[i] ? travel(i, now) : 0;
            if (ring[i] > 0) {
                // A free string dies away over seconds, longest in the bass; a damped one stops almost at once.
                float life = free ? Mth.lerp(i / (KEYS - 1f), 7, 1.1f) : .06f;
                ring[i] *= (float) Math.exp(-dt / life);
                if (ring[i] < .004f) ring[i] = 0;
            }
        }
    }

    private float keyTravel(int i, long now) {
        if (!keyMoving[i]) return 0;
        if (!held[i] && now >= keyRelease[i]) {
            return Mth.clamp(1 - (now - keyRelease[i]) * 1e-9f / KEY_RISE, 0, 1);
        }
        return Mth.clamp((now - keyStarted[i]) / (float) keyAttack[i], 0, 1);
    }

    private float travel(int i, long now) {
        float t = (now - struck[i]) * 1e-9f, flight = flight(force[i]);
        float height;
        if (t < flight) {
            // Thrown by the jack: it gathers speed all the way to the string.
            float rise = Math.max(0, t / flight);
            height = rise * rise;
        } else {
            float settle = Math.min(1, (t - flight) / REBOUND);
            height = Mth.lerp(1 - (1 - settle) * (1 - settle), 1, CHECK);
        }
        if (held[i]) return height;
        // Once thrown it still reaches the string however briefly the key was down; only then does it drop to the rail.
        float fall = Mth.clamp(Math.min((now - released[i]) * 1e-9f, t - flight) / FALL, 0, 1);
        if (fall >= 1) thrown[i] = false;
        return height * (1 - fall * fall * (3 - 2 * fall));
    }
}
