package com.github.guoyyit.canopenstackjava.od;

/** CANopen 对象字典基础数据类型（CiA 301）。 */
public enum DataType {
    UNSIGNED8(1),
    UNSIGNED16(2),
    UNSIGNED32(4),
    UNSIGNED64(8),
    INTEGER8(1),
    INTEGER16(2),
    INTEGER32(4),
    INTEGER64(8),
    VISIBLE_STRING(-1),
    OCTET_STRING(-1),
    DOMAIN(-1);

    private final int sizeBytes;

    DataType(int sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public boolean isFixedSize() {
        return sizeBytes > 0;
    }
}
