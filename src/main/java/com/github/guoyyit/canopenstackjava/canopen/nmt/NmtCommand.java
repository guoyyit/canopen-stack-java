package com.github.guoyyit.canopenstackjava.canopen.nmt;

/**
 * NMT 主站命令。
 */
public enum NmtCommand {

    ENTER_OPERATIONAL(1),
    STOP(2),
    ENTER_PRE_OPERATIONAL(128),
    RESET_NODE(129),
    RESET_COMMUNICATION(130);

    private final int code;

    NmtCommand(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
