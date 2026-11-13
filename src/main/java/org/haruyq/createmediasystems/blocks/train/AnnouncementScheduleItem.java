package org.haruyq.createmediasystems.blocks.train;

import com.simibubi.create.content.trains.schedule.ScheduleItem;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Edited with Create's own schedule screen; read by the train disc reader instead of driving a train. */
public class AnnouncementScheduleItem extends ScheduleItem {
    public AnnouncementScheduleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult handScheduleTo(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        return InteractionResult.PASS;
    }
}
