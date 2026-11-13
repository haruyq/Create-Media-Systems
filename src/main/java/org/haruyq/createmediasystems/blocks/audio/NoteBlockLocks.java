package org.haruyq.createmediasystems.blocks.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Note blocks whose tuning is waxed in place: honeycomb locks one, and only whoever locked it (or an operator)
 * unlocks it, with an axe. Kept by the chunk as who locked which block, until the note block goes.
 */
public final class NoteBlockLocks {
    static final Codec<Map<BlockPos, UUID>> CODEC = Codec.unboundedMap(
            Codec.STRING.xmap(key -> BlockPos.of(Long.parseLong(key)), pos -> Long.toString(pos.asLong())),
            UUIDUtil.STRING_CODEC);

    private NoteBlockLocks() {}

    /** What a click on a note block does instead of tuning it; null to tune it as usual. */
    @Nullable
    public static InteractionResult use(BlockState state, Level level, BlockPos pos, Player player) {
        if (!(level instanceof ServerLevel)) return null;
        ItemStack held = player.getMainHandItem();
        UUID owner = locks(level, pos).get(pos);
        if (owner == null) {
            if (!held.is(Items.HONEYCOMB)) return null;
            set(level, pos, player.getUUID());
            held.consume(1, player);
            level.levelEvent(null, 3003, pos, 0);
            player.displayClientMessage(Component.translatable("create_media_systems.note_block.locked"), true);
        } else if (held.canPerformAction(ItemAbilities.AXE_WAX_OFF)
                && (owner.equals(player.getUUID()) || player.hasPermissions(2))) {
            set(level, pos, null);
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            level.playSound(null, pos, SoundEvents.AXE_WAX_OFF, SoundSource.BLOCKS, 1, 1);
            level.levelEvent(null, 3004, pos, 0);
            player.displayClientMessage(Component.translatable("create_media_systems.note_block.unlocked"), true);
        } else {
            state.attack(level, pos, player);
            player.displayClientMessage(Component.translatable("create_media_systems.note_block.is_locked"), true);
        }
        return InteractionResult.CONSUME;
    }

    /** The note block at {@code pos} is gone, and its lock with it. */
    public static void removed(Level level, BlockPos pos) {
        if (level instanceof ServerLevel && locks(level, pos).containsKey(pos)) set(level, pos, null);
    }

    private static Map<BlockPos, UUID> locks(Level level, BlockPos pos) {
        return level.getChunkAt(pos).getExistingData(AudioFeature.NOTE_BLOCK_LOCKS).orElse(Map.of());
    }

    private static void set(Level level, BlockPos pos, @Nullable UUID owner) {
        Map<BlockPos, UUID> locks = new HashMap<>(locks(level, pos));
        if (owner == null) locks.remove(pos);
        else locks.put(pos.immutable(), owner);
        LevelChunk chunk = level.getChunkAt(pos);
        if (locks.isEmpty()) chunk.removeData(AudioFeature.NOTE_BLOCK_LOCKS);
        else chunk.setData(AudioFeature.NOTE_BLOCK_LOCKS, Map.copyOf(locks));
    }
}
