package org.haruyq.createmediasystems;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsPacket;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class VideoPlayerGameTests {
    @GameTest(template = "empty")
    public static void wrenchSetsLightingAndVolumeApart(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, CreateMediaSystems.VIDEO_PLAYER.get());
        VideoPlayerBlock.Entity player = helper.getBlockEntity(pos);
        ServerPlayer wrencher = FakePlayerFactory.getMinecraft(helper.getLevel());
        wrencher.setPos(Vec3.atCenterOf(helper.absolutePos(pos.north())));
        // What the client sends when a value box is set with a wrench (see Create's ValueSettingsClient).
        int lightingId = player.getBehaviour(ScrollValueBehaviour.TYPE).netId(); // lighting has the default type
        new ValueSettingsPacket(helper.absolutePos(pos), 0, 40, null, null, Direction.NORTH, false, lightingId)
                .handle(wrencher);
        helper.assertTrue(player.lighting() == 0.4f, "lighting set, was " + player.lighting());
        helper.assertTrue(player.volume() == 1, "volume untouched, was " + player.volume());
        helper.succeed();
    }

    /** A speaker that drops a listener tells them, or their client plays its side on after the disc comes out. */
    @GameTest(template = "empty")
    public static void aListenerWhoLeavesIsToldToStop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos speakerPos = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlockAndUpdate(source, CreateMediaSystems.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(speakerPos, CreateMediaSystems.SPEAKER.get().defaultBlockState());
        ItemStack disc = new ItemStack(CreateMediaSystems.BLU_RAY.get());
        disc.set(CreateMediaSystems.PLAYLIST.get(), List.of("https://www.youtube.com/watch?v=listener"));
        ((VideoPlayerBlock.Entity) level.getBlockEntity(source)).insert(disc, null);
        SpeakerBlock.Entity speaker = (SpeakerBlock.Entity) level.getBlockEntity(speakerPos);

        List<CustomPacketPayload> received = new ArrayList<>();
        ServerPlayer listener = listener(level, speakerPos, received);
        try {
            speaker.configure(level, source, 1, Streams.Channel.MIX);
            helper.assertTrue(received.stream().anyMatch(StreamPackets.PlayAtBlock.class::isInstance),
                    "a listener in range got no stream: " + received);
            received.clear();
            listener.setPos(speakerPos.getCenter().add(JukeboxSettings.DEFAULT_RANGE + JukeboxSettings.DROP_MARGIN + 1, 0, 0));
            speaker.configure(level, source, 1, Streams.Channel.MIX);
            helper.assertTrue(received.contains(new StreamPackets.StopAtBlock(speakerPos)),
                    "a listener out of range wasn't told to stop: " + received);
        } finally {
            Streams.stop(speaker);
            level.removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    /**
     * A player at {@code pos} whose packets land in {@code received}. Not logged in (Sable's login packets refuse a
     * mock connection), just in the level, where relays look.
     */
    private static ServerPlayer listener(ServerLevel level, BlockPos pos, List<CustomPacketPayload> received) {
        ServerPlayer listener = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "listener"));
        listener.connection = new ServerGamePacketListenerImpl(level.getServer(), listener.connection.getConnection(),
                listener, CommonListenerCookie.createInitial(listener.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener sent) {
                if (packet instanceof ClientboundCustomPayloadPacket custom) {
                    received.add(custom.payload());
                }
            }
        };
        listener.setPos(pos.getCenter());
        level.addNewPlayer(listener);
        return listener;
    }

    /**
     * A remote's seek, of a disc shared over P2P: every listener is sent the video again from the second sought, as a
     * new load, and once they all have it loaded they are all told to play it from there together. Paused, it waits
     * there; past the end, it stops a second short.
     */
    @GameTest(template = "empty")
    public static void aSeekStartsEveryListenerAgainAtTheSameSecond(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos speakerPos = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlockAndUpdate(source, CreateMediaSystems.VIDEO_PLAYER.get().defaultBlockState());
        level.setBlockAndUpdate(speakerPos, CreateMediaSystems.SPEAKER.get().defaultBlockState());
        String url = "cms-disc://" + UUID.randomUUID() + "/" + UUID.randomUUID();
        ItemStack disc = new ItemStack(CreateMediaSystems.BLU_RAY.get());
        disc.set(CreateMediaSystems.PLAYLIST.get(), List.of(url));
        VideoPlayerBlock.Entity player = (VideoPlayerBlock.Entity) level.getBlockEntity(source);
        player.insert(disc, null);
        SpeakerBlock.Entity speaker = (SpeakerBlock.Entity) level.getBlockEntity(speakerPos);
        List<CustomPacketPayload> a = new ArrayList<>(), b = new ArrayList<>();
        ServerPlayer first = listener(level, speakerPos, a), second = listener(level, speakerPos.above(), b);
        try {
            speaker.configure(level, source, 1, Streams.Channel.MIX);
            long load = Streams.loadId(player);
            for (ServerPlayer each : List.of(first, second)) {
                // Each one's VLC tells the disc's length: ten minutes.
                Streams.started(each, speakerPos, 600);
            }
            helper.assertTrue(Streams.loaded(player), "both had it loaded, so it plays");
            a.clear();
            b.clear();

            player.control(VideoPlayerBlock.Action.SEEK, 300, first);
            speaker.configure(level, source, 1, Streams.Channel.MIX);
            long seekLoad = Streams.loadId(player);
            helper.assertTrue(seekLoad != load, "a seek is a new load");
            for (List<CustomPacketPayload> received : List.of(a, b)) {
                helper.assertTrue(received.contains(new StreamPackets.StopAtBlock(speakerPos))
                        && received.stream().anyMatch(p -> p instanceof StreamPackets.PlayAtBlock play
                        && play.url().equals(url) && play.startSeconds() == 300 && play.load() == seekLoad),
                        "each listener gets the disc again from 300 s: " + received);
            }
            helper.assertTrue(!Streams.loaded(player) && Streams.playing(player).ticks() == 300 * 20,
                    "it stands at 300 s while they load it, was " + Streams.playing(player).ticks());
            Streams.started(first, speakerPos, 600);
            helper.assertTrue(a.stream().noneMatch(StreamPackets.SyncAtBlock.class::isInstance),
                    "the first to load it waits for the other");
            Streams.started(second, speakerPos, 600);
            for (List<CustomPacketPayload> received : List.of(a, b)) {
                helper.assertTrue(received.contains(new StreamPackets.SyncAtBlock(speakerPos, 300)),
                        "both are told to play from 300 s together: " + received);
            }

            player.control(VideoPlayerBlock.Action.SEEK_BY, -10, first);
            helper.assertTrue(Streams.playing(player).ticks() == 290 * 20, "ten seconds back is 290 s");
            player.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, first);
            player.control(VideoPlayerBlock.Action.SEEK, 100, first);
            helper.assertTrue(player.nowPlaying() == null, "a seek leaves it paused");
            player.control(VideoPlayerBlock.Action.PLAY_PAUSE, 0, first);
            helper.assertTrue(Streams.playing(player).ticks() == 100 * 20, "and it plays on from 100 s");
            player.control(VideoPlayerBlock.Action.SEEK, 10_000, first);
            helper.assertTrue(Streams.playing(player).ticks() == 599 * 20,
                    "past the end it goes a second short of it, was " + Streams.playing(player).ticks());
        } finally {
            Streams.stop(speaker);
            level.removePlayerImmediately(first, Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(second, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }
}
