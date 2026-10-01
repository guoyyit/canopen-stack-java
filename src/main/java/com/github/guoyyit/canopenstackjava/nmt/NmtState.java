package com.github.guoyyit.canopenstackjava.nmt;

/** NMT 节点生命周期状态。 */
public enum NmtState {
    INITIALISATION,
    PRE_OPERATIONAL,
    OPERATIONAL,
    STOPPED;

    public boolean allowsSdo() {
        return this == PRE_OPERATIONAL || this == OPERATIONAL;
    }

    public boolean allowsPdo() {
        return this == OPERATIONAL;
    }

    public boolean allowsEmergency() {
        return this == PRE_OPERATIONAL || this == OPERATIONAL;
    }

    public boolean allowsHeartbeat() {
        return this == PRE_OPERATIONAL || this == OPERATIONAL || this == STOPPED;
    }

    /** NMT 心跳 / bootup 帧中的状态字节。 */
    public int code() {
        switch (this) {
            case OPERATIONAL:
                return 0x05;
            case STOPPED:
                return 0x04;
            case PRE_OPERATIONAL:
                return 0x7F;
            case INITIALISATION:
            default:
                return 0x00;
        }
    }
}
