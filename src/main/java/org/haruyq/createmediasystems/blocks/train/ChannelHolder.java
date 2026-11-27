package org.haruyq.createmediasystems.blocks.train;

/** A train detector or receiver: the channel it is on ("" for none). */
public interface ChannelHolder {
    String channel();

    void setChannel(String channel);
}
