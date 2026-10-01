package com.github.guoyyit.canopenstackjava.canopen.nmt;

/**
 * NMT 节点生命周期状态（CiA 301）。
 */
public enum NmtState {

    INITIALISATION(0x00),
    PRE_OPERATIONAL(0x7F),
    OPERATIONAL(0x05),
    STOPPED(0x04);

    private final int code;

    NmtState(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
