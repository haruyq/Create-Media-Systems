package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Target;

final class MountedKeyboardAdapter implements MovementBehaviour {
    @Override public boolean mustTickWhileDisabled() { return true; }

    @Override public void tick(MovementContext context) {
        if (!(context.world instanceof ServerLevel level) || context.blockEntityData == null) return;
        var contraption = context.contraption;
        var carrier = contraption.entity;
        if (carrier == null || contraption.disassembled) return;
        var block = contraption.getBlocks().get(context.localPos);
        if (block == null) return;
        var state = block.state();
        var facing = KeyboardPowerAdapter.output(state);
        var pos = context.localPos.relative(facing);
        var port = contraption.getBlocks().get(pos);
        int energy = KeyboardPowerAdapter.stored(context.blockEntityData);
        boolean active = false;
        if (!context.disabled && energy > 0 && port != null && KeyboardBlock.isAdapterPort(pos, port.state(), facing.getOpposite())) {
            var target = new Target(carrier.getId(), KeyboardBlock.root(pos, port.state()));
            var keyboard = MountedKeyboard.find(level, target);
            if (keyboard != null) {
                int moved = keyboard.charge(Math.min(energy, KeyboardPowerAdapter.TRANSFER), false);
                if (moved > 0) {
                    context.blockEntityData.putInt(KeyboardPowerAdapter.ENERGY, energy - moved);
                    if (block.nbt() != null && block.nbt() != context.blockEntityData) {
                        block.nbt().putInt(KeyboardPowerAdapter.ENERGY, energy - moved);
                    }
                }
                active = moved > 0 || keyboard.energy() == KeyboardRuntime.CAPACITY;
            }
        }
        if (state.getValue(BlockStateProperties.POWERED) != active) {
            carrier.setBlock(context.localPos, new StructureBlockInfo(context.localPos,
                    state.setValue(BlockStateProperties.POWERED, active), block.nbt()));
        }
    }
}
