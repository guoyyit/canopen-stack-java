package com.github.guoyyit.canopenstackjava.canopen.od;

/**
 * 对象字典条目的访问权限。
 */
public enum Access {

    READ_ONLY,
    WRITE_ONLY,
    READ_WRITE,
    CONSTANT;

    public boolean isReadable() {
        return this == READ_ONLY || this == READ_WRITE || this == CONSTANT;
    }

    public boolean isWritable() {
        return this == WRITE_ONLY || this == READ_WRITE;
    }
}
