package com.github.guoyyit.canopenstackjava.od;

/** 对象字典中的单个条目（索引 + 子索引）。 */
public final class OdEntry {

    private final int index;
    private final int subIndex;
    private final DataType type;
    private final AccessType access;
    private final Long min;
    private final Long max;
    private byte[] data;

    public OdEntry(int index, int subIndex, DataType type, AccessType access, byte[] data, Long min, Long max) {
        this.index = index;
        this.subIndex = subIndex;
        this.type = type;
        this.access = access;
        this.data = data == null ? new byte[0] : data.clone();
        this.min = min;
        this.max = max;
    }

    public int getIndex() {
        return index;
    }

    public int getSubIndex() {
        return subIndex;
    }

    public DataType getType() {
        return type;
    }

    public AccessType getAccess() {
        return access;
    }

    public Long getMin() {
        return min;
    }

    public Long getMax() {
        return max;
    }

    public byte[] getData() {
        return data.clone();
    }

    /** 内部直接访问，仅允许在字典锁内调用。 */
    byte[] dataRaw() {
        return data;
    }

    public void setDataRaw(byte[] raw) {
        this.data = raw == null ? new byte[0] : raw.clone();
    }

    /** 该条目类型的固定字节长度；变长类型返回 -1。 */
    public int expectedSize() {
        return type.getSizeBytes();
    }

    /** 将当前数据按无符号整数解释（用于范围校验）。 */
    public long currentAsLong() {
        long value = 0L;
        for (int i = data.length - 1; i >= 0; i--) {
            value = (value << 8) | (data[i] & 0xFFL);
        }
        return value;
    }
}
