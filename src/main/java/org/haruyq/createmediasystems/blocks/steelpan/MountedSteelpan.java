package org.haruyq.createmediasystems.blocks.steelpan;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets.Target;
import org.jetbrains.annotations.Nullable;

final class MountedSteelpan implements MovementBehaviour {
    private static final class Mallets {
        private boolean left;

        boolean next() {
            left = !left;
            return left;
        }
    }

    @Nullable
    private static BlockState steelpan(AbstractContraptionEntity carrier, BlockPos pos) {
        if (!carrier.isAlive() || carrier.getContraption() == null || carrier.getContraption().disassembled) return null;
        var block = carrier.getContraption().getBlocks().get(pos);
        return block != null && block.state().is(SteelpanFeature.BLOCK.get()) ? block.state() : null;
    }

    @Nullable
    static Vec3 origin(Level level, Target target, float partial) {
        if (!(level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier)
                || steelpan(carrier, target.pos()) == null) return null;
        Vec3 local = carrier.toGlobalVector(Target.soundPosition(target.pos()), partial);
        return Target.project(level, carrier.blockPosition(), local);
    }

    static void strike(ServerLevel level, Target target, int midi, int velocity) {
        if (!(level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier)
                || steelpan(carrier, target.pos()) == null) return;
        var actor = carrier.getContraption().getActorAt(target.pos());
        if (actor == null) return;
        if (!(actor.right.temporaryData instanceof Mallets)) actor.right.temporaryData = new Mallets();
        boolean left = ((Mallets) actor.right.temporaryData).next();
        SteelpanPackets.broadcast(level, new SteelpanPackets.Strike(target, midi, velocity, left));
    }

    static final class Interaction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos pos, AbstractContraptionEntity carrier) {
            if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || player.isSpectator()) return false;
            var state = steelpan(carrier, pos);
            if (state == null) return false;
            var target = new Target(carrier.getId(), pos);
            if (player.isShiftKeyDown()) {
                if (player instanceof ServerPlayer server) SteelpanPackets.open(server, target);
                return true;
            }
            var level = player.level();
            Vec3 eye = SableCompat.eye(player, 1);
            Vec3 localEye = local(carrier, eye);
            Vec3 direction = local(carrier, eye.add(player.getLookAngle())).subtract(localEye);
            int midi = SteelpanBlock.rayNote(localEye.subtract(Vec3.atBottomCenterOf(pos)), direction, state.getValue(SteelpanBlock.FACING));
            if (midi < 0) return false;
            if (level instanceof ServerLevel server && SteelpanPackets.canPlay(player, target)) strike(server, target, midi, 100);
            return true;
        }

        private static Vec3 local(AbstractContraptionEntity carrier, Vec3 world) {
            BlockPos anchor = carrier.blockPosition();
            Level level = carrier.level();
            Vec3 inPlot = level.isClientSide ? SableCompat.toRenderBlockSpace(level, anchor, world)
                    : SableCompat.toBlockSpace(level, anchor, world);
            return carrier.toLocalVector(inPlot, 1);
        }
    }
}
