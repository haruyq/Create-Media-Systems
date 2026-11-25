package org.haruyq.createmediasystems.blocks.turntable.atlpa2.client;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableBlock;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.Part;
import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.client.WorldEvents;
import org.haruyq.createmediasystems.playback.EmitterAudioManager;
import org.haruyq.createmediasystems.playback.MusicEmitter;

import org.jetbrains.annotations.Nullable;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The turntable on a client: it turns its platter, swings and lifts its arm and opens its cover between the server's
 * words, plays its record to this listener as a music emitter (see EmitterAudioManager), and names the part pointed
 * at above the hotbar.
 */
public final class TurntableClient implements ClientFeature {
    /** How far it is heard (blocks), and how far off it is half as loud. */
    private static final double RANGE = 48, HALF = 12;
    /** How fast the arm swings to where it is set (degrees a tick), and the platter slows with the motor off. */
    private static final double SWING = 3, COAST = .12;
    private static final Set<TurntableBlock.Entity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    @Nullable
    private static Part hovered;
    @Nullable
    private static BlockPos hoveredAt;

    /** Where its moving parts are this tick and the last. */
    static final class Motion {
        double platter, lastPlatter, spin;
        double arm, lastArm;
        float cover, lastCover;

        float platter(float partialTick) {
            return (float) (Mth.lerp(partialTick, lastPlatter, platter) % 360);
        }

        float arm(float partialTick) {
            return (float) Mth.lerp(partialTick, lastArm, arm);
        }

        float cover(float partialTick) {
            return Mth.lerp(partialTick, lastCover, cover);
        }
    }

    @Override
    public void register(IEventBus modBus) {
        TurntableBlock.Entity.clientTick = TurntableClient::animate;
        TurntableBlock.Entity.clientLoaded = LOADED::add;
        TurntableBlock.Entity.clientRemoved = LOADED::remove;
        TurntableRenderer.register(modBus);
        EmitterAudioManager.add(Deck::near);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> hover());
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        TurntablePonder.register(helper);
    }

    static Motion motion(TurntableBlock.Entity turntable) {
        if (!(turntable.animation instanceof Motion motion)) {
            Motion fresh = new Motion();
            fresh.arm = fresh.lastArm = armTarget(turntable);
            fresh.cover = fresh.lastCover = turntable.getBlockState().getValue(TurntableBlock.OPEN) ? 1 : 0;
            turntable.animation = fresh;
            return fresh;
        }
        return motion;
    }

    /** Where the arm is to be: on its rest, or over the groove its needle is in. */
    private static double armTarget(TurntableBlock.Entity turntable) {
        return turntable.onRest() ? 0 : TurntableLayout.armAngleFor(
                TurntableLayout.radiusAt(turntable.fraction(turntable.getLevel().getGameTime())));
    }

    private static void animate(TurntableBlock.Entity turntable) {
        Motion m = motion(turntable);
        m.lastPlatter = m.platter;
        m.lastArm = m.arm;
        m.lastCover = m.cover;
        // A belt-driven platter comes up to speed over a second and a half, and coasts down slower.
        double full = turntable.speed().rpm * 360 / 60 / 20;
        double target = turntable.turning() ? full : 0;
        m.spin = target > m.spin ? Math.min(target, m.spin + full / TurntableBlock.Entity.SPIN_UP_TICKS)
                : Math.max(target, m.spin - COAST);
        m.platter += m.spin;
        if (m.platter > 3600) {
            m.platter -= 3600;
            m.lastPlatter -= 3600;
        }
        m.arm += Mth.clamp(armTarget(turntable) - m.arm, -SWING, SWING);
        m.cover = Mth.approach(m.cover, turntable.getBlockState().getValue(TurntableBlock.OPEN) ? 1 : 0, 1 / 15f);
    }

    /** How far the arm's head is raised (0 down, 1 up), at this part of a tick. */
    static float lift(TurntableBlock.Entity turntable, float partialTick) {
        if (turntable.onRest()) {
            return 1;
        }
        double since = turntable.getLevel().getGameTime() - turntable.leverAt() + partialTick;
        return turntable.raised() ? (float) Mth.clamp(since / TurntableBlock.Entity.RAISE_TICKS, 0, 1)
                : 1 - (float) Mth.clamp(since / TurntableBlock.Entity.DROP_TICKS, 0, 1);
    }

    /** How far up the lift lever is (0 down, 1 up): the lever itself flips over a few ticks. */
    static float lever(TurntableBlock.Entity turntable, float partialTick) {
        double since = turntable.getLevel().getGameTime() - turntable.leverAt() + partialTick;
        float moved = (float) Mth.clamp(since / 4, 0, 1);
        return turntable.raised() ? moved : 1 - moved;
    }

    private static void hover() {
        Minecraft mc = Minecraft.getInstance();
        Part was = hovered;
        BlockPos wasAt = hoveredAt;
        hovered = null;
        hoveredAt = null;
        if (mc.level != null && mc.screen == null && mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && mc.level.getBlockEntity(hit.getBlockPos()) instanceof TurntableBlock.Entity turntable) {
            var state = turntable.getBlockState();
            Part part = TurntableLayout.partAt(state.getValue(TurntableBlock.FACING), state.getValue(TurntableBlock.OPEN),
                    hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos())));
            if (part != null && part != Part.BASE) {
                hovered = part;
                hoveredAt = hit.getBlockPos().immutable();
            }
        }
        if (hovered != null && (hovered != was || !hoveredAt.equals(wasAt))) {
            mc.gui.setOverlayMessage(hovered.label(), false);
        }
    }

    /**
     * A turntable playing a music disc, as this listener hears it. A Custom Disc's link it plays as a source instead,
     * with its linked speakers (see TurntableBlock.Entity#nowPlaying).
     */
    private record Deck(TurntableBlock.Entity turntable) implements MusicEmitter {
        static Stream<Deck> near(Entity ear) {
            return LOADED.stream().filter(turntable -> turntable.getLevel() == ear.level()).map(Deck::new);
        }

        @Override
        public UUID id() {
            return UUID.nameUUIDFromBytes(("at_lpa2 " + turntable.getBlockPos().asLong()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int revision() {
            return turntable.revision();
        }

        @Override
        public ItemStack media() {
            return turntable.record();
        }

        @Override
        public boolean present() {
            return !turntable.isRemoved() && turntable.playing() && turntable.link() == null
                    && WorldEvents.isClientWorld(turntable.getLevel());
        }

        @Override
        public Vec3 position() {
            BlockPos pos = turntable.getBlockPos();
            Vec3 spindle = HifiLayout.toBlock(turntable.getBlockState().getValue(TurntableBlock.FACING),
                    TurntableLayout.CENTER_X, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z);
            return SableCompat.toRenderWorld(turntable.getLevel(), pos, Vec3.atLowerCornerOf(pos).add(spindle));
        }

        @Override
        @Nullable
        public Vec3 velocity() {
            return null;
        }

        @Override
        public double seconds() {
            return turntable.seconds(turntable.getLevel().getGameTime());
        }

        @Override
        public double rate() {
            return turntable.rate();
        }

        @Override
        public double range() {
            return RANGE;
        }

        @Override
        public double gain(Entity ear) {
            return MusicEmitter.falloff(SableCompat.eye(ear, 1).distanceTo(position()), HALF);
        }

        @Override
        public int cap() {
            return 4;
        }
    }
}
