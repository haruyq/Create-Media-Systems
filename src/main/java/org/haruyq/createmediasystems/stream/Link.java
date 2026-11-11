package org.haruyq.createmediasystems.stream;

import java.util.Objects;
import java.util.UUID;

import org.haruyq.createmediasystems.SableCompat;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A link to another block: where it was last seen, and its {@link BlockIdentity}, by which the link follows it when
 * Sable moves it or its chunk reloads. A speaker or display links to its source, a fixture to its console, a console
 * to its fixtures and media, a remote to its player. Reading a link changes nothing; its owner calls {@link #follow}
 * when it reconciles, and saves what that returns.
 */
public record Link(@Nullable BlockPos pos, @Nullable UUID id) {
    public static final Link NONE = new Link(null, null);
    /** Farthest a block may be from the block it links to. */
    public static final int MAX_DISTANCE = 128;

    /** A link to the block at {@code pos} (none for null), which is given an identity on a server. */
    public static Link to(Level level, @Nullable BlockPos pos) {
        return pos == null ? NONE : new Link(pos.immutable(), BlockIdentity.claim(level, pos));
    }

    /** Whether {@code a} and {@code b} are near enough to link, measured in the world wherever Sable has them. */
    public static boolean inReach(Level level, BlockPos a, BlockPos b) {
        return SableCompat.distance(level, a, b) <= MAX_DISTANCE;
    }

    /**
     * Whether {@code player} may link the speaker or display at {@code emitter} to the {@code kind} of source at
     * {@code source}; tells them if it is too far.
     */
    public static boolean linkable(ServerPlayer player, BlockPos source, BlockPos emitter, Class<?> kind) {
        if (!player.level().isLoaded(source) || !kind.isInstance(player.level().getBlockEntity(source))) {
            return false;
        }
        if (inReach(player.level(), source, emitter)) {
            return true;
        }
        player.displayClientMessage(Component.translatable("create_media_systems.speaker.too_far", MAX_DISTANCE)
                .withStyle(ChatFormatting.RED), true);
        return false;
    }

    /**
     * This link as the block is now, on a server: moved to where it is, its position cleared if another block replaced
     * it, or (saved before links had identities) given the identity of the block at its position. Itself if nothing
     * changed, and off a server.
     */
    public Link follow(Level level) {
        if (!(level instanceof ServerLevel) || pos == null && id == null) {
            return this;
        }
        UUID now = id != null ? id : BlockIdentity.claim(level, pos);
        BlockPos at = BlockIdentity.find(level, now, pos);
        return Objects.equals(at, pos) && Objects.equals(now, id) ? this : new Link(at, now);
    }

    /**
     * Where the block {@code stack} links to (in its {@code at} and {@code identity} components) is now, followed in
     * {@code level}; the stack keeps that. Null if it links to nothing, or its block is gone.
     */
    @Nullable
    public static BlockPos follow(Level level, ItemStack stack, DataComponentType<GlobalPos> at, DataComponentType<UUID> identity) {
        GlobalPos target = stack.get(at);
        if (target == null) {
            return null;
        }
        Link saved = new Link(target.pos(), stack.get(identity));
        Link now = saved.follow(level);
        if (now.id() != null && !now.id().equals(saved.id())) {
            stack.set(identity, now.id());
        }
        if (now.pos() != null && !now.pos().equals(saved.pos())) {
            stack.set(at, GlobalPos.of(target.dimension(), now.pos()));
        }
        return now.pos();
    }

    public void save(CompoundTag tag, String posKey, String idKey) {
        if (pos != null) {
            tag.putLong(posKey, pos.asLong());
        }
        if (id != null) {
            tag.putUUID(idKey, id);
        }
    }

    /** The link saved under {@code posKey} (a long, or the int array speakers and displays once saved) and {@code idKey}. */
    public static Link load(CompoundTag tag, String posKey, String idKey) {
        BlockPos pos = tag.getTagType(posKey) == Tag.TAG_LONG ? BlockPos.of(tag.getLong(posKey))
                : NbtUtils.readBlockPos(tag, posKey).orElse(null);
        return new Link(pos, tag.hasUUID(idKey) ? tag.getUUID(idKey) : null);
    }
}
