package org.haruyq.createmediasystems.blocks.broadcast;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.items.power.BatteryItem;
import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.jetbrains.annotations.Nullable;

final class MovingBroadcastTower implements MovementBehaviour {
    @Override
    public boolean mustTickWhileDisabled() {
        return true;
    }

    @Override
    public void tick(MovementContext context) {
        if (!(context.world instanceof ServerLevel level) || context.blockEntityData == null
                || context.contraption.entity == null) return;
        Runtime runtime = Runtime.of(context);
        long now = level.getGameTime();
        if (runtime.aboard != null) runtime.aboard.tick(now);
        if (context.disabled || runtime.volume <= 0 || runtime.base == null) {
            runtime.broadcast.stop();
            return;
        }

        BlockPos source = runtime.aboard == null ? runtime.link.follow(level).pos() : null;
        var carrier = context.contraption.entity;
        BlockPos anchor = carrier.blockPosition();
        boolean refresh = Streams.relayDue(level);
        if (source != null && refresh && BroadcastBaseBlock.stored(runtime.base) >= runtime.profile.energyPerTick) {
            var at = SableCompat.toWorld(level, anchor, carrier.toGlobalVector(MovingBroadcastPackets.Frame.mouth(context.localPos), 1));
            double reach = runtime.profile.reach;
            if (level.players().stream().anyMatch(player -> SableCompat.eye(player, 1).distanceToSqr(at) <= reach * reach)) {
                BroadcastCoverage.keepLoaded(level, source, context.localPos.offset(context.contraption.anchor));
            }
        }
        var playing = runtime.aboard == null ? source == null ? null : Streams.source(level, source) : runtime.aboard.nowPlaying();
        int energy = BroadcastBaseBlock.stored(runtime.base);
        if (playing == null || !UrlPolicy.isAllowed(playing.url()) || energy < runtime.profile.energyPerTick) {
            runtime.broadcast.stop();
            return;
        }
        runtime.base.putInt(BroadcastBaseBlock.ENERGY, energy - runtime.profile.energyPerTick);
        var frame = MovingBroadcastPackets.Frame.at(carrier.getId(), context.localPos,
                local -> SableCompat.toWorld(level, anchor, carrier.toGlobalVector(local, 1)));
        float gain = runtime.aboard != null ? runtime.aboard.gain()
                : level.getBlockEntity(source) instanceof MediaSource media ? media.gain() : 1;
        runtime.broadcast.relay(level, frame, source, runtime.aboard, runtime.channel,
                runtime.volume * gain, runtime.profile, refresh);
    }

    @Override
    public void stopMoving(MovementContext context) {
        if (context.temporaryData instanceof Runtime runtime) {
            runtime.broadcast.stop();
            writeExtraData(context);
        }
    }

    @Override
    public void writeExtraData(MovementContext context) {
        if (context.temporaryData instanceof Runtime runtime && runtime.aboard != null) {
            runtime.aboard.save(context.world.registryAccess());
        }
    }

    private static final class Runtime {
        final MovingBroadcast broadcast = new MovingBroadcast();
        final Link link;
        final BroadcastProfile profile;
        final Streams.Channel channel;
        final float volume;
        @Nullable final CompoundTag base;
        @Nullable final CapturedBroadcastSource aboard;

        Runtime(MovementContext context) {
            link = Link.load(context.blockEntityData, "Source", "SourceId");
            profile = ((BroadcastTowerBlock) context.state.getBlock()).profile();
            channel = Streams.Channel.byId(context.blockEntityData.getInt("Channel"));
            volume = context.blockEntityData.contains("Volume") ? Mth.clamp(context.blockEntityData.getFloat("Volume"), 0, 1) : 1;
            base = findBase(context);
            aboard = CapturedBroadcastSource.find(context, link);
        }

        static Runtime of(MovementContext context) {
            if (context.temporaryData instanceof Runtime runtime) return runtime;
            Runtime runtime = new Runtime(context);
            context.temporaryData = runtime;
            return runtime;
        }

        @Nullable
        private static CompoundTag findBase(MovementContext context) {
            var blocks = context.contraption.getBlocks();
            BlockPos below = context.localPos.below();
            StructureBlockInfo block = blocks.get(below);
            while (block != null && block.state().is(BroadcastFeature.MAST.get())) {
                below = below.below();
                block = blocks.get(below);
            }
            return block != null && block.state().is(BroadcastFeature.BASE.get()) ? block.nbt() : null;
        }
    }

    static final class BaseInteraction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos pos, AbstractContraptionEntity entity) {
            var battery = player.getItemInHand(hand);
            if (player.isSpectator() || !BatteryItem.fits(battery)) return false;
            if (player.level().isClientSide) return true;
            var block = entity.getContraption().getBlocks().get(pos);
            if (block == null || block.nbt() == null) return false;
            int energy = BroadcastBaseBlock.stored(block.nbt());
            energy += ChargeableItem.drawBattery(battery, BroadcastBaseBlock.CAPACITY - energy);
            block.nbt().putInt(BroadcastBaseBlock.ENERGY, energy);
            BroadcastBaseBlock.showCharge(player, energy);
            return true;
        }
    }
}
