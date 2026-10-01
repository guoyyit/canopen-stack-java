package com.github.guoyyit.canopenstackjava.canopen.od;

/**
 * CANopen 基础数据类型。bitLength 为 0 表示变长（字符串/域）。
 */
public enum DataType {

    BOOLEAN(1, false),
    UNSIGNED8(8, false),
    INTEGER8(8, true),
    UNSIGNED16(16, false),
    INTEGER16(16, true),
    UNSIGNED32(32, false),
    INTEGER32(32, true),
    UNSIGNED64(64, false),
    INTEGER64(64, true),
    VISIBLE_STRING(0, false),
    DOMAIN(0, false);

    private final int bitLength;
    private final boolean signed;

    DataType(int bitLength, boolean signed) {
        this.bitLength = bitLength;
        this.signed = signed;
    }

    public int getBitLength() {
        return bitLength;
    }

    public boolean isSigned() {
        return signed;
    }
}
