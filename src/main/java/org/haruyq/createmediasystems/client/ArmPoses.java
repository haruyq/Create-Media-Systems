package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * How what a player holds or does poses their arms, over vanilla's pose (mixin/HumanoidModelMixin). Poses are asked in
 * the order they were added, which is the order of the client's features: a later one's arm stands over an earlier
 * one's (a playing megaphone's raised arm over the disc launcher's supporting one).
 */
public final class ArmPoses {
    /** Sets the arms of {@code model} for {@code player}, if what it poses for is there. */
    @FunctionalInterface
    public interface Pose {
        /** {@code yaw} and {@code pitch}: the head's, in degrees, as HumanoidModel.setupAnim has them. */
        void pose(HumanoidModel<?> model, Player player, float yaw, float pitch);
    }

    private static final List<Pose> POSES = new ArrayList<>();

    private ArmPoses() {
    }

    public static void add(Pose pose) {
        POSES.add(pose);
    }

    /** After vanilla has posed {@code model} for {@code entity}. */
    public static void pose(HumanoidModel<?> model, LivingEntity entity, float yaw, float pitch) {
        if (entity instanceof Player player) {
            for (Pose pose : POSES) {
                pose.pose(model, player, yaw, pitch);
            }
        }
    }
}
