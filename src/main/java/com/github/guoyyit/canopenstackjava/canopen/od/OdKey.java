package com.github.guoyyit.canopenstackjava.canopen.od;

/**
 * 对象字典寻址键：索引 + 子索引。
 */
public record OdKey(int index, int subindex) {

    public static OdKey of(int index, int subindex) {
        return new OdKey(index, subindex);
    }
}
