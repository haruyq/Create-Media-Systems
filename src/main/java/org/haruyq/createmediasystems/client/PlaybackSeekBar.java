package org.haruyq.createmediasystems.client;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import org.haruyq.createmediasystems.stream.PlaybackClock;

/** Shows the device's current time; a drag sends one seek when released. */
public final class PlaybackSeekBar extends AbstractSliderButton {
    private final IntConsumer seek;
    private double seconds, duration;
    private boolean dragging;

    public PlaybackSeekBar(int x, int y, int width, IntConsumer seek) {
        super(x, y, width, 20, Component.empty(), 0);
        this.seek = seek;
    }

    public void show(double seconds, double duration) {
        this.seconds = Math.max(0, seconds);
        this.duration = duration;
        active = duration > 0;
        if (!active) dragging = false;
        if (!dragging) value = active ? Math.clamp(seconds / duration, 0, 1) : 0;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(PlaybackClock.format(dragging ? value * duration : seconds) + " / "
                + (active ? PlaybackClock.format(duration) : "--:--")));
    }

    @Override
    protected void applyValue() {
        if (active && !dragging) apply();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        dragging = true;
        super.onClick(mouseX, mouseY);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        release();
    }

    public void release() {
        if (!dragging) return;
        dragging = false;
        if (active) apply();
    }

    private void apply() {
        seek.accept((int) Math.round(value * duration));
    }
}
