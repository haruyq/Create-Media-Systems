package org.haruyq.createmediasystems.hifi;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Everything on a unit a player can work by pointing at it: buttons, knobs, the disc tray and the cassette doors on
 * its front, sockets and terminals on its back. Each has its place on that face, in sixteenths from the face's
 * bottom left as one looks at it: the client finds which one is pointed at, the renderer draws it there, and the
 * server is told which was pressed.
 */
public enum Control {
    POWER(Unit.AMPLIFIER, Kind.BUTTON, 0.5f, 1.9f, 1.6f, 2.4f),
    DIRECT(Unit.AMPLIFIER, Kind.BUTTON, 2.5f, 1.9f, 3.5f, 2.3f),
    WIDE(Unit.AMPLIFIER, Kind.BUTTON, 3.8f, 1.9f, 4.8f, 2.3f),
    VOLUME(Unit.AMPLIFIER, Kind.KNOB, 5.7f, 1.0f, 7.7f, 3.0f),
    BASS(Unit.AMPLIFIER, Kind.BUTTON, 8.4f, 1.9f, 9.3f, 2.3f),
    BASS_LEVEL(Unit.AMPLIFIER, Kind.KNOB, 9.8f, 1.6f, 10.8f, 2.6f),
    INPUT_DISC(Unit.AMPLIFIER, Kind.BUTTON, 1.9f, 0.5f, 3.1f, 0.95f, 0),
    INPUT_TUNER(Unit.AMPLIFIER, Kind.BUTTON, 3.2f, 0.5f, 4.4f, 0.95f, 1),
    INPUT_TAPE(Unit.AMPLIFIER, Kind.BUTTON, 4.5f, 0.5f, 5.7f, 0.95f, 2),
    LINE_IN(Unit.AMPLIFIER, Cable.LINE, 0.8f, 1.5f, 1.8f, 2.7f),
    SOCKET_TUNER(Unit.AMPLIFIER, Cable.TUNER, 2.6f, 0.6f, 4.0f, 1.2f),
    SOCKET_EQUALIZER(Unit.AMPLIFIER, Cable.EQUALIZER, 4.3f, 0.6f, 5.7f, 1.2f),
    SOCKET_DISC(Unit.AMPLIFIER, Cable.DISC, 6.0f, 0.6f, 7.4f, 1.2f),
    SOCKET_DECK(Unit.AMPLIFIER, Cable.DECK, 7.7f, 0.6f, 9.1f, 1.2f),
    SPEAKER_RIGHT(Unit.AMPLIFIER, Kind.TERMINAL, 9.7f, 1.5f, 10.4f, 2.8f, 0),
    SPEAKER_LEFT(Unit.AMPLIFIER, Kind.TERMINAL, 10.7f, 1.5f, 11.4f, 2.8f, 1),

    PRESET_1(Unit.EQUALIZER, Kind.BUTTON, 3.0f, 0.35f, 3.9f, 0.75f, 0),
    PRESET_2(Unit.EQUALIZER, Kind.BUTTON, 4.02f, 0.35f, 4.92f, 0.75f, 1),
    PRESET_3(Unit.EQUALIZER, Kind.BUTTON, 5.04f, 0.35f, 5.94f, 0.75f, 2),
    PRESET_4(Unit.EQUALIZER, Kind.BUTTON, 6.06f, 0.35f, 6.96f, 0.75f, 3),
    PRESET_5(Unit.EQUALIZER, Kind.BUTTON, 7.08f, 0.35f, 7.98f, 0.75f, 4),
    PRESET_6(Unit.EQUALIZER, Kind.BUTTON, 8.1f, 0.35f, 9.0f, 0.75f, 5),
    EFFECT(Unit.EQUALIZER, Kind.KNOB, 9.7f, 0.55f, 11.1f, 1.95f),
    EQUALIZER_PLUG(Unit.EQUALIZER, Cable.EQUALIZER, 2.6f, 0.7f, 4.0f, 1.3f),

    STATION_1(Unit.TUNER, Kind.BUTTON, 8.2f, 1.45f, 9.15f, 1.9f, 0),
    STATION_2(Unit.TUNER, Kind.BUTTON, 9.3f, 1.45f, 10.25f, 1.9f, 1),
    STATION_3(Unit.TUNER, Kind.BUTTON, 10.4f, 1.45f, 11.35f, 1.9f, 2),
    STATION_4(Unit.TUNER, Kind.BUTTON, 8.2f, 0.75f, 9.15f, 1.2f, 3),
    STATION_5(Unit.TUNER, Kind.BUTTON, 9.3f, 0.75f, 10.25f, 1.2f, 4),
    STATION_6(Unit.TUNER, Kind.BUTTON, 10.4f, 0.75f, 11.35f, 1.2f, 5),
    TUNER_PLUG(Unit.TUNER, Cable.TUNER, 2.6f, 0.7f, 4.0f, 1.3f),

    TRAY(Unit.DISC_PLAYER, Kind.SLOT, 2.8f, 1.85f, 8.8f, 2.6f),
    OPEN(Unit.DISC_PLAYER, Kind.BUTTON, 9.4f, 2.05f, 10.3f, 2.5f),
    PLAY(Unit.DISC_PLAYER, Kind.BUTTON, 10.5f, 2.05f, 11.4f, 2.5f),
    STOP(Unit.DISC_PLAYER, Kind.BUTTON, 9.4f, 1.3f, 10.3f, 1.75f),
    PREVIOUS(Unit.DISC_PLAYER, Kind.BUTTON, 9.4f, 0.55f, 10.3f, 1.0f),
    NEXT(Unit.DISC_PLAYER, Kind.BUTTON, 10.5f, 0.55f, 11.4f, 1.0f),
    DISC_PLUG(Unit.DISC_PLAYER, Cable.DISC, 2.6f, 0.7f, 4.0f, 1.3f),
    LINE_OUT(Unit.DISC_PLAYER, Cable.LINE, 0.8f, 1.0f, 1.8f, 2.2f),

    DOOR_1(Unit.CASSETTE_DECK, Kind.SLOT, 0.9f, 1.9f, 5.7f, 4.0f, 0),
    REWIND_1(Unit.CASSETTE_DECK, Kind.BUTTON, 0.9f, 0.45f, 1.95f, 0.9f, 0),
    PLAY_1(Unit.CASSETTE_DECK, Kind.BUTTON, 2.1f, 0.45f, 3.15f, 0.9f, 0),
    FORWARD_1(Unit.CASSETTE_DECK, Kind.BUTTON, 3.3f, 0.45f, 4.35f, 0.9f, 0),
    STOP_1(Unit.CASSETTE_DECK, Kind.BUTTON, 4.5f, 0.45f, 5.55f, 0.9f, 0),
    DOOR_2(Unit.CASSETTE_DECK, Kind.SLOT, 6.3f, 1.9f, 11.1f, 4.0f, 1),
    REWIND_2(Unit.CASSETTE_DECK, Kind.BUTTON, 6.3f, 0.45f, 7.35f, 0.9f, 1),
    PLAY_2(Unit.CASSETTE_DECK, Kind.BUTTON, 7.5f, 0.45f, 8.55f, 0.9f, 1),
    FORWARD_2(Unit.CASSETTE_DECK, Kind.BUTTON, 8.7f, 0.45f, 9.75f, 0.9f, 1),
    STOP_2(Unit.CASSETTE_DECK, Kind.BUTTON, 9.9f, 0.45f, 10.95f, 0.9f, 1),
    DECK_PLUG(Unit.CASSETTE_DECK, Cable.DECK, 2.6f, 0.7f, 4.0f, 1.3f);

    /** How it is worked: pressed, held and turned, given or taken an item, or plugged (the last two on the back). */
    public enum Kind {
        BUTTON, KNOB, SLOT, SOCKET, TERMINAL;

        public boolean rear() {
            return this == SOCKET || this == TERMINAL;
        }
    }

    public final Unit unit;
    public final Kind kind;
    /** Its place on its unit's face (sixteenths): across from the left as one looks at that face, and up. */
    public final float left;
    public final float bottom;
    public final float right;
    public final float top;
    /** Which of its kind it is: the input, preset or station it picks, the deck it works, the speaker's side. */
    public final int index;
    /** The cable a socket takes; null for anything else. */
    @Nullable
    public final Cable cable;

    Control(Unit unit, Kind kind, float left, float bottom, float right, float top) {
        this(unit, kind, left, bottom, right, top, 0);
    }

    Control(Unit unit, Kind kind, float left, float bottom, float right, float top, int index) {
        this(unit, kind, null, left, bottom, right, top, index);
    }

    Control(Unit unit, Cable cable, float left, float bottom, float right, float top) {
        this(unit, Kind.SOCKET, cable, left, bottom, right, top, 0);
    }

    Control(Unit unit, Kind kind, @Nullable Cable cable, float left, float bottom, float right, float top, int index) {
        this.unit = unit;
        this.kind = kind;
        this.cable = cable;
        this.left = left;
        this.bottom = bottom;
        this.right = right;
        this.top = top;
        this.index = index;
    }

    public boolean rear() {
        return kind.rear();
    }

    public float centerX() {
        return (left + right) / 2;
    }

    public float centerY() {
        return (bottom + top) / 2;
    }

    /** What it is called when pointed at. */
    public Component label() {
        return Component.translatable("create_media_systems.hifi.control." + name().toLowerCase(Locale.ROOT));
    }

    public static Control byId(int id) {
        return values()[Mth.clamp(id, 0, values().length - 1)];
    }

    /** The control of {@code unit} at ({@code x}, {@code y}) on its front or {@code rear}; null for none there. */
    @Nullable
    public static Control at(Unit unit, boolean rear, double x, double y) {
        for (Control control : values()) {
            if (control.unit == unit && control.rear() == rear && x >= control.left && x <= control.right
                    && y >= control.bottom && y <= control.top) {
                return control;
            }
        }
        return null;
    }

    /** The other end's socket of {@code cable}: the one on {@code amplifier}, or on the cable's own unit. */
    public static Control socket(Cable cable, boolean amplifier) {
        for (Control control : values()) {
            if (control.cable == cable && (control.unit == Unit.AMPLIFIER) == amplifier) {
                return control;
            }
        }
        throw new IllegalStateException("No socket for " + cable);
    }
}
