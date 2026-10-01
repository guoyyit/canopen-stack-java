package com.github.guoyyit.canopenstackjava.emcy;

import java.util.List;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/** 紧急报文历史（环形缓冲，并发安全）。 */
public final class EmergencyHistory {

    private final int capacity;
    private final Deque<EmergencyRecord> records = new ArrayDeque<>();

    public EmergencyHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(EmergencyRecord record) {
        records.addLast(record);
        while (records.size() > capacity) {
            records.removeFirst();
        }
    }

    public synchronized List<EmergencyRecord> getAll() {
        return new ArrayList<>(records);
    }

    public synchronized void clear() {
        records.clear();
    }
}
