package org.haruyq.createmediasystems.blocks.turntable.atlpa2;

import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.Part;
import org.haruyq.createmediasystems.blocks.audio.SpeakerBlock;
import org.haruyq.createmediasystems.disc.DiscFeature;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The turntable worked by right-clicks at its parts, as a player's clicks land on its outline. */
@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TurntableGameTests {
    /** Music disc "cat", 185 s long. */
    private static final double CAT = 185;

    private record Deck(GameTestHelper h, BlockPos pos, Player player) {
        static Deck place(GameTestHelper h, Direction facing) {
            ServerLevel level = h.getLevel();
            BlockPos pos = h.absolutePos(new BlockPos(1, 2, 1));
            level.setBlockAndUpdate(pos, TurntableFeature.BLOCK.get().defaultBlockState().setValue(TurntableBlock.FACING, facing));
            Player player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "turntable"));
            return new Deck(h, pos, player);
        }

        TurntableBlock.Entity turntable() {
            return (TurntableBlock.Entity) h.getLevel().getBlockEntity(pos);
        }

        long now() {
            return h.getLevel().getGameTime();
        }

        /** Right-clicks the turntable's point (sixteenths of its own space), holding {@code held}. */
        void click(double x, double y, double z, ItemStack held) {
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            BlockState state = h.getLevel().getBlockState(pos);
            Vec3 at = Vec3.atLowerCornerOf(pos).add(HifiLayout.toBlock(state.getValue(TurntableBlock.FACING), x, y, z));
            BlockHitResult hit = new BlockHitResult(at, Direction.UP, pos, false);
            var used = state.useItemOn(held, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
            if (used == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                state.useWithoutItem(h.getLevel(), player, hit);
            }
        }

        void click(Part part) {
            click((part.x0 + part.x1) / 2, (part.y0 + part.y1) / 2, (part.z0 + part.z1) / 2, ItemStack.EMPTY);
        }

        /** Right-clicks the record {@code fraction} of the way from its first groove to its run-out. */
        void clickGroove(double fraction) {
            click(TurntableLayout.CENTER_X - TurntableLayout.radiusAt(fraction), TurntableLayout.RECORD_TOP,
                    TurntableLayout.CENTER_Z, ItemStack.EMPTY);
        }
    }

    /**
     * Under the open cover a music disc or a Custom Disc goes on the platter by hand, a DVD does not; an empty hand
     * takes the record off, and a shut cover keeps the platter out of reach.
     */
    @GameTest(template = "empty")
    public static void aRecordGoesOnThePlatterAndComesOff(GameTestHelper h) {
        Deck deck = Deck.place(h, Direction.EAST);
        Vec3 platter = new Vec3(TurntableLayout.CENTER_X + 2, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z);
        deck.click(platter.x, platter.y, platter.z, new ItemStack(Items.MUSIC_DISC_CAT));
        h.assertTrue(deck.turntable().record().isEmpty() && h.getLevel().getBlockState(deck.pos).getValue(TurntableBlock.OPEN),
                "The shut cover keeps the platter out of reach: the click opens the cover");

        deck.click(platter.x, platter.y, platter.z, new ItemStack(DiscFeature.DVD.get()));
        h.assertTrue(deck.turntable().record().isEmpty(), "A DVD does not go on");
        deck.click(platter.x, platter.y, platter.z, new ItemStack(Items.MUSIC_DISC_CAT));
        h.assertTrue(deck.turntable().record().is(Items.MUSIC_DISC_CAT) && deck.player.getMainHandItem().isEmpty(),
                "A music disc goes on the platter from the hand");
        deck.click(platter.x, platter.y, platter.z, ItemStack.EMPTY);
        h.assertTrue(deck.turntable().record().isEmpty()
                && deck.player.getInventory().contains(stack -> stack.is(Items.MUSIC_DISC_CAT)), "An empty hand takes it off");

        deck.click(platter.x, platter.y, platter.z, new ItemStack(AudioFeature.CUSTOM_DISC.get()));
        h.assertTrue(deck.turntable().record().is(AudioFeature.CUSTOM_DISC.get()), "A Custom Disc goes on too");
        deck.click(Part.COVER_OPEN);
        h.assertTrue(!h.getLevel().getBlockState(deck.pos).getValue(TurntableBlock.OPEN), "The standing cover shuts again");
        h.succeed();
    }

    /**
     * Switched on and started, with the arm taken off its rest (which sets the needle down), the record plays once
     * the platter is up to speed and the needle has come down; START/STOP stops it where it is, and raising the
     * lever does too.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theRecordPlaysWhileTheNeedleIsDownOnTheTurningPlatter(GameTestHelper h) {
        Deck deck = Deck.place(h, Direction.SOUTH);
        deck.click(Part.COVER);
        deck.click(TurntableLayout.CENTER_X, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z + 3,
                new ItemStack(Items.MUSIC_DISC_CAT));
        deck.click(Part.START_STOP);
        h.assertFalse(deck.turntable().turning(), "START/STOP does nothing while it is switched off");
        deck.click(Part.POWER);
        deck.click(Part.START_STOP);
        deck.click(Part.ARM);
        TurntableBlock.Entity turntable = deck.turntable();
        h.assertTrue(turntable.turning() && !turntable.onRest() && !turntable.raised() && !turntable.playing(),
                "Started, the arm taken off its rest sets the needle down: it is still on its way down");
        int waited = Math.max(TurntableBlock.Entity.SPIN_UP_TICKS, TurntableBlock.Entity.DROP_TICKS) + 2;
        h.runAfterDelay(waited, () -> {
            h.assertTrue(turntable.playing(), "Up to speed with the needle down, it plays");
            int revision = turntable.revision();
            h.runAfterDelay(20, () -> {
                double played = turntable.seconds(deck.now());
                h.assertTrue(played > .9 && played < 1.6, "A second later it is a second in: " + played);
                deck.click(Part.START_STOP);
                h.assertTrue(!turntable.playing() && turntable.revision() > revision, "START/STOP stops it");
                double held = turntable.seconds(deck.now());
                deck.click(Part.START_STOP);
                deck.click(Part.LIFT);
                h.runAfterDelay(waited, () -> {
                    h.assertTrue(!turntable.playing() && turntable.seconds(deck.now()) == held,
                            "With the lever up it stays where it stopped");
                    deck.click(Part.ARM);
                    h.assertTrue(turntable.onRest() && turntable.raised() && turntable.seconds(deck.now()) == 0,
                            "Back on its rest, the arm is off the record");
                    h.succeed();
                });
            });
        });
    }

    /**
     * Left off its rest with the lever up (as a player leaves it who never found the lever), a click on the record
     * sets the needle down there and it plays; the lift lever still raises and lowers it.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aClickOnTheRecordSetsTheNeedleDown(GameTestHelper h) {
        Deck deck = Deck.place(h, Direction.NORTH);
        deck.click(Part.COVER);
        TurntableBlock.Entity turntable = deck.turntable();
        turntable.arrange(new ItemStack(Items.MUSIC_DISC_CAT), true, true, TurntableBlock.Speed.RPM_33, false, true, 0, CAT);
        deck.clickGroove(.2);
        int waited = TurntableBlock.Entity.DROP_TICKS + 2;
        h.runAfterDelay(waited, () -> {
            h.assertTrue(turntable.playing() && !turntable.raised() && turntable.seconds(deck.now()) >= CAT * .2,
                    "The needle came down where the record was clicked, and it plays: " + turntable.seconds(deck.now()));
            deck.click(Part.LIFT);
            double held = turntable.seconds(deck.now());
            h.assertTrue(!turntable.playing() && turntable.raised(), "The lever raises it and it stops");
            deck.click(Part.LIFT);
            h.runAfterDelay(waited, () -> {
                h.assertTrue(turntable.playing() && Math.abs(turntable.seconds(deck.now()) - held) < .2,
                        "The lever sets it down again where it was: " + turntable.seconds(deck.now()) + " against " + held);
                h.succeed();
            });
        });
    }

    /**
     * A speaker linked to the turntable plays its Custom Disc's link: from where the needle came down, not while the
     * needle is up, and from the groove clicked, as a new load its listeners start over on.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aLinkedSpeakerPlaysWhatTheNeedlePlays(GameTestHelper h) {
        Deck deck = Deck.place(h, Direction.NORTH);
        deck.click(Part.COVER);
        TurntableBlock.Entity turntable = deck.turntable();
        String url = new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString();
        ItemStack disc = new ItemStack(AudioFeature.CUSTOM_DISC.get());
        disc.set(AudioFeature.URL.get(), url);
        double length = 180;
        turntable.arrange(disc, true, true, TurntableBlock.Speed.RPM_33, false, true, 0, length);
        BlockPos speakerAt = h.absolutePos(new BlockPos(4, 2, 1));
        h.getLevel().setBlockAndUpdate(speakerAt, AudioFeature.SPEAKER.get().defaultBlockState());
        var speaker = (SpeakerBlock.Entity) h.getLevel().getBlockEntity(speakerAt);
        h.assertTrue(turntable instanceof org.haruyq.createmediasystems.stream.MediaSource, "A speaker quill takes it for a source");
        speaker.configure(h.getLevel(), deck.pos, 1, Streams.Channel.MIX);
        h.assertTrue(Streams.source(h.getLevel(), deck.pos) == null, "Nothing plays with the needle up");

        deck.clickGroove(.5);
        // The needle comes down; its listeners load the track (no one is near: a second), and the speaker relays it.
        int settle = TurntableBlock.Entity.DROP_TICKS + 3 * Streams.RELAY_TICKS;
        h.runAfterDelay(settle, () -> {
            Streams.Source playing = Streams.source(h.getLevel(), deck.pos);
            double from = length / 2 * PlaybackClock.TICKS_PER_SECOND;
            h.assertTrue(turntable.playing() && playing != null && playing.url().equals(url)
                    && playing.ticks() >= from && playing.ticks() < from + 2 * PlaybackClock.TICKS_PER_SECOND,
                    "It plays the link from where the needle came down: " + (playing == null ? null : playing.ticks()));
            h.assertTrue(Streams.emitters(h.getLevel(), deck.pos).contains(speaker), "The speaker plays it");
            h.assertTrue(Math.abs(playing.ticks() / (double) PlaybackClock.TICKS_PER_SECOND - turntable.seconds(deck.now())) < .1,
                    "The needle is where the speaker's track is");
            long load = Streams.loadId(turntable);

            deck.click(Part.LIFT);
            h.assertTrue(Streams.source(h.getLevel(), deck.pos) == null, "With the needle up it plays nothing");
            h.runAfterDelay(Streams.RELAY_TICKS + 1, () -> {
                h.assertFalse(Streams.emitters(h.getLevel(), deck.pos).contains(speaker), "And the speaker stops");

                deck.clickGroove(.25);
                h.runAfterDelay(settle, () -> {
                    Streams.Source sought = Streams.source(h.getLevel(), deck.pos);
                    double at = length / 4 * PlaybackClock.TICKS_PER_SECOND;
                    h.assertTrue(sought != null && sought.ticks() >= at && sought.ticks() < at + 2 * PlaybackClock.TICKS_PER_SECOND
                            && Streams.loadId(turntable) > load && Streams.emitters(h.getLevel(), deck.pos).contains(speaker),
                            "From the groove clicked, as a new load the speaker plays: " + (sought == null ? null : sought.ticks()));
                    h.succeed();
                });
            });
        });
    }

    /**
     * Right-clicking a spot of the record sets the needle down in the groove there: half way across is half way
     * through the track, and it plays on from there; at 45 the needle runs through the record 35 % faster, and at
     * the run-out it stops.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theArmSeeksToTheGrooveClicked(GameTestHelper h) {
        Deck deck = Deck.place(h, Direction.WEST);
        deck.click(Part.COVER);
        deck.click(TurntableLayout.CENTER_X, TurntableLayout.RECORD_TOP, TurntableLayout.CENTER_Z + 3,
                new ItemStack(Items.MUSIC_DISC_CAT));
        deck.click(Part.POWER);
        deck.click(Part.START_STOP);
        deck.click(Part.ARM);
        TurntableBlock.Entity turntable = deck.turntable();
        // It learns the record's length on its next lookup.
        h.runAfterDelay(25, () -> {
            h.assertTrue(turntable.length() == CAT, "It knows the record is " + CAT + " s: " + turntable.length());
            deck.clickGroove(.5);
            h.assertTrue(Math.abs(turntable.seconds(deck.now()) - CAT / 2) < 1e-6 && !turntable.raised()
                    && !turntable.playing(), "Half way across the record is half way through, the needle on its way down: "
                    + turntable.seconds(deck.now()));
            deck.click(Part.SPEED_45);
            long lowered = deck.now();
            h.runAfterDelay(TurntableBlock.Entity.DROP_TICKS + 20, () -> {
                double ran = (deck.now() - lowered - TurntableBlock.Entity.DROP_TICKS) / 20.0 * 1.35;
                double at = turntable.seconds(deck.now());
                h.assertTrue(turntable.playing() && Math.abs(at - (CAT / 2 + ran)) < .2,
                        "At 45 it plays on from there 35 % fast: " + at + " against " + (CAT / 2 + ran));
                deck.clickGroove(1);
                h.runAfterDelay(TurntableBlock.Entity.DROP_TICKS + 5, () -> {
                    h.assertTrue(!turntable.playing() && turntable.seconds(deck.now()) == CAT,
                            "At the run-out the needle stays down and nothing plays");
                    h.succeed();
                });
            });
        });
    }
}
