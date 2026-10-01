package com.github.guoyyit.canopenstackjava.pdo;

/** 单条 PDO 映射项：索引 / 子索引 / 位长度。 */
public record MappingEntry(int index, int subIndex, int bitLength) {

    public long encoded() {
        return ((long) (index & 0xFFFF) << 16)
                | ((long) (subIndex & 0xFF) << 8)
                | (bitLength & 0xFFL);
    }

    public static MappingEntry decode(long value) {
        int idx = (int) ((value >>> 16) & 0xFFFF);
        int sub = (int) ((value >>> 8) & 0xFF);
        int bits = (int) (value & 0xFF);
        return new MappingEntry(idx, sub, bits);
    }
}
