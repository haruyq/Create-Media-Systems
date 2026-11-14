package org.haruyq.createmediasystems.blocks.piano;

import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;

public final class PianoTestPlayers {
    private PianoTestPlayers() {}

    public static ServerPlayer near(GameTestHelper helper, Vec3 position) {
        var level = helper.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "PianoTest");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        };
        player.setPos(position);
        var players = online(level.getServer().getPlayerList());
        players.put(player.getUUID(), player);
        level.players().add(player);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { remove(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { remove(); }

            private void remove() {
                players.remove(player.getUUID(), player);
                level.players().remove(player);
                channel.finishAndReleaseAll();
            }
        });
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> online(PlayerList players) {
        try {
            var field = PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            return (Map<UUID, ServerPlayer>) field.get(players);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
