package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.VideoSource;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What a right-click with the lighting link on a block does, decided in one place (resolve): the server does it
 * (perform), and the client says it before the click (hint).
 *
 * @param console the console it works on: the one clicked (PICK), the fixture's own (UNLINK), else the one picked
 * @param picked the console the link has picked, if any
 * @param target the fixture or media source clicked
 */
public record LinkAction(Kind kind, @Nullable LightingConsoleBlock.Entity console,
                         @Nullable LightingConsoleBlock.Entity picked, @Nullable BlockEntity target, int group,
                         boolean shift) {
    public enum Kind {
        /** Nothing: not a console, a fixture or a media source, or out of the player's hands. */
        NOTHING,
        /** A console's part, the console not complete. */
        ASSEMBLY,
        /** Picks the console clicked. */
        PICK,
        /** A fixture or media source, with no console picked. */
        PICK_FIRST,
        /** Too far from the picked console, or the console out of the player's hands. */
        TOO_FAR,
        /** Unlinks the fixture (Shift, or it is in the selected group already). */
        UNLINK,
        /** Links the fixture to the selected group. */
        LINK,
        /** The selected group runs another family of fixtures. */
        OTHER_FAMILY,
        /** The console has as many fixtures as it can. */
        FULL,
        /** Has the console follow the media source: its sound, and its picture if it has one (alone, with Shift). */
        SOURCE
    }

    /** What {@code player}'s right-click on {@code pos}, with the lighting link, does now. Changes nothing. */
    public static LinkAction resolve(Player player, BlockPos pos) {
        Level level = player.level();
        boolean shift = player.isShiftKeyDown();
        LightingConsoleBlock.Entity picked = LightingLinkItem.selected(player);
        if (!player.getMainHandItem().is(LightingFeature.LIGHTING_LINK.get()) || !player.mayBuild()
                || !level.isLoaded(pos) || !SableCompat.mayInteract(level, player, pos)
                || player.distanceToSqr(SableCompat.toWorld(level, pos.getCenter())) > Reach.QUILL * Reach.QUILL) {
            return nothing(picked);
        }
        Block block = level.getBlockState(pos).getBlock();
        if (block instanceof LightingConsoleBlock || block instanceof LightingMonitorBlock) {
            LightingConsoleBlock.Entity clicked = LightingConsoleBlock.find(level, pos);
            return clicked == null ? of(Kind.ASSEMBLY, null, picked, null, shift)
                    : of(Kind.PICK, clicked, picked, null, shift);
        }
        BlockEntity target = level.getBlockEntity(pos);
        if (!(target instanceof StageLightBlock.Entity) && !(target instanceof MediaSource)) {
            return nothing(picked);
        }
        if (picked == null) {
            return of(Kind.PICK_FIRST, null, null, target, shift);
        }
        if (!Link.inReach(level, picked.getBlockPos(), pos) || !SableCompat.mayInteract(level, player, picked.getBlockPos())) {
            return of(Kind.TOO_FAR, picked, picked, target, shift);
        }
        if (!(target instanceof StageLightBlock.Entity light)) {
            return of(Kind.SOURCE, picked, picked, target, shift);
        }
        LightingConsoleBlock.Entity previous = light.linked();
        if (previous != null && !SableCompat.mayInteract(level, player, previous.getBlockPos())) {
            return nothing(picked);
        }
        int group = picked.settings.selectedGroup;
        if (shift || picked.links(pos) && picked.group(pos) == group) {
            return of(Kind.UNLINK, previous, picked, target, shift);
        }
        Kind kind = !picked.accepts(light, group) ? Kind.OTHER_FAMILY
                : !picked.links(pos) && picked.fixtures().size() >= LightingConsoleBlock.MAX_LIGHTS ? Kind.FULL
                : Kind.LINK;
        return of(kind, picked, picked, target, shift);
    }

    private static LinkAction of(Kind kind, @Nullable LightingConsoleBlock.Entity console,
                                 @Nullable LightingConsoleBlock.Entity picked, @Nullable BlockEntity target,
                                 boolean shift) {
        return new LinkAction(kind, console, picked, target, picked == null ? 0 : picked.settings.selectedGroup, shift);
    }

    private static LinkAction nothing(@Nullable LightingConsoleBlock.Entity picked) {
        return of(Kind.NOTHING, null, picked, null, false);
    }

    /** What it would do, said before the click; null if nothing. */
    @Nullable
    public Component hint() {
        BlockPos pos = target == null ? null : target.getBlockPos();
        return switch (kind) {
            case NOTHING -> null;
            case ASSEMBLY -> LightingText.message(LightingText.WARNING, "assembly");
            case PICK -> console != picked ? LightingText.message("hint_console")
                    : LightingText.message("hint_picked", groupName(), LightingText.number(console.fixtures().size()));
            case PICK_FIRST -> LightingText.message(LightingText.WARNING, "pick_first");
            case TOO_FAR -> LightingText.message(LightingText.WARNING, "too_far");
            case UNLINK -> console == null ? LightingText.message(LightingText.OFF, "unlinked")
                    : LightingText.message("hint_unlink", LightingText.group(console.settings, console.group(pos)),
                            LightingText.number(console.order(pos) + 1));
            case LINK -> LightingText.message("hint_link", groupName());
            case OTHER_FAMILY -> otherFamily();
            case FULL -> LightingText.message(LightingText.WARNING, "full");
            case SOURCE -> LightingText.message(target instanceof VideoSource ? "hint_video" : "hint_audio");
        };
    }

    /** Does it, on the server, and tells {@code player} what it did. */
    public void perform(Player player) {
        switch (kind) {
            case NOTHING -> {
            }
            case PICK -> {
                Level level = player.level();
                Link link = Link.to(level, console.getBlockPos());
                player.getMainHandItem().set(LightingFeature.LIGHTING_TARGET.get(), GlobalPos.of(level.dimension(), link.pos()));
                player.getMainHandItem().set(LightingFeature.LIGHTING_SOURCE.get(), link.id());
                console.refreshLinks();
                say(player, LightingText.message(LightingText.ON, "picked", groupName()));
            }
            case UNLINK -> {
                ((StageLightBlock.Entity) target).unlink();
                say(player, LightingText.message(LightingText.OFF, "link_removed"));
            }
            case LINK -> {
                BlockPos pos = target.getBlockPos();
                if (console.bind((StageLightBlock.Entity) target, group)) {
                    say(player, LightingText.message(LightingText.ON, "linked", LightingText.group(console.settings, console.group(pos)),
                            LightingText.number(console.order(pos) + 1)));
                } else {
                    say(player, LightingText.message(LightingText.WARNING, "full"));
                }
            }
            case SOURCE -> {
                boolean video = target instanceof VideoSource;
                if (video) {
                    console.setVideoSource(target.getBlockPos());
                }
                if (!shift || !video) {
                    console.setAudioSource(target.getBlockPos());
                }
                console.changed();
                say(player, LightingText.message(LightingText.ON, "source_linked"));
            }
            case OTHER_FAMILY -> say(player, otherFamily());
            default -> say(player, hint());
        }
    }

    private Component groupName() {
        LightingConsoleBlock.Entity shown = console != null ? console : picked;
        return LightingText.group(shown.settings, shown.settings.selectedGroup);
    }

    private Component otherFamily() {
        return LightingText.message(LightingText.WARNING, "other_family", LightingText.group(console.settings, group),
                StageLightBlock.familyName(console.family(group)));
    }

    private static void say(Player player, Component message) {
        player.displayClientMessage(message, true);
    }
}
