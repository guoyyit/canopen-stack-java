package com.github.guoyyit.canopenstackjava.nmt;

/** NMT 主站命令（CAN 数据字节 0）。 */
public enum NmtCommand {
    START(0x01),
    STOP(0x02),
    ENTER_PRE_OPERATIONAL(0x80),
    RESET_NODE(0x81),
    RESET_COMMUNICATION(0x82);

    private final int code;

    NmtCommand(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static NmtCommand fromCode(int code) {
        for (NmtCommand command : values()) {
            if (command.code == code) {
                return command;
            }
        }
        return null;
    }
}
