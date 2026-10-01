package com.github.guoyyit.canopenstackjava.pdo;

/** TPDO 传输（触发）类型。 */
public enum PdoTriggerType {
    /** 应用事件驱动，受抑制时间约束。 */
    EVENT,
    /** 定时器周期触发，受抑制时间约束。 */
    TIMER,
    /** 收到 SYNC 后触发（acyclic/synchronous）。 */
    SYNC;

    public static PdoTriggerType fromCode(int transmissionType) {
        if (transmissionType == 0) {
            return SYNC;
        }
        if (transmissionType == 0xFE) {
            return EVENT;
        }
        if (transmissionType >= 1 && transmissionType <= 240) {
            return SYNC;
        }
        return TIMER;
    }
}
