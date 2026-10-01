package com.github.guoyyit.canopenstackjava.persist;

import java.util.List;

/** 节点紧急历史的持久化快照。 */
public record EmergencySnapshot(List<int[]> records, List<byte[]> fields) {

    public static EmergencySnapshot empty() {
        return new EmergencySnapshot(List.of(), List.of());
    }
}
