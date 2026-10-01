package com.github.guoyyit.canopenstackjava.od;

/** 对象字典条目的访问属性。 */
public enum AccessType {
    RO,
    WO,
    RW,
    CONST;

    public boolean canRead() {
        return this == RO || this == RW || this == CONST;
    }

    public boolean canWrite() {
        return this == WO || this == RW;
    }
}
