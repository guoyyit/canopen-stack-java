package com.github.guoyyit.canopenstackjava.canopen.pdo;

/**
 * PDO 触发类型（0x1400/0x1800 通信参数子索引 2）。
 */
public enum PdoType {

    EVENT_DRIVEN(0xFF),
    TIMER_DRIVEN(254),
    SYNCHRONOUS(0);

    private final int transmissionType;

    PdoType(int transmissionType) {
        this.transmissionType = transmissionType;
    }

    public int getTransmissionType() {
        return transmissionType;
    }

    public static PdoType fromCode(int code) {
        if (code >= 0 && code <= 240) {
            return SYNCHRONOUS;
        }
        if (code == 254) {
            return TIMER_DRIVEN;
        }
        return EVENT_DRIVEN;
    }
}
