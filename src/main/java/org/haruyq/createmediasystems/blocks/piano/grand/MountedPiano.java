package org.haruyq.createmediasystems.blocks.piano.grand;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.jetbrains.annotations.Nullable;

final class MountedPiano implements MovementBehaviour {
    @Override
    public boolean isActive(MovementContext context) {
        return context.state.getValue(GrandPianoBlock.PART) == GrandPianoBlock.Part.KEYBOARD;
    }

    @Override
    public boolean mustTickWhileDisabled() { return true; }

    @Override
    public void tick(MovementContext context) {
        if (!isActive(context)) return;
        var carrier = context.contraption.entity;
        if (context.world.isClientSide && carrier != null) {
            var block = context.contraption.getBlocks().get(context.localPos);
            if (block != null && context.contraption.getBlockEntityClientSide(context.localPos) instanceof PianoBlockEntity piano) {
                piano.showMounted(carrier.getId(), block.state());
            }
        } else if (context.temporaryData instanceof PianoSession session) {
            if (carrier == null || !carrier.isAlive() || context.contraption.disassembled
                    || !context.contraption.getBlocks().containsKey(context.localPos)) session.close();
            else session.tick();
        }
    }

    @Override
    public void stopMoving(MovementContext context) {
        if (context.temporaryData instanceof PianoSession session) session.close();
    }

    @Nullable
    static PianoSession session(ServerLevel level, PianoPackets.Target target) {
        if (!(level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier)
                || !carrier.isAlive() || carrier.getContraption() == null || carrier.getContraption().disassembled) return null;
        var context = keyboardAt(carrier, target.pos());
        return context != null && context.temporaryData instanceof PianoSession session ? session : null;
    }

    @Nullable
    private static MovementContext keyboardAt(AbstractContraptionEntity carrier, BlockPos pos) {
        var block = carrier.getContraption().getBlocks().get(pos);
        if (block == null || !block.state().is(PianoFeature.PIANO.get())
                || block.state().getValue(GrandPianoBlock.PART) != GrandPianoBlock.Part.KEYBOARD) return null;
        var actor = carrier.getContraption().getActorAt(pos);
        return actor == null ? null : actor.right;
    }

    static PianoPackets.Frame frame(AbstractContraptionEntity carrier, BlockPos pos, BlockState state) {
        var at = carrier.toGlobalVector(GrandPianoBlock.soundboard(pos, state), 1);
        return new PianoPackets.Frame(SableCompat.toWorld(carrier.level(), carrier.blockPosition(), at),
                state.getValue(GrandPianoBlock.OPEN));
    }

    static final class Interaction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos pos, AbstractContraptionEntity carrier) {
            if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !carrier.isAlive()
                    || carrier.getContraption() == null || carrier.getContraption().disassembled) return false;
            var block = carrier.getContraption().getBlocks().get(pos);
            if (block == null || !block.state().is(PianoFeature.PIANO.get())) return false;
            if (!(player instanceof ServerPlayer server)) return player.level().isClientSide;
            var root = GrandPianoBlock.root(pos, block.state());
            var context = keyboardAt(carrier, root);
            if (context == null) return false;
            var rootBlock = carrier.getContraption().getBlocks().get(root);
            if (rootBlock == null) return false;
            if (!(context.temporaryData instanceof PianoSession)) {
                context.temporaryData = new PianoSession(server.serverLevel(), new PianoPackets.Target(carrier.getId(), root),
                        () -> frame(carrier, root, carrier.getContraption().getBlocks().get(root).state()),
                        action -> toggle(carrier, root, action));
            }
            var action = GrandPianoBlock.interaction(player, block.state().getValue(GrandPianoBlock.PART), rootBlock.state());
            ((PianoSession) context.temporaryData).interact(server, action);
            return true;
        }

        private void toggle(AbstractContraptionEntity carrier, BlockPos pos, Action action) {
            var block = carrier.getContraption().getBlocks().get(pos);
            carrier.setBlock(pos, new StructureBlockInfo(pos, GrandPianoBlock.moveLid(block.state(), action), block.nbt()));
        }
    }
}
