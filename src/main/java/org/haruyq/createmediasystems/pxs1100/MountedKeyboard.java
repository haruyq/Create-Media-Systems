package org.haruyq.createmediasystems.pxs1100;

import java.util.List;

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
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.pxs1100.KeyboardPackets.Target;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.jetbrains.annotations.Nullable;

final class MountedKeyboard implements MovementBehaviour {
    @Override public boolean isActive(MovementContext context) {
        return context.state.getValue(KeyboardBlock.PART) == KeyboardBlock.Part.MAIN;
    }

    @Override public boolean mustTickWhileDisabled() { return true; }

    @Override public void tick(MovementContext context) {
        if (!isActive(context)) return;
        var carrier = context.contraption.entity;
        if (carrier == null || !carrier.isAlive() || context.contraption.disassembled) {
            stopMoving(context);
            return;
        }
        var block = context.contraption.getBlocks().get(context.localPos);
        if (block == null) return;
        if (context.world.isClientSide) {
            if (context.contraption.getBlockEntityClientSide(context.localPos) instanceof KeyboardBlockEntity keyboard) {
                keyboard.showMounted(carrier.getId(), block.state());
            }
        } else if (context.blockEntityData != null) {
            var host = host(context, carrier);
            host.runtime.tick();
            if (context.world.getGameTime() % 40 == 0 && host.runtime.powered()) host.broadcast();
        }
    }

    @Override public void stopMoving(MovementContext context) {
        if (context.temporaryData instanceof MountedHost host) {
            host.save();
            host.runtime.removed();
            context.temporaryData = null;
        }
    }

    @Override public void writeExtraData(MovementContext context) {
        if (context.temporaryData instanceof MountedHost host) host.save();
    }

    @Nullable static KeyboardRuntime find(ServerLevel level, Target target) {
        if (!(level.getEntity(target.carrier()) instanceof AbstractContraptionEntity carrier)) return null;
        var context = keyboardAt(carrier, target.pos());
        return context == null ? null : host(context, carrier).runtime;
    }

    @Nullable private static MovementContext keyboardAt(AbstractContraptionEntity carrier, BlockPos pos) {
        var contraption = carrier.getContraption();
        if (!carrier.isAlive() || contraption == null || contraption.disassembled) return null;
        var block = contraption.getBlocks().get(pos);
        if (block == null || !block.state().is(PxS1100Feature.BLOCK.get())
                || block.state().getValue(KeyboardBlock.PART) != KeyboardBlock.Part.MAIN) return null;
        var actor = contraption.getActorAt(pos);
        return actor != null && actor.right != null && actor.right.blockEntityData != null ? actor.right : null;
    }

    private static MountedHost host(MovementContext context, AbstractContraptionEntity carrier) {
        if (context.temporaryData instanceof MountedHost host) return host;
        var host = new MountedHost(context, carrier);
        context.temporaryData = host;
        return host;
    }

    private static final class MountedHost implements KeyboardRuntime.Host {
        private final MovementContext context;
        private final AbstractContraptionEntity carrier;
        private final Target target;
        private final KeyboardRuntime runtime;

        MountedHost(MovementContext context, AbstractContraptionEntity carrier) {
            this.context = context;
            this.carrier = carrier;
            target = new Target(carrier.getId(), context.localPos);
            runtime = new KeyboardRuntime(this, context.blockEntityData.getCompound(KeyboardBlockEntity.DATA));
        }

        @Override public ServerLevel world() { return (ServerLevel) context.world; }
        @Override public Target target() { return target; }
        @Override public BlockState state() {
            var block = context.contraption.getBlocks().get(target.pos());
            return block == null ? context.state : block.state();
        }

        @Override public KeyboardPackets.Frame frame() {
            var state = state();
            var left = carrier.toGlobalVector(KeyboardBlock.speaker(target.pos(), state, 0), 1);
            var right = carrier.toGlobalVector(KeyboardBlock.speaker(target.pos(), state, 1), 1);
            return new KeyboardPackets.Frame(SableCompat.toWorld(world(), carrier.blockPosition(), left),
                    SableCompat.toWorld(world(), carrier.blockPosition(), right));
        }

        @Override public void decorations(boolean stand, boolean rack) {
            var root = target.pos();
            var bass = KeyboardBlock.bass(root, state());
            var other = context.contraption.getBlocks().get(bass);
            if (other == null || !KeyboardBlock.part(other.state(), KeyboardBlock.Part.BASS, state())) return;
            decorate(root, stand, rack);
            decorate(bass, stand, rack);
        }

        private void decorate(BlockPos pos, boolean stand, boolean rack) {
            var block = context.contraption.getBlocks().get(pos);
            carrier.setBlock(pos, new StructureBlockInfo(pos, KeyboardBlockEntity.decorated(block.state(), stand, rack), block.nbt()));
        }

        void save() {
            var data = runtime.save();
            context.blockEntityData.put(KeyboardBlockEntity.DATA, data);
            var block = context.contraption.getBlocks().get(target.pos());
            if (block != null && block.nbt() != null && block.nbt() != context.blockEntityData) {
                block.nbt().put(KeyboardBlockEntity.DATA, data);
            }
        }

        void broadcast() {
            PacketDistributor.sendToPlayersTrackingEntity(carrier,
                    new KeyboardPackets.Update(target, frame(), runtime.view(), false, List.of(), KeyboardPackets.Routing.INTERNAL));
        }

        @Override public void changed(boolean sync) {
            save();
            if (sync) broadcast();
        }
    }

    static final class Interaction extends MovingInteractionBehaviour {
        @Override public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos pos, AbstractContraptionEntity carrier) {
            if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !carrier.isAlive()
                    || carrier.getContraption() == null || carrier.getContraption().disassembled) return false;
            var block = carrier.getContraption().getBlocks().get(pos);
            if (block == null || !block.state().is(PxS1100Feature.BLOCK.get())) return false;
            if (!(player instanceof ServerPlayer server)) return player.level().isClientSide;
            var root = KeyboardBlock.root(pos, block.state());
            var context = keyboardAt(carrier, root);
            if (context == null) return false;
            var runtime = host(context, carrier).runtime;
            var held = player.getItemInHand(hand);
            if (BatteryItem.fits(held)) runtime.battery(server, held);
            else runtime.open(server, false);
            return true;
        }
    }
}
