package com.github.guoyyit.canopenstackjava.canopen.emcy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 紧急报文历史（对应 0x1003），保留全部记录、可查询可持久化。
 * 所有方法均在总线串行锁内被调用，这里额外同步以保证直接访问时的内存可见性。
 */
public class EmergencyHistory {

    private final List<EmergencyMessage> messages =
            Collections.synchronizedList(new ArrayList<>());

    public void append(EmergencyMessage message) {
        messages.add(message);
    }

    public List<EmergencyMessage> all() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public EmergencyMessage latest() {
        synchronized (messages) {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }

    public void clear() {
        messages.clear();
    }

    public int size() {
        return messages.size();
    }
}
