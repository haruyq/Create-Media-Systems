package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** A source a remote works from afar: its transport, its volume, and what the remote shows of them. */
public interface Remotable extends PlaylistView, MediaSource {
    /**
     * {@code action} from {@code by}'s remote; {@code value} is the entry (from 1) to select, the volume in percent,
     * the second to seek to, or the seconds to seek by.
     */
    void control(Action action, int value, @Nullable Player by);

    Component state();

    /**
     * What a remote asks, by an id that stays as it is on the wire whatever the order here. SEEK goes to a second of
     * the entry playing, SEEK_BY that many seconds on (or back) from where it is.
     */
    enum Action {
        PLAY_PAUSE(0), STOP(1), PREVIOUS(2), NEXT(3), SELECT(4), VOLUME(5), SEEK(6), SEEK_BY(7);

        private static final Action[] BY_ID = new Action[values().length];
        public static final StreamCodec<ByteBuf, Action> CODEC = ByteBufCodecs.idMapper(Action::byId, action -> action.id);

        static {
            for (Action action : values()) {
                BY_ID[action.id] = action;
            }
        }

        public final int id;

        Action(int id) {
            this.id = id;
        }

        public static Action byId(int id) {
            return BY_ID[Mth.clamp(id, 0, BY_ID.length - 1)];
        }
    }
}
