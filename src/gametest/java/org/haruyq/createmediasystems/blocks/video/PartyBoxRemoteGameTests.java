package org.haruyq.createmediasystems.blocks.video;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxEntity;
import org.haruyq.createmediasystems.items.partybox.stage320.PartyBoxFeature;
import org.haruyq.createmediasystems.stream.Remotable.Action;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PartyBoxRemoteGameTests {
    @GameTest(batch = "launcher", template = "empty")
    public static void remoteFollowsTheMovingPartyBoxAndChecksDistanceOnTheServer(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(1, 1, 1));
        var box = new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(), level);
        box.setPos(at.getX(), at.getY(), at.getZ());
        box.inventory.setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
        level.addFreshEntity(box);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "PartyBoxRemoteTest"));
        player.setPos(box.position().add(1, 0, 0));
        var remote = new ItemStack(VideoFeature.REMOTE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        player.setShiftKeyDown(true);
        box.interact(player, InteractionHand.MAIN_HAND);
        h.assertTrue(box.getUUID().equals(remote.get(VideoFeature.REMOTE_ENTITY.get())), "Sneak use pairs with the actual trolley");
        box.setPos(box.position().add(3, 0, 0));
        RemoteItem.remoteControl(player, new RemoteItem.RemoteControl(InteractionHand.MAIN_HAND, Action.SEEK, 30));
        h.assertTrue(box.positionTicks() == 600, "The remote seeks the same box after it has moved");
        player.setPos(box.position().add(VideoFeature.REMOTE_RANGE.get() + 2, 0, 0));
        RemoteItem.remoteControl(player, new RemoteItem.RemoteControl(InteractionHand.MAIN_HAND, Action.SEEK, 50));
        h.assertTrue(box.positionTicks() == 600, "The server rejects a seek from outside the remote range");
        h.succeed();
    }
}
