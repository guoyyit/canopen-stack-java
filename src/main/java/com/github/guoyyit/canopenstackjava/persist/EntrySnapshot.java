package com.github.guoyyit.canopenstackjava.persist;

/** 单个对象字典条目的持久化快照。 */
public record EntrySnapshot(int index, int subIndex, byte[] data) {
}
