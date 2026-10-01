package com.github.guoyyit.canopenstackjava.canopen.pdo;

/**
 * 单条 PDO 映射项：OD 索引、子索引与映射位长（CiA 301 0x1600/0x1A00 条目）。
 */
public record PdoMapping(int index, int subindex, int bitLength) {

    public static PdoMapping parse(int raw) {
        long unsigned = raw & 0xFFFFFFFFL;
        int bits = (int) (unsigned & 0xFF);
        int sub = (int) ((unsigned >> 8) & 0xFF);
        int idx = (int) ((unsigned >> 16) & 0xFFFF);
        return new PdoMapping(idx, sub, bits);
    }

    public int encode() {
        return ((index & 0xFFFF) << 16)
                | ((subindex & 0xFF) << 8)
                | (bitLength & 0xFF);
    }
}
